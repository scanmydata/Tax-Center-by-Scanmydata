package gr.scanmydata.taxcenter.google

import gr.scanmydata.taxcenter.debts.Debts
import gr.scanmydata.taxcenter.requests.AadeRequests
import gr.scanmydata.taxcenter.requests.RequestDetail
import java.time.LocalDate

/**
 * Τα αρχεία κειμένου που ανεβαίνουν στο Drive για τις **οφειλές** και τα
 * **αιτήματα** ενός πελάτη.
 *
 * Στη συσκευή αυτά ζουν ως JSON του engine, που δεν είναι για ανθρώπους. Στο
 * Drive ανεβαίνει ό,τι θα διάβαζε ο λογιστής ανοίγοντας τον φάκελο του
 * πελάτη από υπολογιστή: σκέτο κείμενο UTF-8, χωρίς μορφοποίηση που θέλει
 * πρόγραμμα, με τα ποσά και τις ημερομηνίες όπως τα δείχνει η εφαρμογή.
 *
 * Τι **δεν** μπαίνει: κωδικοί πρόσβασης, ΑΜΚΑ, και ό,τι άλλο ζει στην καρτέλα
 * του πελάτη χωρίς να είναι μέρος της οφειλής ή του αιτήματος. Ο φάκελος του
 * πελάτη στο Drive έχει ήδη ΑΦΜ και επωνυμία στο όνομά του· δεν χρειάζεται
 * τίποτα περισσότερο για να βρεθεί.
 *
 * Καθαρή Kotlin: ό,τι φεύγει από τη συσκευή ελέγχεται με τεστ.
 */
object DriveReports {

    const val DEBTS_FILE = "Οφειλές.txt"
    const val REQUESTS_FILE = "Αιτήματα.txt"
    const val REQUEST_FILE = "Αίτημα.txt"

    // ------------------------------------------------------------- οφειλές

    /**
     * @param aadeAt / keaoAt πότε διαβάστηκε η κάθε πύλη, ήδη μορφοποιημένο· κενό
     *   όταν δεν έχει διαβαστεί ποτέ
     */
    fun debts(
        clientName: String,
        afm: String,
        snapshot: Debts.Snapshot,
        aadeAt: String,
        keaoAt: String,
        today: LocalDate,
        efkaAt: String = "",
    ): String = buildString {
        append("Οφειλές — ").append(clientName).append(" (ΑΦΜ ").append(afm).append(")\n")
        append("ΑΑΔΕ: ").append(if (aadeAt.isBlank()) "δεν έχει ενημερωθεί" else "ενημέρωση $aadeAt")
        append(" · ΚΕΑΟ: ").append(if (keaoAt.isBlank()) "δεν έχει ενημερωθεί" else "ενημέρωση $keaoAt")
        append(" · ΕΦΚΑ: ").append(if (efkaAt.isBlank()) "δεν έχει ενημερωθεί" else "ενημέρωση $efkaAt")
        append("\n")

        for (source in Debts.Source.entries) {
            val known = when (source) {
                Debts.Source.AADE -> aadeAt.isNotBlank()
                Debts.Source.KEAO -> keaoAt.isNotBlank()
                Debts.Source.EFKA -> efkaAt.isNotBlank()
            }
            append("\n").append("=".repeat(60)).append("\n")
            append(source.label)
            if (known) append(" — σύνολο ").append(snapshot.total(source)).append(" €")
            append("\n").append("=".repeat(60)).append("\n")
            if (!known) {
                append("Δεν έχει γίνει ενημέρωση από την πύλη.\n")
                continue
            }
            if (source == Debts.Source.KEAO && snapshot.keaoPartial.isNotEmpty()) {
                append("Μερική εικόνα: η τελευταία ενημέρωση περιορίστηκε σε ")
                    .append(snapshot.keaoPartial.joinToString(", ")).append(".\n")
            }
            val groups = Debts.Group.entries.filter { it.source == source }
            var any = false
            for (group in groups) {
                val lines = snapshot.inGroup(group)
                if (lines.isEmpty()) continue
                any = true
                append("\n").append(group.short)
                if (group != Debts.Group.KEAO_INACTIVE) append(" — ").append(snapshot.total(group)).append(" €")
                else append(" (δεν μετρούν στο σύνολο)")
                append("\n")
                lines.forEach { append(line(it, today)) }
            }
            if (!any) append("Καμία οφειλή.\n")
        }
        append("\n").append(footer())
    }

    private fun line(line: Debts.Line, today: LocalDate): String = buildString {
        append("  • ").append(line.title)
        if (line.detail.isNotBlank()) append(" (").append(line.detail).append(")")
        append("\n")
        if (line.status.isNotBlank()) append("    Κατάσταση: ").append(line.status).append("\n")
        if (line.total.isNotBlank()) append("    Υπόλοιπο: ").append(line.total).append(" €")
        if (line.overdue.isNotBlank()) append(" · ληξιπρόθεσμο: ").append(line.overdue).append(" €")
        if (line.total.isNotBlank() || line.overdue.isNotBlank()) append("\n")
        line.parts.forEach { part ->
            append("    ").append(part.label).append(": ").append(part.amount).append(" €")
            if (part.included) append(" (περιλαμβάνεται)")
            append("\n")
        }
        val next = line.next
        if (next != null) {
            val late = next.dueDate?.isBefore(today) == true
            append("    ").append(if (late) "Ληγμένη δόση" else "Επόμενη δόση")
            if (next.no.isNotBlank()) append(" ").append(next.no)
            if (line.instalments > 0) append(" από ").append(line.instalments)
            append(": ").append(next.amount).append(" €")
            if (next.due.isNotBlank()) append(", ").append(if (late) "έληξε " else "λήξη ").append(next.due)
            append("\n")
            val overdue = line.late(today)
            if (overdue.size > 1) {
                append("    Ληξιπρόθεσμες δόσεις: ").append(overdue.size)
                    .append(", σύνολο ").append(line.lateTotal(today)).append(" €\n")
            }
        }
        if (line.code.isNotBlank()) append("    ").append(line.codeLabel).append(": ").append(line.code).append("\n")
    }

    // ------------------------------------------------------------ αιτήματα

    /** Η λίστα όλων των αιτημάτων του πελάτη, όπως την έδωσε η ΑΑΔΕ. */
    fun requests(
        clientName: String,
        afm: String,
        requests: List<AadeRequests.Request>,
        listedAt: String,
    ): String = buildString {
        append("Αιτήματα προς την ΑΑΔΕ — ").append(clientName).append(" (ΑΦΜ ").append(afm).append(")\n")
        append("Λίστα της ").append(listedAt.ifBlank { "—" }).append(" · ")
        append(if (requests.size == 1) "1 αίτημα" else "${requests.size} αιτήματα").append("\n")
        for (request in requests.sortedByDescending { it.updated.ifBlank { it.submitted } }) {
            append("\n• ").append(request.title).append("\n")
            if (request.caseNumber.isNotBlank()) append("  Αρ. υπόθεσης: ").append(request.caseNumber).append("\n")
            append("  Κατάσταση: ").append(request.status.ifBlank { "—" })
            if (request.rejected) append(" (απορρίφθηκε)")
            append("\n")
            if (request.submitted.isNotBlank()) append("  Υποβολή: ").append(AadeRequests.date(request.submitted)).append("\n")
            if (request.updated.isNotBlank()) append("  Ενημέρωση: ").append(AadeRequests.date(request.updated)).append("\n")
        }
        append("\n").append(footer())
    }

    /** Ένα αίτημα ολόκληρο: κείμενο, απάντηση, συνημμένα. */
    fun request(
        clientName: String,
        afm: String,
        listed: AadeRequests.Request?,
        detail: RequestDetail.Detail,
        retrievedAt: String,
    ): String = buildString {
        val title = listed?.title ?: listOf(detail.process, detail.topic).firstOrNull { it.isNotBlank() } ?: "Αίτημα"
        append("Αίτημα προς την ΑΑΔΕ — ").append(title).append("\n")
        append("Πελάτης: ").append(clientName).append(" (ΑΦΜ ").append(afm).append(")\n")
        val case = detail.caseNumber.ifBlank { listed?.caseNumber.orEmpty() }
        if (case.isNotBlank()) append("Αρ. υπόθεσης: ").append(case).append("\n")
        field("Θεματική ενότητα", detail.topic.ifBlank { listed?.topic.orEmpty() })
        field("Διαδικασία", detail.process.ifBlank { listed?.process.orEmpty() })
        field("Υπηρεσία", detail.service.ifBlank { listed?.service.orEmpty() })
        if (listed != null) {
            field("Κατάσταση", listed.status + if (listed.rejected) " (απορρίφθηκε)" else "")
            if (listed.submitted.isNotBlank()) field("Υποβολή", AadeRequests.date(listed.submitted))
            if (listed.updated.isNotBlank()) field("Ενημέρωση", AadeRequests.date(listed.updated))
        }
        if (detail.reference.isNotBlank()) field("Σχετικό αίτημα", detail.reference)
        if (detail.archived) append("Αρχειοθετημένο στην πύλη.\n")

        section("Κείμενο αιτήματος", detail.text.ifBlank { listed?.text.orEmpty() })
        section("Απάντηση", detail.answer.ifBlank { listed?.answer.orEmpty() })
        section("Λόγος επανεξέτασης", detail.reviewReason)
        section("Λόγος απόρριψης", detail.rejectReason)
        section("Δικαιολογητικά της διαδικασίας", detail.supporting)

        if (detail.attachments.isNotEmpty()) {
            append("\nΣυνημμένα\n").append("-".repeat(9)).append("\n")
            detail.attachments.forEach { file ->
                append("• ").append(file.name).append(" — ").append(file.origin.label)
                if (!file.downloaded) append(" (δεν κατέβηκε)")
                append("\n")
            }
        }
        if (detail.thread.isNotEmpty()) {
            append("\nΑλληλογραφία\n").append("-".repeat(11)).append("\n")
            detail.thread.forEach { note ->
                if (note.date.isNotBlank()) append(AadeRequests.date(note.date)).append(" — ")
                append(note.text).append("\n")
            }
        }
        append("\nΑποθηκεύτηκε από την ΑΑΔΕ: ").append(retrievedAt.ifBlank { "—" }).append("\n")
        append(footer())
    }

    /**
     * Ο υποφάκελος ενός αιτήματος: «<αρ. υπόθεσης> — <είδος>».
     *
     * Ξεκινά από τον αριθμό, που δεν αλλάζει· το είδος κόβεται, γιατί οι
     * διαδικασίες της ΑΑΔΕ έχουν ονόματα μιας παραγράφου. Οι κάθετες γίνονται
     * παύλες: ο αριθμός υπόθεσης τις έχει, και στο Drive θα έμοιαζαν με διαδρομή.
     */
    fun requestFolder(id: String, caseNumber: String, title: String): String {
        val head = caseNumber.ifBlank { "Αίτημα $id" }.replace('/', '-')
        val tail = title.trim().let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
        return (if (tail.isBlank()) head else "$head — $tail").replace(Regex("[\\r\\n\\t]"), " ")
    }

    private fun StringBuilder.field(label: String, value: String) {
        if (value.isNotBlank()) append(label).append(": ").append(value.trim()).append("\n")
    }

    private fun StringBuilder.section(label: String, value: String) {
        if (value.isBlank()) return
        append("\n").append(label).append("\n").append("-".repeat(label.length)).append("\n")
        append(value.trim()).append("\n")
    }

    private fun footer(): String =
        "Από το ScanMyData Tax Center. Τα στοιχεία είναι όπως τα έδειξε η πύλη τη στιγμή της ενημέρωσης.\n"
}
