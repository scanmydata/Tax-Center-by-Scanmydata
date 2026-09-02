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
import java.io.File

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
            signature = settings.signature,
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

        val attachments = documents.mapNotNull { doc ->
            val file = File(context.filesDir, doc.relativePath)
            if (file.isFile) GmailSender.Attachment.of(file) else null
        }
        val body = MailTemplates.documents(
            client = client,
            fileNames = documents.map { it.fileName },
            note = note,
            officeName = settings.officeName,
            signature = settings.signature,
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
        val now = System.currentTimeMillis()
        var status = SendEntity.STATUS_SENT
        var error = ""

        try {
            gmail.send(accessToken, to, subject, text, html, attachments)
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
}
