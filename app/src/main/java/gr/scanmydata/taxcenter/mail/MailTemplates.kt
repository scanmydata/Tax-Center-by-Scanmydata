package gr.scanmydata.taxcenter.mail

import gr.scanmydata.taxcenter.data.db.ClientEntity

/**
 * Τα κείμενα των email, σε απλά ελληνικά.
 *
 * Κάθε μήνυμα φεύγει σε δύο μορφές: σκέτο κείμενο και HTML. Το σκέτο δεν είναι
 * τυπικότητα — πολλοί πελάτες διαβάζουν σε clients που μπλοκάρουν HTML, και ένα
 * email με τα στοιχεία τους δεν πρέπει να φτάσει άδειο.
 */
object MailTemplates {

    data class Body(val subject: String, val text: String, val html: String)

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun footer(officeName: String, signature: String): String = buildString {
        if (signature.isNotBlank()) {
            append("\n\n")
            append(signature)
        } else if (officeName.isNotBlank()) {
            append("\n\nΜε εκτίμηση,\n")
            append(officeName)
        }
    }

    /**
     * Τα προσωπικά στοιχεία του πελάτη.
     *
     * Όταν [includeSecrets] είναι true, το μήνυμα κουβαλά κωδικό και
     * κλειδάριθμο — και μαζί μια ρητή προειδοποίηση. Το email μένει σε
     * γραμματοκιβώτια για χρόνια και συχνά συγχρονίζεται σε συσκευές που ο
     * πελάτης δεν ελέγχει· του το λέμε, αντί να το υποθέσουμε.
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
    ): Body {
        val subject = "Τα στοιχεία σας — ΑΦΜ ${client.afm}"

        val rows = buildList {
            add("ΑΦΜ" to client.afm)
            if (amka.isNotBlank()) add("ΑΜΚΑ" to amka)
            if (taxisUser.isNotBlank()) add("Όνομα χρήστη TAXISnet" to taxisUser)
            if (includeSecrets) {
                if (taxisPass.isNotBlank()) add("Συνθηματικό TAXISnet" to taxisPass)
                if (klidarithmos.isNotBlank()) add("Κλειδάριθμος" to klidarithmos)
            }
        }

        val warning = if (includeSecrets) {
            "Το μήνυμα αυτό περιέχει κωδικούς πρόσβασης. Το email δεν είναι " +
                "ασφαλές κανάλι: παρακαλούμε αποθηκεύστε τα στοιχεία σε ασφαλές " +
                "σημείο και διαγράψτε το μήνυμα. Αν υποψιάζεστε ότι κάποιος άλλος " +
                "έχει πρόσβαση στο γραμματοκιβώτιό σας, αλλάξτε τον κωδικό σας στο " +
                "TAXISnet."
        } else {
            "Για λόγους ασφαλείας δεν αποστέλλονται κωδικοί μέσω email. Αν τους " +
                "χρειάζεστε, επικοινωνήστε μαζί μας."
        }

        val text = buildString {
            append("Αγαπητέ/ή ")
            append(client.displayName)
            append(",\n\nΣας στέλνουμε τα στοιχεία σας:\n\n")
            rows.forEach { (label, value) -> append("  $label: $value\n") }
            append("\n")
            append(warning)
            append(footer(officeName, signature))
        }

        val html = buildString {
            append("<div style=\"font-family:system-ui,Arial,sans-serif;font-size:14px;color:#0B1B2B\">")
            append("<p>Αγαπητέ/ή ").append(esc(client.displayName)).append(",</p>")
            append("<p>Σας στέλνουμε τα στοιχεία σας:</p>")
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
            append(footer(officeName, signature).trim().replace("\n", "<br>").let { "<p>$it</p>" })
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
    ): Body {
        val subject = if (fileNames.size == 1) {
            "Φορολογικό έντυπο — ΑΦΜ ${client.afm}"
        } else {
            "Φορολογικά έντυπα (${fileNames.size}) — ΑΦΜ ${client.afm}"
        }

        val text = buildString {
            append("Αγαπητέ/ή ").append(client.displayName).append(",\n\n")
            append(
                if (fileNames.size == 1) "Σας επισυνάπτουμε το παρακάτω έντυπο:\n\n"
                else "Σας επισυνάπτουμε τα παρακάτω έντυπα:\n\n",
            )
            fileNames.forEach { append("  • $it\n") }
            if (note.isNotBlank()) append("\n").append(note).append("\n")
            append(footer(officeName, signature))
        }

        val html = buildString {
            append("<div style=\"font-family:system-ui,Arial,sans-serif;font-size:14px;color:#0B1B2B\">")
            append("<p>Αγαπητέ/ή ").append(esc(client.displayName)).append(",</p>")
            append(
                if (fileNames.size == 1) "<p>Σας επισυνάπτουμε το παρακάτω έντυπο:</p>"
                else "<p>Σας επισυνάπτουμε τα παρακάτω έντυπα:</p>",
            )
            append("<ul>")
            fileNames.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul>")
            if (note.isNotBlank()) append("<p>").append(esc(note)).append("</p>")
            append(footer(officeName, signature).trim().replace("\n", "<br>").let { "<p>$it</p>" })
            append("</div>")
        }

        return Body(subject, text, html)
    }
}
