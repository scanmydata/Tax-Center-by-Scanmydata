package gr.scanmydata.taxcenter.mail

import gr.scanmydata.taxcenter.data.db.ClientEntity
import gr.scanmydata.taxcenter.mail.MailTemplateStore.CredentialField
import gr.scanmydata.taxcenter.mail.MailTemplateStore.DocumentField
import gr.scanmydata.taxcenter.mail.MailTemplateStore.Template

/**
 * Τα κείμενα των email, σε απλά ελληνικά.
 *
 * Κάθε μήνυμα φεύγει σε δύο μορφές: σκέτο κείμενο και HTML. Το σκέτο δεν είναι
 * τυπικότητα — πολλοί πελάτες διαβάζουν σε clients που μπλοκάρουν HTML, και ένα
 * email με τα στοιχεία τους δεν πρέπει να φτάσει άδειο.
 *
 * Το θέμα, το εισαγωγικό και το καταληκτικό κείμενο, καθώς και **ποια πεδία**
 * μπαίνουν, έρχονται από το [MailTemplateStore] και τα ορίζει ο χρήστης. Η
 * δομή (πίνακας στοιχείων, λίστα αρχείων, προειδοποίηση ασφαλείας) μένει εδώ:
 * είναι το κομμάτι που πρέπει να είναι σωστό ανεξάρτητα από τι έγραψε κάποιος
 * στις ρυθμίσεις.
 */
object MailTemplates {

    data class Body(val subject: String, val text: String, val html: String)

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Αντικαθιστά τα placeholders του χρήστη. Άγνωστα μένουν ως έχουν. */
    private fun fill(
        raw: String,
        client: ClientEntity,
        count: Int = 0,
    ): String = raw
        .replace(MailTemplateStore.PLACEHOLDER_NAME, client.displayName)
        .replace(MailTemplateStore.PLACEHOLDER_AFM, client.afm)
        .replace(MailTemplateStore.PLACEHOLDER_COUNT, count.toString())

    private fun footer(officeName: String, signature: String): String = buildString {
        if (signature.isNotBlank()) {
            append("\n\n")
            append(signature)
        } else if (officeName.isNotBlank()) {
            append("\n\nΜε εκτίμηση,\n")
            append(officeName)
        }
    }

    private fun htmlBlock(text: String): String =
        text.trim().replace("\n", "<br>").let { if (it.isBlank()) "" else "<p>$it</p>" }

    /**
     * Τα προσωπικά στοιχεία του πελάτη.
     *
     * Όταν [includeSecrets] είναι true, το μήνυμα κουβαλά κωδικό και
     * κλειδάριθμο — και μαζί μια ρητή προειδοποίηση. Το email μένει σε
     * γραμματοκιβώτια για χρόνια και συχνά συγχρονίζεται σε συσκευές που ο
     * πελάτης δεν ελέγχει· του το λέμε, αντί να το υποθέσουμε.
     *
     * Ο διακόπτης [includeSecrets] και το πρότυπο είναι **και τα δύο**
     * απαραίτητα: ένα πεδίο φεύγει μόνο αν το θέλει το πρότυπο *και* το
     * επιτρέπει η συγκεκριμένη αποστολή. Δύο κλειδαριές στην ίδια πόρτα, με τη
     * μία να ανοίγει μόνο για μία αποστολή τη φορά.
     */
    fun ownDetails(
        client: ClientEntity,
        amka: String,
        taxisUser: String,
        taxisPass: String,
        klidarithmos: String,
        includeSecrets: Boolean,
        officeName: String,
        signature: String,
        template: Template = MailTemplateStore.DEFAULT_CREDENTIALS,
    ): Body {
        val subject = fill(template.subject, client)

        val rows = buildList {
            if (template.has(CredentialField.AFM)) add("ΑΦΜ" to client.afm)
            if (template.has(CredentialField.AMKA) && amka.isNotBlank()) add("ΑΜΚΑ" to amka)
            if (template.has(CredentialField.DOY) && client.doy.isNotBlank()) {
                add("ΔΟΥ" to client.doy)
            }
            if (template.has(CredentialField.TAXIS_USER) && taxisUser.isNotBlank()) {
                add("Όνομα χρήστη TAXISnet" to taxisUser)
            }
            if (includeSecrets) {
                if (template.has(CredentialField.TAXIS_PASS) && taxisPass.isNotBlank()) {
                    add("Συνθηματικό TAXISnet" to taxisPass)
                }
                if (template.has(CredentialField.KLIDARITHMOS) && klidarithmos.isNotBlank()) {
                    add("Κλειδάριθμος" to klidarithmos)
                }
            }
        }

        val sendsSecrets = includeSecrets && rows.any {
            it.first == "Συνθηματικό TAXISnet" || it.first == "Κλειδάριθμος"
        }
        val warning = if (sendsSecrets) {
            "Το μήνυμα αυτό περιέχει κωδικούς πρόσβασης. Το email δεν είναι " +
                "ασφαλές κανάλι: παρακαλούμε αποθηκεύστε τα στοιχεία σε ασφαλές " +
                "σημείο και διαγράψτε το μήνυμα. Αν υποψιάζεστε ότι κάποιος άλλος " +
                "έχει πρόσβαση στο γραμματοκιβώτιό σας, αλλάξτε τον κωδικό σας στο " +
                "TAXISnet."
        } else {
            "Για λόγους ασφαλείας δεν αποστέλλονται κωδικοί μέσω email. Αν τους " +
                "χρειάζεστε, επικοινωνήστε μαζί μας."
        }

        val intro = fill(template.intro, client)
        val closing = fill(template.closing, client)

        val text = buildString {
            append(intro).append("\n\n")
            rows.forEach { (label, value) -> append("  $label: $value\n") }
            append("\n")
            append(warning)
            if (closing.isNotBlank()) append("\n\n").append(closing)
            append(footer(officeName, signature))
        }

        val html = buildString {
            append("<div style=\"font-family:system-ui,Arial,sans-serif;font-size:14px;color:#0B1B2B\">")
            append(htmlBlock(esc(intro)))
            append("<table style=\"border-collapse:collapse\">")
            rows.forEach { (label, value) ->
                append("<tr>")
                append("<td style=\"padding:4px 12px 4px 0;color:#41546b\">").append(esc(label)).append("</td>")
                append("<td style=\"padding:4px 0;font-weight:600\">").append(esc(value)).append("</td>")
                append("</tr>")
            }
            append("</table>")
            append("<p style=\"margin-top:16px;padding:12px;background:#F2F6FC;border-left:3px solid #2E7DE0\">")
            append(esc(warning)).append("</p>")
            append(htmlBlock(esc(closing)))
            append(htmlBlock(esc(footer(officeName, signature))))
            append("</div>")
        }

        return Body(subject, text, html)
    }

    /** Συνοδευτικό κείμενο για φορολογικά έντυπα. */
    fun documents(
        client: ClientEntity,
        fileNames: List<String>,
        note: String,
        officeName: String,
        signature: String,
        template: Template = MailTemplateStore.DEFAULT_DOCUMENTS,
    ): Body {
        var subject = fill(template.subject, client, fileNames.size)
        if (!template.has(DocumentField.AFM_IN_SUBJECT)) {
            subject = subject.replace(client.afm, "").trim().trimEnd('—', '-', '·', ' ')
        }
        if (!template.has(DocumentField.COUNT)) {
            subject = subject.replace("(${fileNames.size})", "").replace("  ", " ").trim()
        }

        val intro = fill(template.intro, client, fileNames.size)
        val closing = fill(template.closing, client, fileNames.size)
        val showList = template.has(DocumentField.FILE_LIST)
        val showNote = template.has(DocumentField.NOTE) && note.isNotBlank()

        val text = buildString {
            append(intro).append("\n\n")
            if (showList) {
                fileNames.forEach { append("  • $it\n") }
                append("\n")
            }
            if (showNote) append(note).append("\n\n")
            if (closing.isNotBlank()) append(closing)
            append(footer(officeName, signature))
        }

        val html = buildString {
            append("<div style=\"font-family:system-ui,Arial,sans-serif;font-size:14px;color:#0B1B2B\">")
            append(htmlBlock(esc(intro)))
            if (showList) {
                append("<ul>")
                fileNames.forEach { append("<li>").append(esc(it)).append("</li>") }
                append("</ul>")
            }
            if (showNote) append(htmlBlock(esc(note)))
            append(htmlBlock(esc(closing)))
            append(htmlBlock(esc(footer(officeName, signature))))
            append("</div>")
        }

        return Body(subject, text, html)
    }
}
