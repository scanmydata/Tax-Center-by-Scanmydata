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

    /**
     * Καθαρίζει ένα **μυστικό** (κωδικό, όνομα χρήστη, κλειδάριθμο) από κενά
     * στις άκρες.
     *
     * Στο Android αυτό δεν είναι θεωρητικό. Το πληκτρολόγιο βάζει κενό μετά από
     * κάθε πρόταση autocomplete, και η επικόλληση από email ή από το Excel
     * φέρνει μαζί το κενό της στήλης. Το αποτέλεσμα είναι μια καρτέλα που
     * φαίνεται σωστή, κωδικός που φαίνεται σωστός, και σύνδεση που αποτυγχάνει
     * με «λάθος κωδικός» χωρίς να φαίνεται γιατί.
     *
     * Το `trim()` του Kotlin καλύπτει ήδη τα κανονικά κενά **και το NBSP** — σε
     * αντίθεση με το `String.trim()` της Java, που σταματά στο `U+0020`. Αυτό
     * που δεν καλύπτει είναι οι χαρακτήρες μηδενικού πλάτους και το BOM
     * ([INVISIBLE]): δεν είναι κενά κατά Unicode, δεν φαίνονται πουθενά, και
     * ταξιδεύουν με κάθε επικόλληση από ιστοσελίδα ή αρχείο.
     *
     * Κόβονται **μόνο οι άκρες**: ένα κενό στη μέση μπορεί κάλλιστα να ανήκει
     * στον κωδικό, και δεν είναι δική μας δουλειά να το κρίνουμε.
     */
    fun secret(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.trim { ch ->
            ch.isWhitespace() || ch in INVISIBLE
        }
    }

    /**
     * Αόρατοι χαρακτήρες που το `Char.isWhitespace()` **δεν** αναγνωρίζει.
     *
     * Είναι κατηγορίας `Cf` (format) και όχι `Zs` (space): για το Unicode δεν
     * είναι κενά, οπότε κανένα `trim()` δεν τους αγγίζει. Το NBSP **δεν** είναι
     * εδώ — το `trim()` του Kotlin το πιάνει ήδη.
     */
    private val INVISIBLE = charArrayOf(
        '\u200B', '\u200C', '\u200D', // zero-width space / non-joiner / joiner
        '\uFEFF', // BOM, όταν η επικόλληση ξεκινά από αρχείο
    )

    /**
     * Καθαρίζει διεύθυνση email: κενά, εισαγωγικά και πεζά.
     *
     * Το τοπικό μέρος είναι θεωρητικά case-sensitive, στην πράξη όμως κανένας
     * πάροχος δεν το εκμεταλλεύεται και τα πεζά αποτρέπουν διπλοεγγραφές.
     */
    fun email(raw: String?): String =
        secret(raw).trim('<', '>', '"', '\'', ',', ';').trim().lowercase()

    /**
     * Στοιχειώδης έλεγχος διεύθυνσης — **όχι** RFC 5322.
     *
     * Σκοπός είναι να πιάσει το δακτυλογραφικό λάθος πριν φύγει φορολογικό
     * έντυπο σε λάθος παραλήπτη· δεν κρίνει αν το γραμματοκιβώτιο υπάρχει.
     */
    fun validEmail(raw: String?): Boolean {
        val value = email(raw)
        if (value.length !in 6..254) return false
        if (value.any { it.isWhitespace() }) return false
        val at = value.indexOf('@')
        if (at <= 0 || at != value.lastIndexOf('@')) return false
        val domain = value.substring(at + 1)
        if (domain.length < 4 || !domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return false
        return domain.substringAfterLast('.').length >= 2
    }

    private val DOTS = Regex("[.·]")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s{2,}")

    /** Τόνοι, διαλυτικά και τελικό σίγμα. */
    private val ACCENTS: Map<Char, Char> = mapOf(
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο', 'ύ' to 'υ', 'ώ' to 'ω',
        'ϊ' to 'ι', 'ϋ' to 'υ', 'ΐ' to 'ι', 'ΰ' to 'υ', 'ς' to 'σ',
    )
}
