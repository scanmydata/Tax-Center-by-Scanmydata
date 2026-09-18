package gr.scanmydata.taxcenter.doc

/**
 * Το **περιεχόμενο** ενός εντύπου που φτιάχνει η εφαρμογή, χωρίς μία γραμμή
 * σχεδίασης.
 *
 * Γεννήθηκε στην καρτέλα ΚΕΑΟ και μετακόμισε εδώ όταν χρειάστηκε το ίδιο πράγμα
 * για τις οφειλές της ΑΑΔΕ: τίτλος, στοιχεία υπόχρεου, σύνοψη, ενότητες με
 * πίνακες, υποσέλιδο. Δύο σχεδιαστές για την ίδια δομή θα απέκλιναν με την
 * πρώτη διόρθωση, και η απόκλιση θα φαινόταν μόνο στο PDF που θα είχε ήδη φύγει
 * στον πελάτη.
 *
 * Ο κανόνας μένει αυτός που ίσχυε από την αρχή: **εδώ κρίνεται τι λέει το
 * έντυπο, στο [ReportPdf] μόνο πού μπαίνει**. Έτσι όλο το κομμάτι που μπορεί να
 * είναι λάθος ελέγχεται με τεστ χωρίς Android.
 */
data class Table(
    val headers: List<String>,
    val rows: List<List<String>>,
    /**
     * Αναλογίες πλάτους στηλών· κανονικοποιούνται κατά τη σχεδίαση. **Κενές**
     * σημαίνει «βγάλ' τα από το περιεχόμενο»: όπου τις στήλες τις ονομάζει η
     * πύλη και όχι εμείς, δεν ξέρουμε εκ των προτέρων ούτε πόσες είναι ούτε πόσο
     * μακριά είναι τα ονόματά τους.
     */
    val weights: List<Float> = emptyList(),
    /** Γραμμή συνόλων, αν έχει νόημα. Τυπώνεται έντονη και χωριστά. */
    val totals: List<String> = emptyList(),
    /**
     * Στήλες με ποσά: στοιχίζονται δεξιά, ώστε οι υποδιαστολές να μπαίνουν η μία
     * κάτω από την άλλη και η γραμμή των συνόλων να διαβάζεται σαν άθροισμα.
     */
    val numeric: List<Int> = emptyList(),
)

/**
 * Ένα κομμάτι του εντύπου: τίτλος, ζεύγη ετικέτα-τιμή, και προαιρετικά ένας
 * πίνακας. Τα τρία μαζί καλύπτουν όσα δείχνουν οι πύλες, χωρίς να χρειάζεται ο
 * σχεδιαστής να ξέρει τι σημαίνει το καθένα.
 */
data class Section(
    val caption: String,
    val facts: List<Pair<String, String>> = emptyList(),
    val table: Table? = null,
    val note: String = "",
)

data class Report(
    val fileName: String,
    val title: String,
    val office: String = "",
    val identity: List<Pair<String, String>> = emptyList(),
    /**
     * Ο κωδικός πληρωμής — μπαίνει σε πλαίσιο, μόνος του, με μεγάλα γράμματα.
     * Κενός σημαίνει «δεν υπάρχει πλαίσιο»: σε μια σελίδα που προσαρτάται πίσω
     * από επίσημο έντυπο, η ταυτότητα είναι ήδη τυπωμένη στην πρώτη σελίδα και
     * μια δεύτερη εμφάνισή της δεν προσθέτει τίποτα.
     */
    val debtorId: String = "",
    val idCaption: String = "ΤΑΥΤΟΤΗΤΑ ΟΦΕΙΛΕΤΗ",
    val summary: List<Pair<String, String>> = emptyList(),
    val sections: List<Section> = emptyList(),
    val footer: List<String> = emptyList(),
)

/**
 * Ελληνικά ποσά, και προς τις δύο κατευθύνσεις.
 *
 * Χτίζονται στο χέρι και όχι με `NumberFormat`: η μορφή πρέπει να είναι ίδια στη
 * συσκευή και στα τεστ, και το locale της συσκευής δεν είναι δεδομένο. Ένα
 * μπερδεμένο κόμμα σε έντυπο προς πελάτη είναι λάθος κατά χίλια.
 */
object Money {

    /**
     * «1.234,56» σε αριθμό. Οι πύλες δίνουν ελληνική μορφή, συχνά με «€» και
     * αδιάσπαστα κενά· ό,τι δεν διαβάζεται μετρά ως μηδέν, γιατί ένα σύνολο που
     * λείπει είναι λιγότερο επικίνδυνο από ένα σύνολο λάθος κατά χίλια.
     */
    fun amount(raw: String): Double {
        val clean = raw
            .replace(".", "")
            .replace("€", "")
            .filter { !it.isWhitespace() && it != ' ' }
            .replace(",", ".")
        return clean.toDoubleOrNull() ?: 0.0
    }

    fun sum(values: List<String>): Double = values.sumOf { amount(it) }

    /** Αριθμός σε «1.234,56». */
    fun money(value: Double): String {
        val cents = Math.round(value * 100)
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val whole = (abs / 100).toString()
        val frac = (abs % 100).toString().padStart(2, '0')
        val grouped = whole.reversed().chunked(3).joinToString(".").reversed()
        return "$sign$grouped,$frac"
    }

    /** Γραμμή συνόλων για στήλες ποσών — η ίδια σε κάθε πίνακα του εντύπου. */
    fun totals(label: String, blanks: Int, columns: List<List<String>>): List<String> =
        listOf(label) + List(blanks) { "" } + columns.map { money(sum(it)) }
}
