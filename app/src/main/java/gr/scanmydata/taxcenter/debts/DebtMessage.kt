package gr.scanmydata.taxcenter.debts

import gr.scanmydata.taxcenter.mail.MailTemplateStore
import gr.scanmydata.taxcenter.mail.MailTemplateStore.DebtField
import gr.scanmydata.taxcenter.mail.MailTemplateStore.Template
import java.time.LocalDate

/**
 * Το κείμενο του μηνύματος οφειλής — για SMS και Viber.
 *
 * Λέει τέσσερα πράγματα ανά οφειλή, με αυτή τη σειρά: **τι** είναι, **πόσο**
 * είναι η δόση, **μέχρι πότε**, και **με ποιον κωδικό** πληρώνεται. Η σειρά
 * είναι αυτή με την οποία τα χρειάζεται όποιος ανοίγει το e-banking.
 *
 * Το ποιες γραμμές μπαίνουν το ορίζει το πρότυπο (διακόπτες στις Ρυθμίσεις)·
 * το πώς γράφονται μένει εδώ, ώστε ένα ποσό να μη φύγει ποτέ χωρίς το «€» ή
 * ένας κωδικός χωρίς την ετικέτα του.
 *
 * Καθαρή Kotlin: όλο το κείμενο που φτάνει σε πελάτη ελέγχεται με τεστ.
 */
object DebtMessage {

    fun text(
        clientName: String,
        afm: String,
        lines: List<Debts.Line>,
        template: Template,
        office: String,
        today: LocalDate,
    ): String {
        val blocks = lines.map { block(it, template, today) }.filter { it.isNotBlank() }
        return buildString {
            val intro = fill(template.intro, clientName, afm)
            if (intro.isNotBlank()) append(intro).append("\n\n")
            append(blocks.joinToString("\n\n"))
            val closing = fill(template.closing, clientName, afm)
            if (closing.isNotBlank()) append("\n\n").append(closing)
            if (template.has(DebtField.OFFICE) && office.isNotBlank()) {
                append("\n\n").append(office.trim())
            }
        }.trim()
    }

    private fun block(line: Debts.Line, template: Template, today: LocalDate): String {
        val rows = ArrayList<String>()
        if (template.has(DebtField.NAME)) rows += line.title + " (" + line.group.source + ")"

        val next = line.next
        if (template.has(DebtField.AMOUNT) && line.payable.isNotBlank()) {
            // Χωρίς δοσολόγιο δεν υπάρχει «δόση»: το ποσό είναι όλο το υπόλοιπο.
            rows += (if (next != null) "Ποσό δόσης: " else "Ποσό: ") + line.payable + " €"
        }
        if (template.has(DebtField.DUE) && next != null && next.due.isNotBlank()) {
            val late = next.dueDate?.isBefore(today) == true
            // Το «έληξε» δεν είναι στολίδι: μια ημερομηνία στο παρελθόν χωρίς
            // αυτό διαβάζεται ως λάθος του μηνύματος και αγνοείται.
            rows += if (late) "Έληξε: " + next.due else "Λήξη: " + next.due
        }
        if (template.has(DebtField.LATE)) {
            val late = line.late(today)
            // Μία μόνο ληξιπρόθεσμη είναι ήδη η δόση που γράφτηκε παραπάνω.
            if (late.size > 1) {
                rows += "Ληξιπρόθεσμες δόσεις: " + late.size + ", σύνολο " + line.lateTotal(today) + " €"
            }
        }
        if (template.has(DebtField.TOTAL) && line.total.isNotBlank() && line.total != line.payable) {
            rows += "Συνολικό υπόλοιπο: " + line.total + " €"
        }
        if (template.has(DebtField.CODE) && line.code.isNotBlank()) {
            rows += line.codeLabel + ": " + line.code
        }
        return rows.joinToString("\n")
    }

    private fun fill(raw: String, clientName: String, afm: String): String = raw
        .replace(MailTemplateStore.PLACEHOLDER_NAME, clientName)
        .replace(MailTemplateStore.PLACEHOLDER_AFM, afm)
        .trim()

    /** Το θέμα δεν φεύγει ποτέ στον πελάτη — γράφεται μόνο στο ημερολόγιο. */
    fun subject(clientName: String, afm: String, template: Template): String =
        fill(template.subject, clientName, afm)

    /** Τι καταγράφεται ως περιεχόμενο της αποστολής: οι οφειλές, μία ανά γραμμή. */
    fun items(lines: List<Debts.Line>): List<String> = lines.map { line ->
        line.title + " (" + line.group.source + ")" +
            if (line.payable.isBlank()) "" else " — " + line.payable + " €"
    }

    // ------------------------------------------------------------------- SMS

    /**
     * Πόσα τμήματα SMS θα χρεωθούν για ένα κείμενο.
     *
     * Αξίζει να φαίνεται πριν την αποστολή: ένα SMS χωρά 160 χαρακτήρες μόνο
     * στο λατινικό αλφάβητο του GSM. Με **ένα** πεζό ελληνικό γράμμα το μήνυμα
     * γυρίζει σε Unicode και το όριο πέφτει στους 70 — οπότε ένα μήνυμα
     * οφειλής είναι σχεδόν πάντα τρία-τέσσερα τμήματα, όχι ένα.
     */
    data class Sms(val chars: Int, val parts: Int, val unicode: Boolean)

    fun sms(text: String): Sms {
        var units = 0
        var unicode = false
        for (ch in text) {
            when {
                ch in GSM_BASIC -> units += 1
                ch in GSM_EXTENDED -> units += 2
                else -> { unicode = true; break }
            }
        }
        if (unicode) {
            val n = text.length
            return Sms(n, if (n <= 70) 1 else (n + 66) / 67, true)
        }
        return Sms(units, if (units <= 160) 1 else (units + 152) / 153, false)
    }

    /** Το βασικό αλφάβητο GSM 03.38. Από τα ελληνικά έχει μόνο δέκα κεφαλαία. */
    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ!\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Η επέκταση: κάθε χαρακτήρας της μετρά για δύο. */
    private const val GSM_EXTENDED = "^{}\\[~]|€"
}
