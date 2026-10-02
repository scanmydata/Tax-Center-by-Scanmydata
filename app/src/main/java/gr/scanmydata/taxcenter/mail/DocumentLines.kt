package gr.scanmydata.taxcenter.mail

import gr.scanmydata.taxcenter.data.db.DocumentEntity
import gr.scanmydata.taxcenter.debts.Debts
import gr.scanmydata.taxcenter.debts.DebtsStore
import gr.scanmydata.taxcenter.engine.DocumentNaming
import gr.scanmydata.taxcenter.ui.AthensDates
import java.io.File
import java.time.LocalDate

/**
 * Η λίστα των συνημμένων, **όπως τη διαβάζει ο πελάτης**.
 *
 * Για τα περισσότερα έντυπα αρκεί το όνομά τους: «Εκκαθαριστικό δήλωσης 2024».
 * Για τις οφειλές δεν αρκεί. Ένας πελάτης με πέντε δόσεις ΕΝΦΙΑ έπαιρνε πέντε
 * γραμμές που έλεγαν όλες «Ταυτότητα οφειλής», και έπρεπε να ανοίξει πέντε PDF
 * για να βρει ποια λήγει πρώτη. Το email οφειλών είναι χρήσιμο μόνο αν λέει,
 * για κάθε συνημμένο, **τι** είναι, **πόσο**, **μέχρι πότε** και **με ποιον
 * κωδικό** — τα ίδια τέσσερα που γράφει και το μήνυμα SMS.
 *
 * ## Από πού έρχονται τα στοιχεία
 *
 * Από την ίδια εικόνα που δείχνει η καρτέλα «Οφειλές» ([DebtsStore]). Το
 * συνημμένο ταιριάζει με την οφειλή του από το **όνομα αρχείου**
 * ([Debts.Line.files]).
 *
 * Όταν δεν ταιριάζει — το έντυπο είναι παλαιότερο από την τελευταία ενημέρωση
 * και το ποσό άλλαξε — **δεν μαντεύουμε**: γράφεται μόνο ό,τι λέει το ίδιο το
 * όνομα του αρχείου. Λάθος ημερομηνία λήξης σε email προς πελάτη είναι
 * χειρότερη από καμία.
 *
 * ## ΑΑΔΕ και ΚΕΑΟ χωριστά
 *
 * Οι οφειλές μπαίνουν κάτω από την πύλη τους. Είναι άλλος πιστωτής, άλλος
 * κωδικός πληρωμής και άλλη ρύθμιση, και ο πελάτης τα πληρώνει από άλλη οθόνη
 * του e-banking.
 */
object DocumentLines {

    /** Οι επικεφαλίδες, με τη σειρά που εμφανίζονται. */
    const val GROUP_AADE = "Οφειλές ΑΑΔΕ"
    const val GROUP_KEAO = "Οφειλές ΚΕΑΟ"
    const val GROUP_OTHER = "Λοιπά έντυπα"

    /** Διαβάζει δίσκο — όχι από το κύριο νήμα όπου μπορεί να αποφευχθεί. */
    fun items(
        filesDir: File,
        afm: String,
        documents: List<DocumentEntity>,
        detailed: Boolean,
    ): List<MailTemplates.Item> {
        // Χωρίς έντυπο οφειλής δεν ανοίγουμε καθόλου τα JSON.
        val snapshot = if (documents.any { source(it.fileName) != null }) {
            runCatching { DebtsStore.load(filesDir, afm) }.getOrDefault(Debts.Snapshot())
        } else {
            Debts.Snapshot()
        }
        return items(
            files = documents.map { it.fileName to it.year },
            snapshot = snapshot,
            detailed = detailed,
            today = LocalDate.now(AthensDates.ZONE),
        )
    }

    /**
     * @param files όνομα αρχείου και έτος, με τη σειρά των συνημμένων
     * @param detailed αν μπαίνουν ποσά, δόσεις και ταυτότητες κάτω από κάθε
     *   οφειλή — ο διακόπτης του προτύπου. Ο χωρισμός ανά πύλη γίνεται πάντα.
     */
    fun items(
        files: List<Pair<String, String>>,
        snapshot: Debts.Snapshot,
        detailed: Boolean,
        today: LocalDate,
    ): List<MailTemplates.Item> {
        val index = HashMap<String, Debts.Line>()
        for (line in snapshot.lines) for (name in line.files) index.putIfAbsent(name.lowercase(), line)

        val anyDebt = files.any { source(it.first) != null }
        val out = files.map { (name, year) ->
            val portal = source(name)
            if (portal == null) {
                MailTemplates.Item(
                    title = DocumentNaming.line(name, year),
                    // Επικεφαλίδα μόνο όταν υπάρχει και κάτι άλλο να ξεχωρίσει.
                    group = if (anyDebt) GROUP_OTHER else "",
                )
            } else {
                debt(name, portal, index[name.lowercase()], detailed, today)
            }
        }
        // Οι πύλες πρώτα, με σταθερή σειρά· μέσα σε κάθε ομάδα μένει η σειρά
        // των συνημμένων.
        val order = listOf(GROUP_AADE, GROUP_KEAO, GROUP_OTHER, "")
        return out.sortedBy { order.indexOf(it.group).let { i -> if (i < 0) order.size else i } }
    }

    /** Η πύλη ενός εντύπου οφειλής, από το πρόθεμα του ονόματός του. */
    fun source(fileName: String): Debts.Source? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        return when {
            KEAO_PREFIXES.any { name.startsWith(it, ignoreCase = true) } -> Debts.Source.KEAO
            AADE_PREFIXES.any { name.startsWith(it, ignoreCase = true) } -> Debts.Source.AADE
            else -> null
        }
    }

    private fun debt(
        fileName: String,
        source: Debts.Source,
        line: Debts.Line?,
        detailed: Boolean,
        today: LocalDate,
    ): MailTemplates.Item {
        val group = if (source == Debts.Source.AADE) GROUP_AADE else GROUP_KEAO
        val label = DocumentNaming.label(fileName).ifBlank { "Έντυπο οφειλής" }
        val attachment = "Συνημμένο: $fileName"

        if (line == null) {
            // Δεν βρέθηκε η οφειλή στην καρτέλα. Το όνομα του αρχείου της ΑΑΔΕ
            // κουβαλά όμως κατηγορία και ποσό, και αυτά είναι του ίδιου του
            // εντύπου — όχι μαντεψιά.
            val named = fromName(fileName)
            return MailTemplates.Item(
                title = if (named == null) label else named.first + " — " + label,
                details = buildList {
                    if (detailed && named != null && named.second.isNotBlank()) {
                        add("Ποσό: " + named.second + " €")
                    }
                    add(attachment)
                },
                group = group,
            )
        }

        val title = buildString {
            append(line.title)
            if (line.detail.isNotBlank()) append(" (").append(line.detail).append(')')
            append(" — ").append(label)
        }
        if (!detailed) return MailTemplates.Item(title, listOf(attachment), group)

        val details = ArrayList<String>()
        if (line.total.isNotBlank()) details += "Υπόλοιπο: " + line.total + " €"
        val next = line.next
        if (next != null && next.amount.isNotBlank()) {
            val expired = next.dueDate?.isBefore(today) == true
            details += when {
                next.due.isBlank() -> "Δόση: " + next.amount + " €"
                // Το «έληξε» δεν είναι στολίδι: μια ημερομηνία στο παρελθόν
                // χωρίς αυτό διαβάζεται ως λάθος του μηνύματος.
                expired -> "Δόση: " + next.amount + " € — έληξε " + next.due
                else -> "Επόμενη δόση: " + next.amount + " € — λήξη " + next.due
            }
        }
        val late = line.late(today)
        if (late.size > 1) {
            details += "Ληξιπρόθεσμες δόσεις: " + late.size + ", σύνολο " + line.lateTotal(today) + " €"
        }
        if (line.instalments > 0 && line.unpaid.isNotEmpty()) {
            details += "Απομένουν " + line.unpaid.size + " από " + line.instalments + " δόσεις"
        }
        if (line.code.isNotBlank()) details += line.codeLabel + ": " + line.code
        details += attachment
        return MailTemplates.Item(title, details, group)
    }

    /**
     * Κατηγορία και ποσό από `OFEILI_<ΑΦΜ>_<κατηγορία>_<ποσό>.pdf` (και το ίδιο
     * για το `DOSEIS_…`). Τα κενά της κατηγορίας έγιναν `_` στο όνομα και
     * γυρίζουν πίσω σε κενά.
     */
    private fun fromName(fileName: String): Pair<String, String>? {
        val base = fileName.substringBeforeLast('.')
        if (!base.startsWith("OFEILI_", ignoreCase = true) && !base.startsWith("DOSEIS_", ignoreCase = true)) {
            return null
        }
        val parts = base.split('_').drop(2)
        if (parts.isEmpty()) return null
        val amount = parts.last().takeIf { AMOUNT.matches(it) }.orEmpty()
        val kind = (if (amount.isBlank()) parts else parts.dropLast(1)).joinToString(" ").trim()
        return if (kind.isBlank()) null else kind to amount
    }

    private val AMOUNT = Regex("""-?[\d.]+,\d{2}""")

    // Το ΚΕΑΟ ελέγχεται πρώτο: το `KEAO_RYTHMISI` δεν είναι `RYTHMISI`, αλλά η
    // σειρά το κάνει προφανές σε όποιον προσθέσει πρόθεμα αύριο.
    private val KEAO_PREFIXES = listOf("KEAO_KARTELA", "KEAO_RYTHMISI", "KEAO_PBO")
    private val AADE_PREFIXES = listOf("OFEILI_", "RYTHMISI_", "DOSEIS_")
}
