package gr.scanmydata.taxcenter.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import gr.scanmydata.taxcenter.data.Normalize
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase

/**
 * Μήνυμα οφειλής με **SMS**, μέσα από την εφαρμογή μηνυμάτων της συσκευής.
 *
 * ## Γιατί δεν στέλνει η ίδια η εφαρμογή
 *
 * Θα μπορούσε, με το δικαίωμα `SEND_SMS`. Δεν το ζητάμε, για τρεις λόγους:
 *
 *  * Είναι από τα πιο επικίνδυνα δικαιώματα του Android — μια εφαρμογή που
 *    κρατά κωδικούς TAXISnet δεν έχει λόγο να μπορεί να στέλνει μηνύματα χωρίς
 *    να το δει κανείς.
 *  * Το SMS **χρεώνεται** στο συμβόλαιο του γραφείου, και ένα μήνυμα οφειλής με
 *    ελληνικά είναι τρία-τέσσερα τμήματα. Το τελευταίο πάτημα πρέπει να είναι
 *    ανθρώπου.
 *  * Λάθος αριθμός σημαίνει ότι ένας άγνωστος μαθαίνει τι χρωστά ο πελάτης.
 *    Στην εφαρμογή μηνυμάτων ο λογιστής βλέπει τον παραλήπτη πριν φύγει.
 *
 * Άρα η εφαρμογή ανοίγει τα Μηνύματα με τον αριθμό και το κείμενο **έτοιμα**,
 * και ο χρήστης πατά αποστολή. Σε αντίθεση με το Viber, εδώ ο παραλήπτης
 * προσυμπληρώνεται κανονικά.
 *
 * Όπως και στο Viber, δεν ξέρουμε αν το μήνυμα έφυγε: η εγγραφή στο ημερολόγιο
 * είναι [SendEntity.STATUS_HANDED], «παραδόθηκε», ποτέ «στάλθηκε».
 */
class SmsSender(
    private val context: Context,
    private val db: TaxCenterDatabase,
) {

    /**
     * @param to κινητό, σε όποια μορφή — κανονικοποιείται εδώ και απορρίπτεται
     *   ό,τι δεν είναι ελληνικό κινητό.
     * @param items τι αφορούσε το μήνυμα, μία γραμμή ανά οφειλή — για το ημερολόγιο.
     */
    suspend fun send(
        client: ClientEntity,
        to: String,
        text: String,
        subject: String,
        items: List<String>,
    ): SendEntity {
        val now = System.currentTimeMillis()
        val mobile = Normalize.mobile(to)
        var status = SendEntity.STATUS_HANDED
        var error = ""
        try {
            if (mobile.isBlank()) throw IllegalArgumentException("Ο αριθμός δεν είναι έγκυρο ελληνικό κινητό.")
            context.startActivity(
                // `smsto:` και όχι `sms:`: το πρώτο δέχεται μόνο εφαρμογές
                // μηνυμάτων, οπότε το κείμενο δεν καταλήγει σε ό,τι τυχαίνει να
                // δηλώνει ότι χειρίζεται συνδέσμους.
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(Normalize.mobileE164(mobile))))
                    .putExtra("sms_body", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            status = SendEntity.STATUS_FAILED
            error = "Η συσκευή δεν έχει εφαρμογή μηνυμάτων SMS."
        } catch (e: Exception) {
            status = SendEntity.STATUS_FAILED
            error = e.message ?: e.toString()
        }

        val entry = SendEntity(
            clientId = client.id,
            afm = client.afm,
            clientName = client.displayName,
            toEmail = mobile,
            subject = subject,
            kind = SendEntity.KIND_SMS_DEBTS,
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
                action = if (status == SendEntity.STATUS_FAILED) "SMS_FAILED" else "SMS_HANDED",
                afm = client.afm,
                // Πλήθος και κανάλι — ποτέ ποσά ή κωδικοί πληρωμής στο αρχείο ενεργειών.
                detail = "μήνυμα για ${items.size} οφειλές -> SMS $mobile" +
                    if (error.isNotBlank()) " ($error)" else "",
            ),
        )
        return entry.copy(id = id)
    }
}
