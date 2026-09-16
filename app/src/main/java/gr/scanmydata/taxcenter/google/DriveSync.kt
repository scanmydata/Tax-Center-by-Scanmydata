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
import gr.scanmydata.taxcenter.engine.FileBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 *   │         └─ Χωρίς έτος/  STOIXEIA_….pdf
 *   └─ Αντίγραφα/  taxcenter-backup-….smdbk
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
            "Μόνο αντίγραφο ασφαλείας",
            "Χειροκίνητο, κρυπτογραφημένο αντίγραφο ολόκληρης της βάσης. Η Google " +
                "βλέπει μόνο κρυπτογράφημα.",
        ),
        SYNC(
            "Αντίγραφο και συγχρονισμός εντύπων",
            "Επιπλέον, κάθε έντυπο που κατεβαίνει ανεβαίνει αυτόματα σε φάκελο ανά " +
                "πελάτη και έτος. Τα PDF στο Drive είναι αναγνώσιμα — είναι τα ίδια " +
                "αρχεία που θα έστελνες με email.",
        ),
    }

    data class Progress(val uploaded: Int, val skipped: Int, val failed: Int) {
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

    val enabled: Boolean get() = settings.driveMode == Mode.SYNC

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
        val base = folderPath
        val clientsFolder = cache.getOrPut("$base/$CLIENTS") {
            client.ensureFolder(CLIENTS, rootFolder(client, cache))
        }
        val label = FileBridge.sanitiseSegment(
            buildString {
                append(entity.afm)
                if (entity.displayName != entity.afm) append(" — ").append(entity.displayName)
            },
        )
        val clientFolder = cache.getOrPut("$base/$CLIENTS/$label") {
            client.ensureFolder(label, clientsFolder)
        }
        val yearLabel = year.ifBlank { NO_YEAR }
        return cache.getOrPut("$base/$CLIENTS/$label/$yearLabel") {
            client.ensureFolder(yearLabel, clientFolder)
        }
    }

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
     * Ανεβάζει ό,τι δεν έχει ανέβει ακόμη.
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

        for (document in allDocuments()) {
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
            onProgress(Progress(uploaded, skipped, failed))
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
            val target = File(context.filesDir, mapping.relativePath)
            if (target.isFile && target.length() == mapping.bytes) continue
            runCatching { drive.download(mapping.driveId, target) }
                .onSuccess { restored++ }
        }
        restored
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        name.endsWith(".json", ignoreCase = true) -> "application/json"
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
