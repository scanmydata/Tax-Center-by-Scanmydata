package gr.scanmydata.taxcenter.mail

import android.content.Context
import gr.scanmydata.taxcenter.data.ClientRepository
import gr.scanmydata.taxcenter.data.ColumnAliases.Field
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import kotlinx.coroutines.delay
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Αποστολές προς πελάτες, με καταγραφή.
 *
 * Κάθε αποστολή — επιτυχής ή όχι — γράφεται στον πίνακα `sends`, που τροφοδοτεί
 * το ημερολόγιο αποστολών, και αφήνει ίχνος στο `audit_log`.
 *
 * **Ένα email ανά πελάτη, πάντα.** Καμία μαζική αποστολή δεν βάζει δεύτερο
 * παραλήπτη: τα φορολογικά στοιχεία ενός πελάτη δεν επιτρέπεται να φανούν σε
 * άλλον, ούτε καν ως διεύθυνση σε πεδίο «Προς».
 */
class MailService(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val repository: ClientRepository,
    private val settings: Settings = Settings(context),
    private val gmail: GmailSender = GmailSender(),
) {

    class NoRecipient(afm: String) : Exception("Ο πελάτης $afm δεν έχει διεύθυνση email.")

    // --------------------------------------------------- στοιχεία πελάτη

    /**
     * Στέλνει στον πελάτη **τα δικά του** στοιχεία: ΑΦΜ, ΑΜΚΑ, όνομα χρήστη
     * TAXISnet και — μόνο αν έχει ενεργοποιηθεί ρητά — κωδικό και κλειδάριθμο.
     *
     * Το email **δεν είναι ασφαλές κανάλι**: περνά από servers τρίτων και μένει
     * σε γραμματοκιβώτια για χρόνια. Γι' αυτό οι κωδικοί είναι εκτός εξ ορισμού
     * ([Settings.includePasswordsInClientEmail]), το μήνυμα προειδοποιεί τον
     * παραλήπτη, και το τι στάλθηκε καταγράφεται.
     */
    suspend fun sendOwnDetails(
        accessToken: String,
        client: ClientEntity,
        includeSecrets: Boolean = settings.includePasswordsInClientEmail,
    ): SendEntity {
        val to = client.effectiveEmail
        if (to.isBlank()) throw NoRecipient(client.afm)

        val credentials = repository.credentials(client.id)
        val amka = repository.amka(client)
        val body = MailTemplates.ownDetails(
            client = client,
            amka = amka,
            taxisUser = credentials[Field.TAXIS_USER].orEmpty(),
            taxisPass = credentials[Field.TAXIS_PASS].orEmpty(),
            klidarithmos = credentials[Field.TAXIS_KLIDARITHMOS].orEmpty(),
            includeSecrets = includeSecrets,
            officeName = settings.officeName,
            signature = settings.signatureFor(SendEntity.KIND_CREDENTIALS),
        )

        val items = buildList {
            add("ΑΦΜ")
            if (amka.isNotBlank()) add("ΑΜΚΑ")
            if (credentials[Field.TAXIS_USER].orEmpty().isNotBlank()) add("Όνομα χρήστη TAXISnet")
            if (includeSecrets) {
                if (credentials[Field.TAXIS_PASS].orEmpty().isNotBlank()) add("Συνθηματικό TAXISnet")
                if (credentials[Field.TAXIS_KLIDARITHMOS].orEmpty().isNotBlank()) add("Κλειδάριθμος")
            }
        }

        return deliver(
            accessToken = accessToken,
            client = client,
            to = to,
            subject = body.subject,
            text = body.text,
            html = body.html,
            attachments = emptyList(),
            kind = SendEntity.KIND_CREDENTIALS,
            items = items,
            auditDetail = if (includeSecrets) {
                "στοιχεία πελάτη ΜΕ κωδικούς"
            } else {
                "στοιχεία πελάτη χωρίς κωδικούς"
            },
        )
    }

    // ------------------------------------------------------------- έντυπα

    /** Στέλνει φορολογικά έντυπα ως συνημμένα. */
    suspend fun sendDocuments(
        accessToken: String,
        client: ClientEntity,
        documents: List<DocumentEntity>,
        note: String = "",
    ): SendEntity {
        val to = client.effectiveEmail
        if (to.isBlank()) throw NoRecipient(client.afm)

        val files = documents.mapNotNull { doc ->
            File(context.filesDir, doc.relativePath).takeIf { it.isFile }
        }
        val attachments = bundleIfLarge(client, files)
        val body = MailTemplates.documents(
            client = client,
            fileNames = documents.map { it.fileName },
            note = note,
            officeName = settings.officeName,
            signature = settings.signatureFor(SendEntity.KIND_DOCUMENTS),
        )

        val send = deliver(
            accessToken = accessToken,
            client = client,
            to = to,
            subject = body.subject,
            text = body.text,
            html = body.html,
            attachments = attachments,
            kind = SendEntity.KIND_DOCUMENTS,
            items = documents.map { it.fileName },
            auditDetail = "${documents.size} έντυπα",
        )

        if (!send.failed) {
            db.documents().markSent(documents.map { it.id }, send.sentAt)
        }
        return send
    }

    // ------------------------------------------------------------ κοινό

    /**
     * Το μοναδικό σημείο που στέλνει και καταγράφει.
     *
     * Οι αποτυχίες **δεν πετιούνται**: επιστρέφονται ως εγγραφή με
     * `status = FAILED`, ώστε μια μαζική αποστολή σε 40 πελάτες να συνεχίσει και
     * ο λογιστής να δει στο ημερολόγιο ποιες δεν έφτασαν.
     */
    private suspend fun deliver(
        accessToken: String,
        client: ClientEntity,
        to: String,
        subject: String,
        text: String,
        html: String,
        attachments: List<GmailSender.Attachment>,
        kind: String,
        items: List<String>,
        auditDetail: String,
    ): SendEntity {
        throttle()

        val now = System.currentTimeMillis()
        var status = SendEntity.STATUS_SENT
        var error = ""

        try {
            sendWithRetry(accessToken, to, subject, text, html, attachments)
        } catch (e: Exception) {
            status = SendEntity.STATUS_FAILED
            error = e.message ?: e.toString()
        }

        val entry = SendEntity(
            clientId = client.id,
            afm = client.afm,
            clientName = client.displayName,
            toEmail = to,
            subject = subject,
            kind = kind,
            items = items.joinToString("\n"),
            itemCount = items.size,
            sentAt = now,
            status = status,
            error = error,
        )
        val id = db.sends().log(entry)

        db.audit().log(
            AuditEntity(
                ts = now,
                action = if (status == SendEntity.STATUS_SENT) "SEND" else "SEND_FAILED",
                afm = client.afm,
                detail = "$auditDetail -> $to" + if (error.isNotBlank()) " ($error)" else "",
            ),
        )
        return entry.copy(id = id)
    }

    /**
     * Πολλά PDF μαζί γίνονται ένα ZIP.
     *
     * Το Gmail κόβει στα ~25 MB ανά μήνυμα, και το MIME base64 φουσκώνει τα
     * δεδομένα κατά ~33% — άρα το πραγματικό όριο είναι κοντά στα 18 MB
     * αρχείων. Ένα ZIP με δέκα εκκαθαριστικά περνά άνετα, ενώ δέκα ξεχωριστά
     * συνημμένα μπορεί να μην περάσουν.
     *
     * Κάτω από το όριο μένουν ξεχωριστά: ο πελάτης τα ανοίγει με ένα πάτημα
     * από το κινητό του, χωρίς να ψάχνει πρόγραμμα αποσυμπίεσης.
     */
    private fun bundleIfLarge(
        client: ClientEntity,
        files: List<File>,
    ): List<GmailSender.Attachment> {
        val total = files.sumOf { it.length() }
        if (files.size <= 1 || total < ZIP_THRESHOLD_BYTES) {
            return files.map { GmailSender.Attachment.of(it) }
        }
        val zip = File(context.cacheDir, "outbox").apply { mkdirs() }
            .resolve("Έντυπα_${client.afm}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (file in files) {
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val attachment = GmailSender.Attachment.of(zip, mimeType = "application/zip")
        // Η cache δεν είναι θέση για φορολογικά έντυπα ούτε λεπτό παραπάνω.
        zip.delete()
        return listOf(attachment)
    }

    // --------------------------------------------------- ρυθμός & επανάληψη

    private var lastSentAt = 0L

    /**
     * Ελάχιστο κενό ανάμεσα σε δύο αποστολές.
     *
     * Το Gmail API έχει όριο ανά χρήστη και ανά δευτερόλεπτο, και μια μαζική
     * αποστολή σε 40 πελάτες το χτυπά εύκολα. Το κενό είναι φθηνότερο από το να
     * φάει η παρτίδα σαράντα 429 και να ξαναπροσπαθεί.
     */
    private suspend fun throttle() {
        val since = System.currentTimeMillis() - lastSentAt
        if (lastSentAt != 0L && since < MIN_GAP_MS) {
            delay(MIN_GAP_MS - since)
        }
        lastSentAt = System.currentTimeMillis()
    }

    /**
     * Επανάληψη **μόνο** σε παροδικές αποτυχίες, με εκθετική αναμονή.
     *
     * Ένα 400 «λάθος διεύθυνση» δεν γίνεται σωστό με επανάληψη — και το να
     * ξαναστείλει η εφαρμογή το ίδιο μήνυμα τρεις φορές σε λάθος παραλήπτη
     * είναι χειρότερο από την αποτυχία.
     */
    private suspend fun sendWithRetry(
        accessToken: String,
        to: String,
        subject: String,
        text: String,
        html: String,
        attachments: List<GmailSender.Attachment>,
    ) {
        var attempt = 0
        while (true) {
            try {
                gmail.send(accessToken, to, subject, text, html, attachments)
                return
            } catch (e: GmailSender.SendFailed) {
                attempt++
                if (!e.transient || attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
            }
        }
    }

    private companion object {
        const val ZIP_THRESHOLD_BYTES = 8L * 1024 * 1024
        const val MIN_GAP_MS = 1_200L
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_MS = 2_000L
    }
}
