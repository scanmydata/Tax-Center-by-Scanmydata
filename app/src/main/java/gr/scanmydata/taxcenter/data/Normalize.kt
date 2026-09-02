package gr.scanmydata.taxcenter.data

/**
 * Κανονικοποίηση τιμών από τα Excel των λογιστικών προγραμμάτων.
 *
 * Η λογική είναι αντιγραμμένη από το `timologio-downloader`, όπου έχει ήδη
 * δοκιμαστεί σε πραγματικά exports (Epsilon Hyper/Extra και TaxSystem).
 */
object Normalize {

    /**
     * Κανονικοποιεί μια επικεφαλίδα στήλης για σύγκριση.
     *
     * Η σειρά των βημάτων έχει σημασία: οι **τελείες σβήνονται** (δεν γίνονται
     * κενά), ώστε το `Α.Φ.Μ.` να γίνει `αφμ` και όχι `α φ μ`.
     */
    fun header(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val noDots = raw.lowercase().replace(DOTS, "")
        val folded = fold(noDots)
        return folded.replace(NON_ALNUM, " ").trim().replace(SPACES, " ")
    }

    /** Πεζά, χωρίς τόνους, με τελικό σίγμα σε σίγμα. */
    fun fold(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) sb.append(ACCENTS[ch] ?: ch)
        return sb.toString()
    }

    /**
     * Εξάγει ΑΦΜ 9 ψηφίων.
     *
     * Το Excel συχνά αποθηκεύει το ΑΦΜ ως αριθμό: τότε εμφανίζεται ως `9.99999E8`
     * ή `999999999.0`, και ένα ΑΦΜ που αρχίζει από μηδέν χάνει το πρώτο ψηφίο.
     * Και τα δύο διορθώνονται εδώ.
     */
    fun afm(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim().removeSuffix(".0")
        val digits = trimmed.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> ""
            digits.length == 8 -> "0$digits"          // το Excel έφαγε το αρχικό μηδέν
            digits.length > 9 -> digits.takeLast(9)
            else -> digits
        }
    }

    /**
     * Έλεγχος ψηφίου ελέγχου ΑΦΜ (mod 11).
     *
     * **Συμβουλευτικός μόνο.** Ένα ΑΦΜ που δεν περνά σημαίνεται στο preview αλλά
     * ΔΕΝ απορρίπτεται: υπάρχουν παλιά ή ειδικά ΑΦΜ στα μητρώα, και το να αρνηθεί
     * η εφαρμογή να εισαγάγει έναν πελάτη είναι χειρότερο από μια προειδοποίηση.
     */
    fun validAfm(afm: String?): Boolean {
        if (afm == null || afm.length != 9 || !afm.all { it.isDigit() }) return false
        if (afm == "000000000") return false
        var sum = 0
        for (i in 0..7) {
            sum += (afm[i] - '0') shl (8 - i)
        }
        return (sum % 11) % 10 == (afm[8] - '0')
    }

    /** ΑΜΚΑ: 11 ψηφία, με στοιχειώδη έλεγχο ημερομηνίας γέννησης στην αρχή. */
    fun amka(raw: String?): String {
        val digits = raw?.trim()?.removeSuffix(".0")?.filter { it.isDigit() } ?: return ""
        return if (digits.length == 11) digits else ""
    }

    fun validAmka(amka: String?): Boolean {
        if (amka == null || amka.length != 11 || !amka.all { it.isDigit() }) return false
        val day = amka.substring(0, 2).toInt()
        val month = amka.substring(2, 4).toInt()
        return day in 1..31 && month in 1..12
    }

    /** Το myDATA subscription key είναι ακριβώς 32 δεκαεξαδικά. */
    fun validSubscriptionKey(key: String?): Boolean =
        key != null && key.length == 32 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private val DOTS = Regex("[.·]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s{2,}")

    /** Τόνοι, διαλυτικά και τελικό σίγμα. */
    private val ACCENTS: Map<Char, Char> = mapOf(
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο', 'ύ' to 'υ', 'ώ' to 'ω',
        'ϊ' to 'ι', 'ϋ' to 'υ', 'ΐ' to 'ι', 'ΰ' to 'υ', 'ς' to 'σ',
    )
}
