package gr.scanmydata.taxcenter.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import gr.scanmydata.taxcenter.data.Normalize
import gr.scanmydata.taxcenter.data.Settings
import gr.scanmydata.taxcenter.data.db.AuditEntity
import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.data.db.SendEntity
import gr.scanmydata.taxcenter.data.db.TaxCenterDatabase
import gr.scanmydata.taxcenter.engine.DocumentNaming
import java.io.File

/**
 * Αποστολή εντύπων μέσω **Viber**, με την εφαρμογή Viber της ίδιας συσκευής.
 *
 * ## Γιατί έτσι, και όχι με API
 *
 * Το Viber δεν έχει δρόμο να στείλει αρχείο σε αριθμό τηλεφώνου προγραμματικά:
 *
 *  * Το **Bot API** (`chatapi.viber.com`) στέλνει μόνο σε χρήστες που έχουν
 *    πρώτα κάνει εγγραφή στο bot. Ένας πελάτης λογιστικού γραφείου δεν πρόκειται.
 *  * Τα **Viber Business Messages** στέλνουν όντως σε αριθμό, αλλά μόνο μέσω
 *    εξουσιοδοτημένου παρόχου (Infobip, Vonage, Sinch…), με έλεγχο νομιμότητας
 *    της επιχείρησης, χρέωση ανά μήνυμα — και, το κρίσιμο, **δεν στέλνουν
 *    αρχεία** προς αριθμό: μόνο κείμενο και εικόνα. Δηλαδή δεν στέλνουν PDF.
 *
 * Άρα ο μόνος δρόμος που παραδίδει πράγματι τα έντυπα είναι η εγκατεστημένη
 * εφαρμογή: της δίνουμε το κείμενο και τα αρχεία, και ο χρήστης διαλέγει την
 * επαφή και πατά αποστολή.
 *
 * ## Τι σημαίνει αυτό στην πράξη
 *
 * **Δεν υπάρχει μαζική αποστολή με Viber.** Κάθε πελάτης θέλει δύο πατήματα
 * ανθρώπου. Αυτό δεν είναι παράλειψη υλοποίησης· είναι το όριο του καναλιού,
 * και γι' αυτό η μαζική αποστολή μένει αποκλειστικά στο email.
 *
 * **Δεν ξέρουμε αν έφτασε.** Παραδίδουμε στο Viber και χάνουμε το νήμα. Η
 * εγγραφή στο ημερολόγιο γράφεται ως [SendEntity.STATUS_HANDED] και όχι ως
 * «στάλθηκε»: το ημερολόγιο απαντά στο «το έστειλα;» και δεν επιτρέπεται να
 * απαντά ψέματα.
 *
 * ## Προστασία δεδομένων
 *
 * Τα αρχεία δίνονται με `FileProvider` και **προσωρινό** δικαίωμα ανάγνωσης,
 * όπως και στο άνοιγμα PDF. Το Viber είναι τρίτος αποδέκτης: ό,τι περνά από
 * εκεί φεύγει από τον έλεγχο του γραφείου, και ο πελάτης πρέπει να το έχει
 * ζητήσει — γι' αυτό το κανάλι επιλέγεται ρητά ανά αποστολή και ποτέ αυτόματα.
 */
class ViberSender(
    private val context: Context,
    private val db: TaxCenterDatabase,
    private val settings: Settings = Settings(context),
    private val templates: MailTemplateStore = MailTemplateStore(context),
) {

    class NoMobile(afm: String) : Exception("Ο πελάτης $afm δεν έχει καταχωρημένο κινητό.")
    class NotInstalled : Exception("Το Viber δεν είναι εγκατεστημένο σε αυτή τη συσκευή.")

    /** Το κείμενο και τα αρχεία, πριν φύγουν — για την οθόνη επιβεβαίωσης. */
    data class Draft(
        val to: String,
        val text: String,
        val subject: String,
        val documents: List<DocumentEntity>,
        val missing: List<String>,
    )

    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        // Χωρίς τη δήλωση `<queries>` το σύστημα κρύβει το πακέτο· καλύτερα να
        // πούμε «δεν υπάρχει» παρά να σκάσει η οθόνη.
        false
    }

    /**
     * Χτίζει το μήνυμα — **ίδιο κείμενο με το email**, εκτός αν ο χρήστης έχει
     * ορίσει χωριστό πρότυπο στις Ρυθμίσεις.
     *
     * Το θέμα δεν μπαίνει στο κείμενο: το Viber δεν έχει θέμα, και μια γραμμή
     * «Φορολογικά έντυπα (3) — ΑΦΜ …» στην κορυφή ενός chat είναι θόρυβος.
     * Κρατιέται μόνο για την εγγραφή στο ημερολόγιο.
     */
    fun draft(
        client: ClientEntity,
        documents: List<DocumentEntity>,
        note: String = "",
        overrideTo: String = "",
    ): Draft {
        val to = Normalize.mobile(overrideTo).ifBlank { Normalize.mobile(client.mobile) }
        if (to.isBlank()) throw NoMobile(client.afm)

        val body = MailTemplates.documents(
            client = client,
            fileNames = documents.map { DocumentNaming.line(it) },
            note = note,
            officeName = settings.officeName,
            signature = settings.signatureFor(SendEntity.KIND_DOCUMENTS),
            template = templates.viberEffective,
        )

        val missing = documents.filterNot { fileOf(it).isFile }.map { it.fileName }
        return Draft(
            to = to,
            text = body.text,
            subject = body.subject,
            documents = documents.filter { fileOf(it).isFile },
            missing = missing,
        )
    }

    /**
     * Παραδίδει το μήνυμα στο Viber και καταγράφει την προσπάθεια.
     *
     * Επιστρέφει την εγγραφή του ημερολογίου. Σε αποτυχία εκκίνησης η εγγραφή
     * είναι `FAILED` — δεν πετάμε εξαίρεση, για τον ίδιο λόγο με το email: η
     * αποτυχία πρέπει να **φαίνεται** στο ιστορικό, όχι να εξαφανίζεται σε ένα
     * μήνυμα οθόνης που ο χρήστης θα κλείσει.
     */
    suspend fun send(client: ClientEntity, draft: Draft): SendEntity {
        val now = System.currentTimeMillis()
        var status = SendEntity.STATUS_HANDED
        var error = ""

        try {
            if (!installed()) throw NotInstalled()
            context.startActivity(shareIntent(draft))
        } catch (e: ActivityNotFoundException) {
            status = SendEntity.STATUS_FAILED
            error = "Το Viber δεν δέχτηκε τα αρχεία."
        } catch (e: Exception) {
            status = SendEntity.STATUS_FAILED
            error = e.message ?: e.toString()
        }

        val entry = SendEntity(
            clientId = client.id,
            afm = client.afm,
            clientName = client.displayName,
            toEmail = draft.to,
            subject = draft.subject,
            kind = SendEntity.KIND_VIBER_DOCUMENTS,
            items = draft.documents.map { it.fileName }.joinToString("\n"),
            itemCount = draft.documents.size,
            sentAt = now,
            status = status,
            error = error,
        )
        val id = db.sends().log(entry)

        db.audit().log(
            AuditEntity(
                ts = now,
                action = if (status == SendEntity.STATUS_FAILED) "VIBER_FAILED" else "VIBER_HANDED",
                afm = client.afm,
                detail = "${draft.documents.size} έντυπα -> Viber ${draft.to}" +
                    if (error.isNotBlank()) " ($error)" else "",
            ),
        )
        return entry.copy(id = id)
    }

    /**
     * Ανοίγει τη συνομιλία με **αυτόν** τον αριθμό, χωρίς να στείλει τίποτα.
     *
     * Χρησιμεύει για επαλήθευση πριν την αποστολή: ο διάλογος κοινοποίησης του
     * Viber δείχνει επαφές με ονόματα, και ο λογιστής θέλει να βεβαιωθεί ότι το
     * όνομα που θα διαλέξει αντιστοιχεί στο νούμερο της καρτέλας.
     *
     * Επιστρέφει μήνυμα σφάλματος, ή κενό όταν άνοιξε.
     */
    fun openChat(mobile: String): String {
        val e164 = Normalize.mobileE164(mobile)
        if (e164.isBlank()) return "Ο αριθμός δεν είναι έγκυρο ελληνικό κινητό."
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("viber://chat?number=" + Uri.encode(e164)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ""
        } catch (e: ActivityNotFoundException) {
            "Το Viber δεν είναι εγκατεστημένο σε αυτή τη συσκευή."
        } catch (e: Exception) {
            "Δεν άνοιξε το Viber: ${e.message}"
        }
    }

    // ------------------------------------------------------------ εσωτερικά

    private fun fileOf(document: DocumentEntity) = File(context.filesDir, document.relativePath)

    /**
     * Ένα ή πολλά αρχεία, μαζί με το κείμενο, προς το Viber και **μόνο**.
     *
     * Το `setPackage` δεν είναι διακοσμητικό: χωρίς αυτό εμφανίζεται ο κοινός
     * διάλογος «κοινή χρήση με…» της συσκευής, όπου φορολογικά έντυπα πελάτη
     * μπορούν να φύγουν με ένα άστοχο πάτημα σε οποιαδήποτε εφαρμογή — cloud,
     * σημειώσεις, κοινωνικό δίκτυο.
     */
    private fun shareIntent(draft: Draft): Intent {
        val uris = ArrayList<Uri>(
            draft.documents.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fileOf(it))
            },
        )

        val action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        return Intent(action).apply {
            setPackage(PACKAGE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TEXT, draft.text)
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            } else if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                // Μήνυμα χωρίς συνημμένα: το `application/pdf` θα έκρυβε το
                // Viber από τους αποδέκτες κειμένου.
                type = "text/plain"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        const val PACKAGE = "com.viber.voip"
    }
}
