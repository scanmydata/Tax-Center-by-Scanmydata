package gr.scanmydata.taxcenter.aade

import gr.scanmydata.taxcenter.doc.Money
import gr.scanmydata.taxcenter.doc.Report
import gr.scanmydata.taxcenter.doc.Section
import gr.scanmydata.taxcenter.doc.Table
import org.json.JSONArray
import org.json.JSONObject

/**
 * Το **δοσολόγιο** μιας οφειλής ή μιας ρύθμισης της ΑΑΔΕ, από το JSON του
 * `aade-debts` σε σελίδες που μπαίνουν πίσω από την Ταυτότητα Οφειλής.
 *
 * ## Γιατί χωριστά από την Ταυτότητα
 *
 * Το έντυπο που εκδίδει η ΑΑΔΕ λέει **πόσο** και **με ποιον κωδικό** πληρώνεις.
 * Δεν λέει **πότε**: οι δόσεις φαίνονται μόνο στην οθόνη, σε πίνακα που η πύλη
 * κρύβει μέχρι να πατηθεί το κουμπί δίπλα στη γραμμή. Ο πελάτης που λαμβάνει
 * μόνο την Ταυτότητα ξαναρωτά «πότε λήγει η επόμενη;» — και ο λογιστής ξαναμπαίνει
 * στην πύλη για να του το πει.
 *
 * Ο desktop runner το έλυνε με Playwright: τύπωνε τον πίνακα σε PDF και τον
 * κολλούσε δεύτερη σελίδα. Στο Android το rendering γίνεται εδώ, με τον ίδιο
 * σχεδιαστή που φτιάχνει την καρτέλα ΚΕΑΟ, και η προσάρτηση στο
 * [gr.scanmydata.taxcenter.doc.PdfFile.append].
 *
 * ## Οι στήλες δεν είναι γραμμένες πουθενά
 *
 * Οι επικεφαλίδες έρχονται **από την ίδια την πύλη** και τυπώνονται όπως ήρθαν.
 * Το ποιες στήλες είναι ποσά κρίνεται από το περιεχόμενο και όχι από το όνομα:
 * η σελίδα των ρυθμίσεων έχει άλλες στήλες από τη σελίδα των οφειλών, και ένας
 * πίνακας αντιστοίχισης θα έσπαγε σιωπηλά την πρώτη φορά που η ΑΑΔΕ θα άλλαζε
 * μια λέξη.
 */
object DebtSchedule {

    /**
     * Ένα δοσολόγιο και πού πάει.
     *
     * @param target το PDF της πύλης όπου προσαρτάται — το κανονικό αποτέλεσμα.
     * @param fallback όνομα για δικό του αρχείο, όταν το [target] δεν βγήκε ή
     *   δεν διαβάζεται. Το δοσολόγιο φτάνει στον πελάτη έτσι κι αλλιώς: μια
     *   σιωπηλή απώλεια εδώ μοιάζει με οφειλή χωρίς δόσεις.
     */
    data class Attachment(
        val target: String,
        val fallback: String,
        val report: Report,
    )

    /** Οι σελίδες του `aade-debts` που κουβαλούν δοσολόγιο, με τον τίτλο τους. */
    private val PAGES = listOf(
        "debts_unregulated" to "Ανάλυση δόσεων οφειλής",
        "debts_arrangement" to "Δοσολόγιο ρύθμισης",
        "debts_coresponsible" to "Ανάλυση δόσεων οφειλής",
    )

    fun attachments(
        json: String,
        clientName: String,
        afm: String,
        office: String,
        retrievedAt: String,
    ): List<Attachment> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val sections = root.optJSONObject("sections") ?: return emptyList()
        val out = ArrayList<Attachment>()
        val used = HashSet<String>()
        for ((key, title) in PAGES) {
            val page = sections.optJSONObject(key) ?: continue
            val debts = page.optJSONArray("debts") ?: continue
            for (i in 0 until debts.length()) {
                val debt = debts.optJSONObject(i) ?: continue
                val table = table(debt.optJSONObject("installments")) ?: continue
                out += attachment(debt, table, title, clientName, afm, office, retrievedAt, used)
            }
        }
        return out
    }

    private fun attachment(
        debt: JSONObject,
        table: Table,
        title: String,
        clientName: String,
        afm: String,
        office: String,
        retrievedAt: String,
        used: MutableSet<String>,
    ): Attachment {
        val fields = fields(debt.optJSONObject("fields"))
        val kind = pick(fields, "Είδος φόρου", "Είδος", "Τύπος Ρύθμισης", "Ρύθμιση")
        val total = debt.optString("total").ifBlank { debt.optString("overdueBalance") }
            .ifBlank { debt.optString("nonOverdue") }

        return Attachment(
            target = debt.optString("toPdf").trim(),
            fallback = unique(fallbackName(afm, kind, total), used),
            report = Report(
                fileName = "",
                title = title,
                office = office,
                identity = buildList {
                    add("Υπόχρεος" to listOf(clientName, afm).filter { it.isNotBlank() }.joinToString(" · "))
                    if (kind.isNotBlank()) add("Οφειλή" to kind)
                    for (label in listOf("ΔΟΥ", "Πηγή", "Οικ. έτος", "Ημ/νία Βεβαίωσης", "Ημ/νία Ρύθμισης")) {
                        fields[label]?.takeIf { it.isNotBlank() }?.let { add(label to it) }
                    }
                    if (total.isNotBlank()) add("Συνολικό ποσό" to total)
                },
                // Η ταυτότητα είναι ήδη τυπωμένη στη σελίδα της ΑΑΔΕ που
                // προηγείται· δεύτερη φορά δεν προσθέτει τίποτα και δίνει την
                // εντύπωση δεύτερου κωδικού.
                debtorId = "",
                summary = summary(debt.optJSONObject("general")),
                sections = listOf(Section(caption = "Δόσεις", table = table)),
                footer = buildList {
                    add(
                        "Άντληση από την Προσωποποιημένη Πληροφόρηση της ΑΑΔΕ" +
                            if (retrievedAt.isNotBlank()) " στις $retrievedAt." else ".",
                    )
                    add(
                        "Οι δόσεις αλλάζουν με κάθε καταβολή και με τον χρόνο " +
                            "(προσαυξήσεις, τόκοι). Η πληρωμή γίνεται με την Ταυτότητα " +
                            "Οφειλής της προηγούμενης σελίδας.",
                    )
                    add("Η σελίδα των δόσεων προστέθηκε από το γραφείο — δεν είναι έγγραφο της ΑΑΔΕ.")
                },
            ),
        )
    }

    // ------------------------------------------------------------- πίνακας

    private fun table(source: JSONObject?): Table? {
        if (source == null) return null
        val headers = strings(source.optJSONArray("headers"))
        val rows = (source.optJSONArray("rows") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONArray(i)?.let(::strings) }
        }
        if (headers.isEmpty() || rows.isEmpty()) return null

        val columns = headers.indices
        val moneyColumn = columns.map { c -> rows.any { row -> isMoney(row.getOrNull(c)) } }
        return Table(
            headers = headers,
            // Χωρίς αναλογίες: τις στήλες τις ονομάζει η πύλη, και το
            // «Προσαυξήσεις, Τόκοι, Τέλη» δεν χωρά σε καμία σταθερή αναλογία.
            rows = rows.map { row -> columns.map { row.getOrNull(it).orEmpty() } },
            totals = columns.map { c ->
                when {
                    c == 0 -> "ΣΥΝΟΛΑ"
                    moneyColumn[c] -> Money.money(Money.sum(rows.map { it.getOrNull(c).orEmpty() }))
                    else -> ""
                }
            },
            numeric = columns.filter { moneyColumn[it] },
        )
    }

    /**
     * Ποσό; Ζητά δεκαδικό με κόμμα — «31/07/2026» και «ΟΧΙ» δεν αθροίζονται,
     * και ούτε ένα σκέτο «2» (αριθμός δόσεων) που θα έβγαζε σύνολο από το πουθενά.
     */
    private fun isMoney(cell: String?): Boolean =
        cell != null && Regex("""-?[\d.]+,\d{2}""").containsMatchIn(cell)

    /** Η «Γενική Εικόνα Δόσεων» της πύλης: ζεύγη ετικέτα-τιμή, όπως ήρθαν. */
    private fun summary(source: JSONObject?): List<Pair<String, String>> {
        val rows = source?.optJSONArray("rows") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val cells = rows.optJSONArray(i)?.let(::strings).orEmpty()
            if (cells.size < 2 || cells[0].isBlank()) null else cells[0] to cells[1]
        }
    }

    // -------------------------------------------------------------- βοηθοί

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it).trim() }
    }

    private fun fields(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it).trim() }
    }

    private fun pick(fields: Map<String, String>, vararg labels: String): String =
        labels.firstNotNullOfOrNull { fields[it]?.takeIf { value -> value.isNotBlank() } }.orEmpty()

    /**
     * `DOSEIS_<ΑΦΜ>_<κατηγορία>_<ποσό>.pdf` — το ίδιο όνομα που έδινε ο runner
     * όταν έγραφε το δοσολόγιο σε δικό του αρχείο.
     */
    fun fallbackName(afm: String, kind: String, total: String): String {
        val parts = listOf(afm, san(kind), san(total)).filter { it.isNotBlank() }
        return "DOSEIS_" + parts.joinToString("_") + ".pdf"
    }

    /** Ελληνικά επιτρέπονται· ό,τι μπερδεύει το filesystem, όχι. */
    private fun san(raw: String): String = raw
        .map { if (it.isLetterOrDigit() || it == ',' || it == '.' || it == '-') it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(60)

    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val base = name.removeSuffix(".pdf")
        var n = 2
        while (!used.add("${base}_$n.pdf")) n++
        return "${base}_$n.pdf"
    }
}
