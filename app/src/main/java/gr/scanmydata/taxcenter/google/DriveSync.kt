package gr.scanmydata.taxcenter.google

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.data.db.DriveFileEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import gr.scanmydata.taxcenter.debts.DebtsStore
import gr.scanmydata.taxcenter.engine.FileBridge
import gr.scanmydata.taxcenter.requests.RequestsStore
import gr.scanmydata.taxcenter.ui.AthensDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Συγχρονισμός των ληφθέντων εντύπων στο Google Drive, με δομή φακέλων ανά
 * πελάτη.
 *
 * Ο ριζικός φάκελος είναι **επιλογή του χρήστη** (`Settings.driveFolderPath`,
 * με `/` για υποφακέλους)· η δομή από κάτω του είναι σταθερή:
 *
 * ```
 * <ο φάκελος που διάλεξε ο χρήστης>/
 *   ├─ Πελάτες/
 *   │    └─ 123456783 — ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ/
 *   │         ├─ 2025/  E1_….pdf, E2_….pdf
 *   │         ├─ Χωρίς έτος/  STOIXEIA_….pdf
 *   │         ├─ Οφειλές/  Οφειλές.txt
 *   │         └─ Αιτήματα/  Αιτήματα.txt
 *   │              └─ 1000001-20260901-0001 — Τροποποιητική Ε1/  Αίτημα.txt, συνημμένα
 *   └─ Αντίγραφα/  taxcenter-backup-….smdbk   (κρυπτογραφημένα)
 * ```
 *
 * ## Καθρέφτης, όχι μετακόμιση
 *
 * Το τοπικό αντίγραφο **μένει**. Ζητήθηκε «όλα τα δεδομένα να ζουν στο cloud»,
 * και αυτό είναι το ένα σημείο που δεν έγινε κατά γράμμα — με δύο λόγους που
 * αξίζουν περισσότερο από τη συνέπεια:
 *
 *  * **Χωρίς δίκτυο δεν θα δούλευε τίποτα.** Το γραφείο ανοίγει έντυπα και
 *    στέλνει email από κινητό, συχνά σε πύλη που κόβει. Ένα PDF που πρέπει να
 *    κατέβει πριν το δεις είναι χειρότερο εργαλείο από ένα που είναι ήδη εκεί.
 *  * **Στο Drive τα PDF είναι ακρυπτογράφητα.** Τοπικά ζουν σε app-private
 *    αποθήκευση, πίσω από κλείδωμα εφαρμογής και `FLAG_SECURE`. Το να γίνει ο
 *    Drive το **μόνο** αντίγραφο θα σήμαινε ότι τα φορολογικά έντυπα τρίτων
 *    ζουν αποκλειστικά σε λογαριασμό που μοιράζεται, συγχρονίζεται και
 *    ανακτάται με κωδικό — χειρότερη θέση από αυτήν που έχουν σήμερα.
 *
 * Έτσι: ανεβαίνουν αυτόματα μόλις κατέβουν, όσο υπάρχει δίκτυο και σύνδεση, τα
 * id τους μένουν σε cache για ταχύτητα, και σε νέα συσκευή κατεβαίνουν πίσω.
 * Ό,τι ζητήθηκε λειτουργικά, χωρίς το τίμημα.
 */
class DriveSync(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val settings: Settings = Settings(context),
) {

    /** Τι κάνει η εφαρμογή με το Drive. */
    enum class Mode(val label: String, val description: String) {
        OFF(
            "Ανενεργό",
            "Τίποτα δεν ανεβαίνει. Τα δεδομένα ζουν μόνο στη συσκευή.",
        ),
        BACKUP(
            "Αυτόματο αντίγραφο ασφαλείας",
            "Μία φορά την ημέρα, κρυπτογραφημένο αντίγραφο ολόκληρης της βάσης στον " +
                "φάκελο «Αντίγραφα» του Drive σου. Η Google βλέπει μόνο κρυπτογράφημα.",
        ),
        SYNC(
            "Αντίγραφο και αρχεία πελατών",
            "Επιπλέον, σε φάκελο ανά πελάτη ανεβαίνουν τα έντυπα, οι οφειλές και τα " +
                "αιτήματα ΑΑΔΕ με τα συνημμένα τους. Αυτά είναι αναγνώσιμα στο Drive — " +
                "ό,τι θα έστελνες και με email.",
        ),
    }

    /**
     * @param stage τι ανεβαίνει τώρα — «Έντυπα», «Οφειλές», «Αιτήματα» — για το
     *   παράθυρο αναμονής
     */
    data class Progress(val uploaded: Int, val skipped: Int, val failed: Int, val stage: String = "") {
        val total: Int get() = uploaded + skipped + failed
    }

    // -------------------------------------------------------------- έλεγχοι

    /** Υπάρχει σύνδεση που να μπορεί να ανεβάσει; */
    fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val enabled: Boolean get() = settings.driveMode == Mode.SYNC && settings.driveReady

    // ------------------------------------------------------------ φάκελοι

    /**
     * Ο φάκελος ενός πελάτη για ένα έτος, φτιάχνοντας ό,τι λείπει.
     *
     * Το όνομα ξεκινά με το **ΑΦΜ** και όχι με την επωνυμία: ταξινομείται
     * σταθερά, δεν αλλάζει όταν διορθωθεί ένα όνομα, και είναι το μόνο στοιχείο
     * που ο λογιστής θυμάται με βεβαιότητα.
     */
    private fun folderFor(
        client: DriveClient,
        cache: MutableMap<String, String>,
        entity: ClientEntity,
        year: String,
    ): String {
        val parent = clientFolder(client, cache, entity)
        return subFolder(client, cache, entity, parent, year.ifBlank { NO_YEAR })
    }

    /** `Πελάτες/<ΑΦΜ — Επωνυμία>`, φτιάχνοντας ό,τι λείπει. */
    private fun clientFolder(
        client: DriveClient,
        cache: MutableMap<String, String>,
        entity: ClientEntity,
    ): String {
        val base = folderPath
        val clientsFolder = cache.getOrPut("$base/$CLIENTS") {
            client.ensureFolder(CLIENTS, rootFolder(client, cache))
        }
        val label = clientLabel(entity)
        return cache.getOrPut("$base/$CLIENTS/$label") {
            client.ensureFolder(label, clientsFolder)
        }
    }

    /** Ένας φάκελος μέσα στον φάκελο του πελάτη (έτος, «Οφειλές», «Αιτήματα»). */
    private fun subFolder(
        client: DriveClient,
        cache: MutableMap<String, String>,
        entity: ClientEntity,
        parent: String,
        name: String,
    ): String = cache.getOrPut("$folderPath/$CLIENTS/${clientLabel(entity)}/$name") {
        client.ensureFolder(name, parent)
    }

    private fun clientLabel(entity: ClientEntity): String = FileBridge.sanitiseSegment(
        buildString {
            append(entity.afm)
            if (entity.displayName != entity.afm) append(" — ").append(entity.displayName)
        },
    )

    fun backupFolder(client: DriveClient, cache: MutableMap<String, String>): String =
        cache.getOrPut("$folderPath/$BACKUPS") {
            client.ensureFolder(BACKUPS, rootFolder(client, cache))
        }

    /** Η διαδρομή που διάλεξε ο χρήστης, πάντα καθαρισμένη. */
    val folderPath: String get() = normalisePath(settings.driveFolderPath)

    /**
     * Φτιάχνει **τώρα** τη διαδρομή στον Drive και επιστρέφει την τελική μορφή.
     *
     * Υπάρχει για το στήσιμο: ο χρήστης γράφει πού τα θέλει και βλέπει αμέσως
     * τον φάκελο να εμφανίζεται στον Drive του, αντί να το ανακαλύψει την πρώτη
     * φορά που θα κατέβει έντυπο — ή να μην το ανακαλύψει ποτέ, επειδή κάτι
     * πήγε στραβά στην ταυτοποίηση.
     */
    suspend fun ensureFolders(accessToken: String): String = withContext(Dispatchers.IO) {
        val client = DriveClient(accessToken)
        val cache = HashMap<String, String>()
        rootFolder(client, cache)
        // Και οι δύο υποφάκελοι, ώστε η δομή να φαίνεται ολόκληρη από την αρχή.
        client.ensureFolder(CLIENTS, rootFolder(client, cache))
        client.ensureFolder(BACKUPS, rootFolder(client, cache))
        folderPath
    }

    /**
     * Ο φάκελος-ρίζα της εφαρμογής μέσα στον Drive, φτιάχνοντας **κάθε** τμήμα
     * της διαδρομής που λείπει.
     *
     * Η δημιουργία είναι ο μόνος δρόμος: με scope `drive.file` η εφαρμογή δεν
     * βλέπει φακέλους που έφτιαξε ο χρήστης μόνος του, οπότε δεν μπορεί να
     * «μπει» σε υπάρχοντα φάκελο ούτε να τον προτείνει σε λίστα. Ό,τι φτιάχνει
     * η ίδια το ξαναβρίσκει κανονικά — γι' αυτό η διαδρομή επιλέγεται μία φορά
     * και μετά μένει σταθερή.
     */
    private fun rootFolder(client: DriveClient, cache: MutableMap<String, String>): String {
        var parent: String? = null
        var key = ""
        for (segment in folderPath.split('/')) {
            key = if (key.isEmpty()) segment else "$key/$segment"
            val above = parent
            parent = cache.getOrPut(key) { client.ensureFolder(segment, above) }
        }
        return parent ?: cache.getOrPut(ROOT) { client.ensureFolder(ROOT) }
    }

    // ---------------------------------------------------------- ανεβάσματα

    /**
     * Ανεβάζει ό,τι δεν έχει ανέβει ακόμη: έντυπα, οφειλές, αιτήματα.
     *
     * Παραλείπει σιωπηλά τα ήδη συγχρονισμένα με **ίδιο μέγεθος**: το Drive δεν
     * χρειάζεται να ξαναδεί το ίδιο PDF, και μια παρτίδα 300 εντύπων δεν πρέπει
     * να ξαναανεβαίνει κάθε φορά που ανοίγει η εφαρμογή.
     *
     * Μια αποτυχία σε ένα αρχείο **δεν σταματά τα υπόλοιπα** — ίδια αρχή με τη
     * λήψη: ένα timeout δεν ακυρώνει τη δουλειά της ημέρας.
     */
    suspend fun syncAll(
        accessToken: String,
        onProgress: (Progress) -> Unit = {},
    ): Progress = withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        val folders = HashMap<String, String>()
        val clientsById = db.clients().all().associateBy { it.id }

        var uploaded = 0
        var skipped = 0
        var failed = 0

        val documents = allDocuments()
        for (document in documents) {
            val entity = clientsById[document.clientId] ?: continue
            val file = File(context.filesDir, document.relativePath)
            if (!file.isFile) {
                skipped++
                continue
            }
            val known = db.driveFiles().byPath(document.relativePath)
            if (known != null && known.bytes == file.length()) {
                skipped++
                continue
            }
            try {
                val parent = folderFor(drive, folders, entity, document.year)
                val entry = drive.upload(
                    file = file,
                    parentId = parent,
                    name = document.fileName,
                    mimeType = mimeOf(document.fileName),
                    existingId = known?.driveId,
                )
                db.driveFiles().put(
                    DriveFileEntity(
                        relativePath = document.relativePath,
                        driveId = entry.id,
                        remoteName = entry.name,
                        parentId = parent,
                        bytes = file.length(),
                        syncedAt = System.currentTimeMillis(),
                    ),
                )
                uploaded++
            } catch (e: Exception) {
                failed++
            }
            onProgress(Progress(uploaded, skipped, failed, STAGE_DOCUMENTS))
        }

        // Οφειλές και αιτήματα: όσοι πελάτες έχουν κάτι στη συσκευή.
        for (entity in clientsById.values) {
            if (entity.afm.isBlank()) continue
            val debts = runCatching { mirrorDebts(drive, folders, entity) }.getOrElse { Tally(failed = 1) }
            uploaded += debts.uploaded; skipped += debts.skipped; failed += debts.failed
            onProgress(Progress(uploaded, skipped, failed, STAGE_DEBTS))
            val requests = runCatching { mirrorRequests(drive, folders, entity) }.getOrElse { Tally(failed = 1) }
            uploaded += requests.uploaded; skipped += requests.skipped; failed += requests.failed
            onProgress(Progress(uploaded, skipped, failed, STAGE_REQUESTS))
        }

        if (uploaded > 0 || failed > 0) {
            db.audit().log(
                AuditEntity(
                    ts = System.currentTimeMillis(),
                    action = if (failed == 0) "DRIVE_SYNC" else "DRIVE_SYNC_PARTIAL",
                    detail = "$uploaded ανέβηκαν, $skipped ήδη συγχρονισμένα, $failed απέτυχαν",
                ),
            )
        }
        Progress(uploaded, skipped, failed)
    }

    /**
     * Κατεβάζει στη συσκευή ό,τι υπάρχει στο Drive αλλά λείπει τοπικά.
     *
     * Η περίπτωση που το χρειάζεται είναι μία και συγκεκριμένη: καινούργιο
     * κινητό, μετά την επαναφορά της βάσης από το κρυπτογραφημένο αντίγραφο. Η
     * βάση ξέρει ποια έντυπα υπάρχουν· τα αρχεία τους δεν είναι ακόμη εκεί.
     */
    suspend fun pullMissing(accessToken: String): Int = withContext(Dispatchers.IO) {
        val drive = DriveClient(accessToken)
        var restored = 0
        for (mapping in db.driveFiles().all()) {
            // Οι αναφορές κειμένου (οφειλές, αιτήματα) δεν είναι αρχεία της
            // συσκευής: ξαναγράφονται από τη βάση και τα JSON, δεν κατεβαίνουν.
            if (mapping.relativePath.startsWith(REPORT_PREFIX)) continue
            val target = File(context.filesDir, mapping.relativePath)
            if (target.isFile && target.length() == mapping.bytes) continue
            runCatching { drive.download(mapping.driveId, target) }
                .onSuccess { restored++ }
        }
        restored
    }

    /**
     * Εκτελεί την ουρά διαγραφών ([Settings.drivePendingErase]).
     *
     * Ο φάκελος ενός πελάτη βρίσκεται με το **ΑΦΜ** στην αρχή του ονόματος, σε
     * όλο τον Drive που βλέπει η εφαρμογή — όχι μόνο στη σημερινή διαδρομή:
     * αν ο φάκελος άλλαξε κάποτε, ή αν άλλαξε η επωνυμία του πελάτη, υπάρχουν
     * φάκελοι και εκεί. Το scope `drive.file` εγγυάται ότι η αναζήτηση δεν
     * αγγίζει τίποτα που δεν έφτιαξε η ίδια η εφαρμογή.
     *
     * Ό,τι δεν υπάρχει πια (404) μετρά ως διαγραμμένο. Ό,τι αποτύχει για άλλο
     * λόγο μένει στην ουρά για την επόμενη φορά.
     *
     * @return πόσα ολοκληρώθηκαν, πόσα μένουν
     */
    suspend fun erasePending(accessToken: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val pending = settings.drivePendingErase
        if (pending.isEmpty()) return@withContext 0 to 0
        val drive = DriveClient(accessToken)
        val done = ArrayList<String>()
        var folders = 0
        var files = 0
        for (entry in pending) {
            try {
                when {
                    entry.startsWith(ERASE_CLIENT) -> {
                        val afm = entry.removePrefix(ERASE_CLIENT).filter { it.isDigit() }
                        if (afm.isNotEmpty()) {
                            val query = "mimeType = '" + DriveClient.FOLDER_MIME + "' and name contains '" + afm +
                                "' and trashed = false"
                            for (folder in drive.list(query).filter { it.name.startsWith(afm) }) {
                                trashQuietly(drive, folder.id)
                                folders++
                            }
                        }
                    }
                    entry.startsWith(ERASE_FILE) -> {
                        trashQuietly(drive, entry.removePrefix(ERASE_FILE))
                        files++
                    }
                }
                done += entry
            } catch (e: Exception) {
                // Μένει στην ουρά.
            }
        }
        settings.settleDriveErase(done)
        if (done.isNotEmpty()) {
            db.audit().log(
                AuditEntity(
                    ts = System.currentTimeMillis(),
                    action = "DRIVE_ERASE",
                    detail = "$folders φάκελοι πελατών και $files αρχεία στον κάδο του Drive",
                ),
            )
        }
        done.size to (pending.size - done.size)
    }

    private fun trashQuietly(drive: DriveClient, id: String) {
        try {
            drive.trash(id)
        } catch (e: DriveClient.DriveFailed) {
            if (e.code != 404) throw e
        }
    }

    private data class Tally(val uploaded: Int = 0, val skipped: Int = 0, val failed: Int = 0) {
        operator fun plus(o: Tally) = Tally(uploaded + o.uploaded, skipped + o.skipped, failed + o.failed)
    }

    /**
     * `Οφειλές/Οφειλές.txt` του πελάτη — **ένα** αρχείο που αντικαθίσταται σε
     * κάθε ενημέρωση. Το Drive κρατά μόνο του τις παλιές εκδόσεις του αρχείου·
     * δεκάδες αρχεία με ημερομηνία στο όνομα θα έκρυβαν το τωρινό.
     */
    private suspend fun mirrorDebts(drive: DriveClient, folders: HashMap<String, String>, entity: ClientEntity): Tally {
        val snapshot = DebtsStore.load(context.filesDir, entity.afm)
        val changedAt = maxOf(snapshot.aadeAt, snapshot.keaoAt, snapshot.efkaAt)
        if (changedAt == 0L) return Tally()
        val key = REPORT_PREFIX + "debts/" + entity.afm
        val known = db.driveFiles().byPath(key)
        if (known != null && known.syncedAt >= changedAt) return Tally(skipped = 1)

        val text = DriveReports.debts(
            clientName = entity.displayName,
            afm = entity.afm,
            snapshot = snapshot,
            aadeAt = if (snapshot.aadeAt == 0L) "" else AthensDates.stamp(snapshot.aadeAt),
            keaoAt = if (snapshot.keaoAt == 0L) "" else AthensDates.stamp(snapshot.keaoAt),
            today = LocalDate.now(AthensDates.ZONE),
            efkaAt = if (snapshot.efkaAt == 0L) "" else AthensDates.stamp(snapshot.efkaAt),
        )
        val parent = subFolder(drive, folders, entity, clientFolder(drive, folders, entity), DEBTS)
        return uploadText(drive, key, parent, DriveReports.DEBTS_FILE, text, known)
    }

    /**
     * `Αιτήματα/`: η λίστα όλων, και ένας υποφάκελος για κάθε αίτημα που είναι
     * αποθηκευμένο ολόκληρο, με το κείμενό του και τα συνημμένα του.
     */
    private suspend fun mirrorRequests(drive: DriveClient, folders: HashMap<String, String>, entity: ClientEntity): Tally {
        val snapshot = RequestsStore.load(context.filesDir, entity.afm)
        if (snapshot.at == 0L || snapshot.requests.isEmpty() && snapshot.saved.isEmpty()) return Tally()
        val clientDir = { clientFolder(drive, folders, entity) }
        val requestsFolder = { subFolder(drive, folders, entity, clientDir(), REQUESTS) }
        var tally = Tally()

        val listKey = REPORT_PREFIX + "requests/" + entity.afm
        val listKnown = db.driveFiles().byPath(listKey)
        tally += if (listKnown != null && listKnown.syncedAt >= snapshot.at) {
            Tally(skipped = 1)
        } else {
            val text = DriveReports.requests(entity.displayName, entity.afm, snapshot.requests, AthensDates.stamp(snapshot.at))
            uploadText(drive, listKey, requestsFolder(), DriveReports.REQUESTS_FILE, text, listKnown)
        }

        for (id in snapshot.saved.keys) {
            val source = RequestsStore.detailFile(context.filesDir, entity.afm, id)
            val detail = RequestsStore.detail(context.filesDir, entity.afm, id) ?: continue
            val listed = snapshot.requests.firstOrNull { it.id == id }
            val folderName = DriveReports.requestFolder(
                id = id,
                caseNumber = detail.caseNumber.ifBlank { listed?.caseNumber.orEmpty() },
                title = listed?.title ?: detail.process.ifBlank { detail.topic },
            )
            val folder = {
                folders.getOrPut("$folderPath/$CLIENTS/${clientLabel(entity)}/$REQUESTS/$folderName") {
                    drive.ensureFolder(FileBridge.sanitiseSegment(folderName), requestsFolder())
                }
            }

            val key = REPORT_PREFIX + "request/" + entity.afm + "/" + id
            val known = db.driveFiles().byPath(key)
            tally += if (known != null && known.syncedAt >= source.lastModified()) {
                Tally(skipped = 1)
            } else {
                val retrieved = if (detail.retrievedAt == 0L) "" else AthensDates.stamp(detail.retrievedAt)
                val text = DriveReports.request(entity.displayName, entity.afm, listed, detail, retrieved)
                uploadText(drive, key, folder(), DriveReports.REQUEST_FILE, text, known)
            }

            for (file in detail.attachments.filter { it.downloaded }) {
                val local = RequestsStore.attachment(context.filesDir, entity.afm, file.saved) ?: continue
                val relative = local.relativeTo(context.filesDir).invariantSeparatorsPath
                val mapped = db.driveFiles().byPath(relative)
                if (mapped != null && mapped.bytes == local.length()) {
                    tally += Tally(skipped = 1)
                    continue
                }
                tally += try {
                    val parent = folder()
                    val entry = drive.upload(
                        file = local,
                        parentId = parent,
                        name = file.name.ifBlank { local.name },
                        mimeType = mimeOf(local.name),
                        existingId = mapped?.driveId,
                    )
                    db.driveFiles().put(
                        DriveFileEntity(relative, entry.id, entry.name, parent, local.length(), System.currentTimeMillis()),
                    )
                    Tally(uploaded = 1)
                } catch (e: Exception) {
                    Tally(failed = 1)
                }
            }
        }
        return tally
    }

    /** Ανεβάζει κείμενο ως `.txt` UTF-8, αντικαθιστώντας την προηγούμενη έκδοσή του. */
    private suspend fun uploadText(
        drive: DriveClient,
        key: String,
        parent: String,
        name: String,
        text: String,
        known: DriveFileEntity?,
    ): Tally {
        val temp = File.createTempFile("drive-report", ".txt", context.cacheDir)
        return try {
            temp.writeText(text, Charsets.UTF_8)
            val entry = drive.upload(
                file = temp,
                parentId = parent,
                name = name,
                mimeType = "text/plain; charset=utf-8",
                // Αν άλλαξε φάκελος (π.χ. νέα διαδρομή), ανεβαίνει ως νέο.
                existingId = known?.driveId?.takeIf { known.parentId == parent },
            )
            db.driveFiles().put(DriveFileEntity(key, entry.id, entry.name, parent, temp.length(), System.currentTimeMillis()))
            Tally(uploaded = 1)
        } catch (e: Exception) {
            Tally(failed = 1)
        } finally {
            // Το κείμενο έχει στοιχεία πελάτη: δεν μένει στην cache.
            temp.delete()
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".txt", ignoreCase = true) -> "text/plain"
        name.endsWith(".doc", ignoreCase = true) -> "application/msword"
        name.endsWith(".docx", ignoreCase = true) ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        name.endsWith(".xls", ignoreCase = true) -> "application/vnd.ms-excel"
        name.endsWith(".xlsx", ignoreCase = true) ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

    /**
     * Όλα τα έγγραφα, μία φορά.
     *
     * Ο DAO εκθέτει ροή για την οθόνη και `forClient` για την καρτέλα· ο
     * συγχρονισμός τα θέλει όλα ως στιγμιότυπο, χωρίς να παρακολουθεί αλλαγές.
     */
    private suspend fun allDocuments(): List<DocumentEntity> =
        db.clients().all().flatMap { db.documents().forClient(it.id) }

    companion object {
        /** Η προεπιλεγμένη διαδρομή, όταν ο χρήστης δεν διάλεξε άλλη. */
        const val ROOT = "ScanMyData Tax Center"
        const val CLIENTS = "Πελάτες"
        const val BACKUPS = "Αντίγραφα"
        const val NO_YEAR = "Χωρίς έτος"
        const val DEBTS = "Οφειλές"
        const val REQUESTS = "Αιτήματα"

        const val STAGE_DOCUMENTS = "Έντυπα"
        const val STAGE_DEBTS = "Οφειλές"
        const val STAGE_REQUESTS = "Αιτήματα"

        /**
         * Κλειδιά του πίνακα `drive_files` για αρχεία που **δεν** υπάρχουν στη
         * συσκευή ως έχουν — οι αναφορές κειμένου. Το `@` δεν βγαίνει ποτέ από
         * διαδρομή του `filesDir`.
         */
        const val REPORT_PREFIX = "@report/"

        /** Οι εγγραφές της ουράς διαγραφών — βλ. [erasePending]. */
        const val ERASE_CLIENT = "client:"
        const val ERASE_FILE = "file:"

        /**
         * Καθαρίζει μια διαδρομή που πληκτρολόγησε άνθρωπος.
         *
         * Πέφτουν τα κενά τμήματα («Γραφείο//Έντυπα/»), οι διπλές κάθετες και
         * οι χαρακτήρες που δεν στέκουν σε όνομα φακέλου. Κενή διαδρομή γυρίζει
         * στην προεπιλογή: το εναλλακτικό θα ήταν να σκορπιστούν φάκελοι
         * πελατών **στη ρίζα** του Drive του χρήστη.
         */
        fun normalisePath(raw: String): String {
            val parts = raw.split('/', '\\')
                .map { segment -> segment.trim().filterNot { it in FORBIDDEN }.trim() }
                .filter { it.isNotBlank() }
                // Τρία επίπεδα φτάνουν και με το παραπάνω· βαθύτερη ιεραρχία
                // σημαίνει μόνο περισσότερες κλήσεις στο Drive σε κάθε λήψη.
                .take(3)
            return if (parts.isEmpty()) ROOT else parts.joinToString("/")
        }

        private val FORBIDDEN = charArrayOf('\n', '\r', '\t')
    }
}
