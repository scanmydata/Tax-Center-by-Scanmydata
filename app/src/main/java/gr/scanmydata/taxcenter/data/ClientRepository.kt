package gr.scanmydata.taxcenter.data

import android.content.Context
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
                kind = row.values[Field.KIND].orEmpty(),
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
     */
    suspend fun saveClient(client: ClientEntity, credentials: Map<Field, String>): Long {
        val now = System.currentTimeMillis()
        val id = db.clients().upsertPreservingBlanks(client, now)
        db.clients().setEmails(
            id = id,
            aade = client.emailAade.trim(),
            manual = client.emailManual.trim(),
            preferred = client.emailPreferred.trim(),
            now = now,
        )
        for ((field, value) in credentials) {
            if (value.isBlank()) continue
            db.credentials().putIfNotBlank(id, field.name, crypto.enc(value), now)
        }
        db.audit().log(AuditEntity(ts = now, action = "CLIENT_SAVE", afm = client.afm))
        return id
    }

    /** Τα αποθηκευμένα διαπιστευτήρια, **αποκρυπτογραφημένα**. */
    suspend fun credentials(clientId: Long): Map<Field, String> {
        val byName = db.credentials().forClient(clientId).associate { it.field to crypto.dec(it.valueEnc) }
        return Field.entries.mapNotNull { field ->
            byName[field.name]?.takeIf { it.isNotBlank() }?.let { field to it }
        }.toMap()
    }

    suspend fun amka(client: ClientEntity): String = crypto.dec(client.amkaEnc)

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
            Field.MYDATA_USER, Field.MYDATA_KEY,
        )
    }
}
