package gr.scanmydata.taxcenter.requests

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Τα αιτήματα **όλων** των πελατών, σε μία λίστα.
 *
 * Η καρτέλα του πελάτη απαντά στο «τι γίνεται με τα αιτήματα του τάδε». Το
 * γραφείο όμως ρωτά αλλιώς: **τι περιμένει απάντηση, σε ποιον, και τι
 * απαντήθηκε από χθες** — και αυτό δεν φαίνεται ανοίγοντας καρτέλες μία-μία.
 *
 * Εδώ είναι μόνο η **διάταξη**: ταξινόμηση, φίλτρο, αναζήτηση, σύνοψη. Καθαρή
 * Kotlin, ώστε το «ποιο αίτημα βγαίνει πρώτο» να ελέγχεται με τεστ και όχι με
 * το μάτι.
 */
object RequestBoard {

    /**
     * Ένα αίτημα μαζί με τον πελάτη του.
     *
     * @param saved συνημμένα στη συσκευή, ή `null` όταν το αίτημα δεν έχει
     *   αποθηκευτεί ολόκληρο
     * @param outdated αποθηκευμένο, αλλά άλλαξε στην πύλη από τότε
     */
    data class Row(
        val clientId: Long,
        val clientName: String,
        val afm: String,
        val request: AadeRequests.Request,
        val fresh: Boolean = false,
        val saved: Int? = null,
        val outdated: Boolean = false,
    ) {
        /** Μοναδικό σε όλη τη λίστα: ο αριθμός αιτήματος μόνος του είναι ανά πελάτη. */
        val key: String get() = "$clientId:${request.id}"
    }

    enum class Sort(val label: String) {
        UPDATED("Ενημέρωση"),
        SUBMITTED("Υποβολή"),
        STATUS("Κατάσταση"),
        CLIENT("Πελάτης"),
    }

    enum class Filter(val label: String) {
        ALL("Όλα"),
        PENDING("Εκκρεμή"),
        FRESH("Νέα"),
        ANSWERED("Απαντημένα"),
    }

    data class Summary(val requests: Int, val clients: Int, val pending: Int, val fresh: Int)

    fun summary(rows: List<Row>): Summary = Summary(
        requests = rows.size,
        clients = rows.map { it.clientId }.distinct().size,
        pending = rows.count { !it.request.answered },
        fresh = rows.count { it.fresh },
    )

    /**
     * Η λίστα όπως τη βλέπει ο χρήστης.
     *
     * @param reversed αντιστρέφει τη **φυσική** φορά του κάθε κριτηρίου. Η
     *   φυσική φορά είναι αυτή που θέλει κανείς τις περισσότερες φορές: στις
     *   ημερομηνίες το νεότερο πρώτο, στην κατάσταση ό,τι περιμένει πρώτο, στον
     *   πελάτη αλφαβητικά.
     *
     * Όπου δύο αιτήματα ισοβαθμούν, προηγείται **πάντα** αυτό που ενημερώθηκε
     * πιο πρόσφατα — και με αντεστραμμένη φορά. Μια ταξινόμηση «ανά πελάτη»
     * που θα έδειχνε τα αιτήματα του καθενός με τυχαία σειρά δεν θα ήταν λίστα.
     */
    fun arrange(
        rows: List<Row>,
        sort: Sort = Sort.UPDATED,
        reversed: Boolean = false,
        filter: Filter = Filter.ALL,
        query: String = "",
    ): List<Row> {
        val needle = fold(query)
        val kept = rows.filter { row ->
            when (filter) {
                Filter.ALL -> true
                Filter.PENDING -> !row.request.answered
                Filter.FRESH -> row.fresh
                Filter.ANSWERED -> row.request.answered
            } && (needle.isEmpty() || matches(row, needle))
        }
        val newestFirst = compareByDescending<Row> { updated(it) }
        val primary: Comparator<Row> = when (sort) {
            Sort.UPDATED -> compareByDescending<Row> { updated(it) }
            Sort.SUBMITTED -> compareByDescending<Row> { it.request.submitted }
            Sort.STATUS -> compareBy<Row> { rank(it) }
            Sort.CLIENT -> Comparator { a, b -> GREEK.compare(a.clientName, b.clientName) }
        }
        val ordered = if (reversed) primary.reversed() else primary
        return kept.sortedWith(ordered.then(newestFirst).thenBy { it.key })
    }

    /**
     * Τα αιτήματα **ενός πελάτη**, μαζί.
     *
     * Η λίστα του γραφείου είναι λίστα πελατών: «ο τάδε έχει δύο αιτήματα, το
     * ένα περιμένει». Δύο κάρτες με το ίδιο όνομα, σκόρπιες ανάμεσα σε άλλες,
     * δεν το λένε αυτό.
     *
     * Οι τρεις μετρητές **χωρίζουν** τα αιτήματα — κάθε αίτημα μετριέται σε
     * έναν ακριβώς. Τα «νέα» είναι άλλη ερώτηση (τι δεν έχει ανοιχτεί) και
     * μετριούνται χωριστά.
     */
    data class Group(
        val clientId: Long,
        val clientName: String,
        val afm: String,
        /** Με τη σειρά της τρέχουσας ταξινόμησης. */
        val rows: List<Row>,
    ) {
        val fresh: Int get() = rows.count { it.fresh }
        val rejected: Int get() = rows.count { it.request.rejected }
        val answered: Int get() = rows.count { !it.request.rejected && it.request.answered }
        val waiting: Int get() = rows.count { !it.request.rejected && !it.request.answered && !it.request.waitingClient }

        /** Περιμένουν τον πελάτη (η ΑΑΔΕ ζήτησε κάτι). */
        val client: Int get() = rows.count { !it.request.rejected && it.request.waitingClient }

        /** Πότε κινήθηκε τελευταία φορά οποιοδήποτε αίτημά του. */
        val updated: String get() = rows.maxOfOrNull { updated(it) }.orEmpty()

        /** «2 αιτήματα». */
        val count: String get() = if (rows.size == 1) "1 αίτημα" else "${rows.size} αιτήματα"

        /**
         * «1 σε αναμονή · 1 απαντημένο» — πρώτα ό,τι θέλει προσοχή. Μόνο όσα
         * υπάρχουν: ένα «0 απορρίφθηκαν» σε κάθε κάρτα θα ήταν θόρυβος.
         */
        val statuses: String
            get() = listOfNotNull(
                when (client) { 0 -> null; 1 -> "1 θέλει ενέργεια του πελάτη"; else -> "$client θέλουν ενέργεια του πελάτη" },
                when (waiting) { 0 -> null; else -> "$waiting σε αναμονή" },
                when (rejected) { 0 -> null; 1 -> "1 απορρίφθηκε"; else -> "$rejected απορρίφθηκαν" },
                when (answered) { 0 -> null; 1 -> "1 απαντημένο"; else -> "$answered απαντημένα" },
            ).joinToString(" · ")
    }

    /**
     * Η λίστα **ανά πελάτη**: ίδια φίλτρα και ίδια ταξινόμηση με το [arrange],
     * και κάθε πελάτης στη θέση του **καλύτερου** αιτήματός του. Με «πιο
     * πρόσφατη ενημέρωση πρώτα», πρώτος βγαίνει ο πελάτης στον οποίο κινήθηκε
     * κάτι τελευταία· με «κατάσταση», αυτός που έχει κάτι να δει κανείς.
     *
     * Με φίλτρο ή αναζήτηση, κάθε ομάδα έχει **μόνο** τα αιτήματα που
     * ταιριάζουν — και ο μετρητής της μετρά αυτά.
     */
    fun groups(
        rows: List<Row>,
        sort: Sort = Sort.UPDATED,
        reversed: Boolean = false,
        filter: Filter = Filter.ALL,
        query: String = "",
    ): List<Group> = arrange(rows, sort, reversed, filter, query)
        // Το `groupBy` κρατά τη σειρά της πρώτης εμφάνισης — δηλαδή του καλύτερου αιτήματος.
        .groupBy { it.clientId }
        .values
        .map { own -> Group(own.first().clientId, own.first().clientName, own.first().afm, own) }

    /** Η ημερομηνία που λέει «πότε κινήθηκε τελευταία φορά». Το ISO ταξινομείται ως κείμενο. */
    private fun updated(row: Row): String = row.request.updated.ifBlank { row.request.submitted }

    /**
     * Τι θέλει προσοχή πρώτο: η απάντηση που δεν έχει ανοιχτεί, μετά ό,τι
     * περιμένει, μετά οι απορρίψεις, και τελευταία όσα έκλεισαν.
     */
    private fun rank(row: Row): Int = when {
        row.fresh -> 0
        !row.request.answered && !row.request.rejected -> 1
        row.request.rejected -> 2
        else -> 3
    }

    private fun matches(row: Row, needle: String): Boolean {
        val r = row.request
        return listOf(row.clientName, row.afm, r.title, r.topic, r.caseNumber, r.status, r.service)
            .any { fold(it).contains(needle) }
    }

    /** Πεζά χωρίς τόνους: «εισοδημα» βρίσκει «Εισόδημα». */
    internal fun fold(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** Ελληνική αλφαβητική σειρά: το «Άννα» πριν από το «Βασίλης», όχι στο τέλος. */
    private val GREEK: Collator = Collator.getInstance(Locale("el", "GR")).apply { strength = Collator.SECONDARY }
}
