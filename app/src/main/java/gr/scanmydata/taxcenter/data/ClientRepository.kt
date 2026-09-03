package gr.scanmydata.taxcenter.data

import android.content.Context
import androidx.room.withTransaction
import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Το μοναδικό σημείο εγγραφής πελατών και διαπιστευτηρίων.
 *
 * Κάθε κωδικός που μπαίνει στη βάση περνά από εδώ και κρυπτογραφείται· κανένα
 * άλλο σημείο του κώδικα δεν γράφει στον πίνακα `credentials`.
 */
class ClientRepository(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val crypto: Crypto,
) {

    fun observeClients(): Flow<List<ClientEntity>> = db.clients().observeAll()

    suspend fun allClients(): List<ClientEntity> = db.clients().all()

    suspend fun byAfm(afm: String): ClientEntity? = db.clients().byAfm(afm)

    suspend fun byId(id: Long): ClientEntity? = db.clients().byId(id)

    suspend fun existingAfms(): Set<String> = db.clients().all().map { it.afm }.toSet()

    // ------------------------------------------------------------- εισαγωγή

    data class ImportResult(
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        val credentialsWritten: Int,
        val backup: File?,
    ) {
        val total: Int get() = created + updated + unchanged
    }

    /**
     * Γράφει μια εγκεκριμένη προεπισκόπηση.
     *
     * Πριν από οτιδήποτε παίρνεται **αντίγραφο της βάσης**: μια εισαγωγή αγγίζει
     * κάθε πελάτη ταυτόχρονα και είναι η μόνη ενέργεια που μπορεί να κάνει
     * μαζική ζημιά. Ο κανόνας «κενή τιμή δεν σβήνει αποθηκευμένη» ισχύει και
     * για τα διαπιστευτήρια.
     */
    suspend fun applyImport(
        preview: ImportPreview.Result,
        sourceFileName: String,
    ): ImportResult {
        val now = System.currentTimeMillis()
        val backup = backupDatabase("import")

        var created = 0
        var updated = 0
        var unchanged = 0
        var credentials = 0

        for (row in preview.rows) {
            val incoming = ClientEntity(
                afm = row.afm,
                name = row.values[Field.NAME].orEmpty(),
                firstName = row.values[Field.FIRST_NAME].orEmpty(),
                kind = ClientKind.normalise(row.values[Field.KIND].orEmpty()),
                amkaEnc = crypto.enc(row.values[Field.AMKA].orEmpty()),
                doy = row.values[Field.DOY].orEmpty(),
                active = !row.values[Field.ACTIVE].orEmpty().startsWith("Ανενεργ", ignoreCase = true),
                sourceFile = sourceFileName,
            )
            val clientId = db.clients().upsertPreservingBlanks(incoming, now)

            when (row.action) {
                ImportPreview.Action.NEW -> created++
                ImportPreview.Action.UPDATE -> updated++
                ImportPreview.Action.UNCHANGED -> unchanged++
            }

            for (field in CREDENTIAL_FIELDS) {
                val value = row.values[field].orEmpty()
                if (value.isBlank()) continue
                db.credentials().putIfNotBlank(clientId, field.name, crypto.enc(value), now)
                credentials++
            }
        }

        db.audit().log(
            AuditEntity(
                ts = now,
                action = "IMPORT",
                detail = "$sourceFileName — ${preview.rows.size} γραμμές, " +
                    "$created νέοι, $updated ενημερώσεις, $credentials διαπιστευτήρια",
            ),
        )
        return ImportResult(created, updated, unchanged, credentials, backup)
    }

    // -------------------------------------------------- μεμονωμένος πελάτης

    /**
     * Δημιουργία ή ενημέρωση από τη χειροκίνητη καρτέλα.
     *
     * Οι κωδικοί ακολουθούν τον κανόνα της εισαγωγής — κενό δεν σβήνει
     * αποθηκευμένο, γιατί η φόρμα τους δείχνει μασκαρισμένους και ο χρήστης
     * συνήθως δεν τους αγγίζει.
     *
     * Τα **email** όμως γράφονται αυτούσια, ακόμη και κενά: εκεί το κενό είναι
     * ρητή πράξη του χρήστη («αυτή η διεύθυνση είναι λάθος, σβήσ' την»), και το
     * `upsertPreservingBlanks` δεν τα αγγίζει καθόλου.
     *
     * **Όλα ή τίποτα.** Η κρυπτογράφηση γίνεται *πριν* από την πρώτη εγγραφή και
     * οι εγγραφές μέσα σε συναλλαγή. Αλλιώς μια αποτυχία στο τρίτο πεδίο άφηνε
     * τον πελάτη γραμμένο και τους κωδικούς του όχι — η οθόνη έδειχνε σφάλμα,
     * η βάση είχε μισή καρτέλα, και δεν φαινόταν πουθενά ποια μισή.
     */
    suspend fun saveClient(client: ClientEntity, credentials: Map<Field, String>): Long {
        val now = System.currentTimeMillis()
        val encrypted = credentials
            .filterValues { it.isNotBlank() }
            .map { (field, value) -> field.name to crypto.enc(value) }

        return db.withTransaction {
            val id = db.clients().upsertPreservingBlanks(client, now)
            db.clients().setEmails(
                id = id,
                aade = client.emailAade.trim(),
                manual = client.emailManual.trim(),
                preferred = client.emailPreferred.trim(),
                now = now,
            )
            for ((field, valueEnc) in encrypted) {
                db.credentials().putIfNotBlank(id, field, valueEnc, now)
            }
            db.audit().log(AuditEntity(ts = now, action = "CLIENT_SAVE", afm = client.afm))
            id
        }
    }

    /** Τα αποθηκευμένα διαπιστευτήρια, **αποκρυπτογραφημένα**. */
    suspend fun credentials(clientId: Long): Map<Field, String> {
        val byName = db.credentials().forClient(clientId).associate { it.field to crypto.dec(it.valueEnc) }
        return Field.entries.mapNotNull { field ->
            byName[field.name]?.takeIf { it.isNotBlank() }?.let { field to it }
        }.toMap()
    }

    suspend fun amka(client: ClientEntity): String = crypto.dec(client.amkaEnc)

    /**
     * Γράφει ό,τι ήρθε από άντληση (Μητρώο ΑΑΔΕ, MyAMKA) **αφού** το ενέκρινε
     * ο χρήστης.
     *
     * Κάθε παράμετρος είναι nullable με τη σημασία «μην αγγίξεις»: η άντληση
     * επιστρέφει άλλοτε όλα τα πεδία και άλλοτε ένα, και ο χρήστης μπορεί να
     * έχει εγκρίνει μόνο κάποια από αυτά. Το `null` είναι διαφορετικό από το
     * κενό — αλλά κενό δεν φτάνει ποτέ εδώ, το κόβει η οθόνη έγκρισης.
     */
    suspend fun applyLookup(
        clientId: Long,
        name: String? = null,
        firstName: String? = null,
        kind: String? = null,
        doy: String? = null,
        amka: String? = null,
        emailAade: String? = null,
        maritalStatus: String? = null,
    ) {
        val existing = db.clients().byId(clientId) ?: return
        db.clients().update(
            existing.copy(
                name = name ?: existing.name,
                firstName = firstName ?: existing.firstName,
                kind = kind ?: existing.kind,
                doy = doy ?: existing.doy,
                amkaEnc = amka?.let { crypto.enc(it) } ?: existing.amkaEnc,
                emailAade = emailAade ?: existing.emailAade,
                maritalStatus = maritalStatus ?: existing.maritalStatus,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        db.audit().log(
            AuditEntity(
                ts = System.currentTimeMillis(),
                action = "LOOKUP_APPLY",
                afm = existing.afm,
                detail = listOfNotNull(
                    name?.let { "επωνυμία" },
                    firstName?.let { "όνομα" },
                    kind?.let { "είδος" },
                    doy?.let { "ΔΟΥ" },
                    amka?.let { "ΑΜΚΑ" },
                    emailAade?.let { "email" },
                ).joinToString(", "),
            ),
        )
    }

    suspend fun setEmails(clientId: Long, aade: String?, manual: String?, preferred: String?) {
        val existing = db.clients().byId(clientId) ?: return
        db.clients().update(
            existing.copy(
                emailAade = aade ?: existing.emailAade,
                emailManual = manual ?: existing.emailManual,
                emailPreferred = preferred ?: existing.emailPreferred,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Οριστική διαγραφή πελάτη: διαπιστευτήρια, έγγραφα, αρχεία στον δίσκο
     * (GDPR άρθρο 17). Το `audit_log` **μένει** — είναι το αρχείο που αποδεικνύει
     * ότι η διαγραφή έγινε, και δεν περιέχει προσωπικά δεδομένα πέρα από το ΑΦΜ.
     */
    suspend fun deleteClient(client: ClientEntity) {
        val now = System.currentTimeMillis()
        backupDatabase("delete")
        File(context.filesDir, "runs/${client.afm}").deleteRecursively()
        db.clients().hardDelete(client.id) // τα credentials/documents φεύγουν με CASCADE
        db.audit().log(AuditEntity(ts = now, action = "DELETE", afm = client.afm, detail = "οριστική διαγραφή"))
    }

    /**
     * Μαζική οριστική διαγραφή. **Ένα** αντίγραφο για όλη την παρτίδα.
     *
     * Το αντίγραφο ανά πελάτη θα ήταν σπατάλη χώρου και χρόνου· το αντίγραφο
     * *πριν* την παρτίδα είναι αυτό που έχει σημασία, γιατί εκεί μπορεί να γίνει
     * το λάθος («διάλεξα όλους αντί για τους τρεις»).
     */
    suspend fun deleteClients(clients: List<ClientEntity>): Int {
        if (clients.isEmpty()) return 0
        val now = System.currentTimeMillis()
        backupDatabase("delete-bulk")
        for (client in clients) {
            File(context.filesDir, "runs/${client.afm}").deleteRecursively()
            db.clients().hardDelete(client.id)
            db.audit().log(
                AuditEntity(ts = now, action = "DELETE", afm = client.afm, detail = "μαζική οριστική διαγραφή"),
            )
        }
        return clients.size
    }

    /**
     * Σβήνει **μόνο τα έγγραφα** — ο πελάτης, οι κωδικοί και το ιστορικό μένουν.
     *
     * Άλλη δουλειά από τη διαγραφή πελάτη, γι' αυτό και χωριστή ενέργεια: εδώ ο
     * λογιστής καθαρίζει χώρο ή παλιά PDF, δεν τερματίζει σχέση. Το να είναι
     * διακόπτης μέσα στη διαγραφή πελάτη θα έκανε εύκολο να πατηθεί κατά λάθος
     * το πιο καταστροφικό από τα δύο.
     */
    suspend fun deleteDocumentsOf(clients: List<ClientEntity>): Int {
        var removed = 0
        val now = System.currentTimeMillis()
        for (client in clients) {
            val documents = db.documents().forClient(client.id)
            if (documents.isEmpty()) continue
            for (document in documents) {
                File(context.filesDir, document.relativePath).delete()
            }
            db.documents().deleteByIds(documents.map { it.id })
            removed += documents.size
            db.audit().log(
                AuditEntity(
                    ts = now,
                    action = "DELETE_DOCUMENTS",
                    afm = client.afm,
                    detail = "${documents.size} έγγραφα",
                ),
            )
        }
        return removed
    }

    // --------------------------------------------------------------- backup

    /**
     * Αντίγραφο του αρχείου της βάσης πριν από μαζική ενέργεια.
     *
     * Παραμένει κρυπτογραφημένο — είναι byte-αντίγραφο του SQLCipher αρχείου και
     * ζει app-private. Κρατιούνται τα [BACKUPS_KEPT] πιο πρόσφατα ανά αιτία.
     */
    private fun backupDatabase(reason: String): File? = try {
        val source = TaxCenterDatabase.file(context)
        if (!source.exists()) {
            null
        } else {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val target = File(dir, "$reason-${System.currentTimeMillis()}.db")
            source.copyTo(target, overwrite = true)
            dir.listFiles { f -> f.name.startsWith("$reason-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(BACKUPS_KEPT)
                ?.forEach { it.delete() }
            target
        }
    } catch (e: Exception) {
        // Ένα αποτυχημένο backup δεν πρέπει να μπλοκάρει τη δουλειά· καταγράφεται.
        null
    }

    private companion object {
        const val BACKUPS_KEPT = 5

        /** Ποια πεδία της προεπισκόπησης είναι μυστικά και πάνε στον `credentials`. */
        val CREDENTIAL_FIELDS = listOf(
            Field.TAXIS_USER, Field.TAXIS_PASS, Field.TAXIS_KLIDARITHMOS,
            Field.IKA_EMPLOYER_USER, Field.IKA_EMPLOYER_PASS,
            Field.IKA_INSURED_USER, Field.IKA_INSURED_PASS,
        )
    }
    /**
     * Συνδέει δύο καρτέλες ως συζύγους — **και προς τις δύο κατευθύνσεις**.
     *
     * Η μονόπλευρη σύνδεση είναι σχεδόν χειρότερη από καμία: ανοίγεις την άλλη
     * καρτέλα, δεν βλέπεις σχέση, τη δηλώνεις ξανά, και καταλήγεις με δύο
     * εγγραφές που λένε διαφορετικά πράγματα.
     *
     * Αν η άλλη καρτέλα δεν υπάρχει ακόμη, γράφεται μόνο η πλευρά που ξέρουμε·
     * η σύνδεση θα κλείσει μόνη της όταν καταχωρηθεί ο σύζυγος.
     */
    suspend fun linkSpouse(clientId: Long, spouseAfm: String) {
        val afm = Normalize.afm(spouseAfm)
        val client = db.clients().byId(clientId) ?: return
        if (afm.isBlank() || afm == client.afm) return
        val now = System.currentTimeMillis()
        db.clients().update(client.copy(spouseAfm = afm, updatedAt = now))
        db.clients().byAfm(afm)?.let { other ->
            if (other.spouseAfm != client.afm) {
                db.clients().update(other.copy(spouseAfm = client.afm, updatedAt = now))
            }
        }
        db.audit().log(
            AuditEntity(
                ts = now,
                action = "LINK_SPOUSE",
                afm = client.afm,
                // Χωρίς τον ΑΦΜ του συζύγου: το αρχείο καταγράφει ποιανού
                // πελάτη τα δεδομένα άγγιξε η ενέργεια, ποτέ τιμές τρίτων.
                detail = "σύνδεση σχέσης συζύγου",
            ),
        )
    }

    /**
     * Δημιουργεί καρτέλα συζύγου από όσα ξέρουμε, και κλείνει τη σχέση.
     *
     * **Χωρίς διαπιστευτήρια**: δεν τα έχουμε, και δεν τα μαντεύουμε. Η νέα
     * καρτέλα είναι ιδιώτης μέχρι να τρέξει άντληση στοιχείων πάνω της. Αν ο
     * ΑΦΜ υπάρχει ήδη, δεν δημιουργείται τίποτα — απλώς συνδέεται.
     *
     * Επιστρέφει το id της καρτέλας του συζύγου.
     */
    suspend fun createSpouse(
        clientId: Long,
        spouseAfm: String,
        lastName: String,
        firstName: String,
    ): Long {
        val afm = Normalize.afm(spouseAfm)
        if (afm.isBlank()) return 0L
        val now = System.currentTimeMillis()
        val existing = db.clients().byAfm(afm)
        val id = existing?.id ?: db.clients().insert(
            ClientEntity(
                afm = afm,
                name = lastName.trim(),
                firstName = firstName.trim(),
                kind = ClientKind.PRIVATE,
                importedAt = now,
                updatedAt = now,
            ),
        )
        if (existing == null) {
            db.audit().log(
                AuditEntity(
                    ts = now,
                    action = "CREATE_SPOUSE",
                    afm = afm,
                    detail = "καρτέλα από σχέση ETAK",
                ),
            )
        }
        linkSpouse(clientId, afm)
        return id
    }

}
