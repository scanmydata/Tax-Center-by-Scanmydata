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

    /**
     * Ένα συνημμένο, όπως γράφεται στο μήνυμα.
     *
     * @param title η γραμμή που το ονομάζει
     * @param details γραμμές κάτω από τον τίτλο — για τις οφειλές: υπόλοιπο,
     *   δόση, λήξη, ταυτότητα πληρωμής
     * @param group επικεφαλίδα κάτω από την οποία μπαίνει· κενό = καμία. Τα
     *   στοιχεία της ίδιας ομάδας πρέπει να έρχονται συνεχόμενα.
     */
    data class Item(
        val title: String,
        val details: List<String> = emptyList(),
        val group: String = "",
    )

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

    /**
     * Συνοδευτικό κείμενο για φορολογικά έντυπα.
     *
     * Η λίστα χωρίζεται σε ομάδες όπου τα [items] δηλώνουν ομάδα (σήμερα: οι
     * οφειλές ανά πύλη — βλ. [DocumentLines]). Χωρίς ομάδες βγαίνει η απλή
     * λίστα, όπως πάντα.
     */
    fun documents(
        client: ClientEntity,
        items: List<Item>,
        note: String,
        officeName: String,
        signature: String,
        template: Template = MailTemplateStore.DEFAULT_DOCUMENTS,
    ): Body {
        var subject = fill(template.subject, client, items.size)
        if (!template.has(DocumentField.AFM_IN_SUBJECT)) {
            subject = subject.replace(client.afm, "").trim().trimEnd('—', '-', '·', ' ')
        }
        if (!template.has(DocumentField.COUNT)) {
            subject = subject.replace("(${items.size})", "").replace("  ", " ").trim()
        }

        val intro = fill(template.intro, client, items.size)
        val closing = fill(template.closing, client, items.size)
        val showList = template.has(DocumentField.FILE_LIST)
        val showNote = template.has(DocumentField.NOTE) && note.isNotBlank()

        val text = buildString {
            append(intro).append("\n\n")
            if (showList) {
                var group = ""
                items.forEachIndexed { index, item ->
                    if (item.group != group) {
                        group = item.group
                        if (index > 0 && !endsWith("\n\n")) append("\n")
                        if (group.isNotBlank()) append(group).append("\n\n")
                    }
                    append("  • ").append(item.title).append("\n")
                    item.details.forEach { append("    ").append(it).append("\n") }
                    // Μια οφειλή με τέσσερις γραμμές από κάτω θέλει αέρα πριν
                    // την επόμενη· μια σκέτη γραμμή όχι.
                    if (item.details.size > 1) append("\n")
                }
                if (!endsWith("\n\n")) append("\n")
            }
            if (showNote) append(note).append("\n\n")
            if (closing.isNotBlank()) append(closing)
            append(footer(officeName, signature))
        }

        val html = buildString {
            append("<div style=\"font-family:system-ui,Arial,sans-serif;font-size:14px;color:#0B1B2B\">")
            append(htmlBlock(esc(intro)))
            if (showList) {
                var group = ""
                var open = false
                items.forEach { item ->
                    if (!open || item.group != group) {
                        if (open) append("</ul>")
                        group = item.group
                        if (group.isNotBlank()) {
                            append("<p style=\"margin:16px 0 4px 0;font-weight:700\">")
                            append(esc(group)).append("</p>")
                        }
                        append("<ul style=\"margin-top:4px\">")
                        open = true
                    }
                    append("<li style=\"margin-bottom:8px\">")
                    if (item.details.isEmpty()) {
                        append(esc(item.title))
                    } else {
                        append("<span style=\"font-weight:600\">").append(esc(item.title)).append("</span>")
                        item.details.forEach {
                            append("<br><span style=\"color:#41546b\">").append(esc(it)).append("</span>")
                        }
                    }
                    append("</li>")
                }
                if (open) append("</ul>")
            }
            if (showNote) append(htmlBlock(esc(note)))
            append(htmlBlock(esc(closing)))
            append(htmlBlock(esc(footer(officeName, signature))))
            append("</div>")
        }

        return Body(subject, text, html)
    }
}
