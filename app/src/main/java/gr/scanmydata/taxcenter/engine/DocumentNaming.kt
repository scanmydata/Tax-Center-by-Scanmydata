package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.data.db.DocumentEntity

/**
 * Από όνομα αρχείου σε **όνομα εντύπου**.
 *
 * Ο πελάτης λαμβάνει ένα email με συνημμένα που λέγονται
 * `Εκκαθαριστικό_999999999_2024.pdf` και `PERIOUSIAKI_ab123456c789_2025.pdf`.
 * Το πρώτο διαβάζεται με λίγη προσπάθεια· το δεύτερο δεν διαβάζεται καθόλου, και
 * κανένα από τα δύο δεν λέει σε ποια επιλογή του λογιστή αντιστοιχεί.
 *
 * Η αντιστοίχιση γίνεται από το **πρόθεμα του ονόματος** και όχι από το
 * `configId`: ένα `aade-income` κρύβει έξι διαφορετικά έντυπα και ένα
 * `aade-general-forms` έντεκα, οπότε το config δεν ξεχωρίζει τίποτα. Το όνομα
 * του αρχείου το φτιάχνει το ίδιο το config από την ετικέτα του εντύπου, άρα
 * είναι η μόνη πληροφορία που όντως το προσδιορίζει.
 *
 * Ό,τι δεν αναγνωρίζεται επιστρέφεται **ως έχει**. Ένα άγνωστο έντυπο με το
 * τεχνικό του όνομα είναι χειρότερο από ένα με ωραίο όνομα, αλλά πολύ καλύτερο
 * από ένα λάθος όνομα.
 */
object DocumentNaming {

    /**
     * Προθέματα ονομάτων → ετικέτα. Η σειρά μετράει: τα ειδικότερα πρώτα, ώστε
     * το `Εκκαθαριστικό_συζύγου` να μη διαβαστεί ως `Εκκαθαριστικό`.
     */
    private val PREFIXES: List<Pair<String, String>> = listOf(
        "Εκκαθαριστικό_συζύγου" to "Εκκαθαριστικό συζύγου",
        "Εκκαθαριστικό" to "Εκκαθαριστικό δήλωσης",
        "ENFIA_EKK" to "ΕΝΦΙΑ — εκκαθαριστικό",
        "PERIOUSIAKI" to "Ε9 — περιουσιακή κατάσταση",
        "E1_Synopsi" to "Ε1 — συνοπτική εικόνα",
        "E3_myDATA" to "Ε3 myDATA",
        "E3myDATA" to "Ε3 myDATA",
        "E1" to "Ε1 — δήλωση φορολογίας εισοδήματος",
        "E2_Spouse" to "Ε2 συζύγου — κατάσταση μισθωμάτων",
        "E2" to "Ε2 — αναλυτική κατάσταση μισθωμάτων",
        "E3" to "Ε3 — κατάσταση οικονομικών στοιχείων",
        "STOIXEIA_FYSIKOU" to "Στοιχεία μητρώου — φυσικού προσώπου",
        "STOIXEIA_EPIXEIRISIS_NOMIKO" to "Στοιχεία μητρώου — νομικού προσώπου",
        "STOIXEIA_EPIXEIRISIS" to "Στοιχεία μητρώου — επιχείρησης",
        "MISTH" to "Μισθωτήριο",
        "FENP" to "Έντυπο Ν — ΦΕΝΠ",
        "TAXACC" to "Φορολογικός λογαριασμός",
        "TELI" to "Τέλη κυκλοφορίας",
        "KEAO" to "ΚΕΑΟ — οφειλές",
        "ATLAS" to "ΑΤΛΑΣ — ασφαλιστικό ιστορικό",
        "EFKA_EMPLOYER" to "Καρτέλα εργοδότη",
        "EFKA" to "ΕΦΚΑ — ειδοποιητήριο",
        "TEKA" to "ΤΕΚΑ — βεβαίωση",
        "Φ2" to "Φ2 — δήλωση ΦΠΑ",
        "Φ4" to "Φ4 — ανακεφαλαιωτικός πίνακας παραδόσεων",
        "Φ5" to "Φ5 — ανακεφαλαιωτικός πίνακας αποκτήσεων",
        "ΦΜΥ" to "ΦΜΥ — φόρος μισθωτών υπηρεσιών",
    )

    /** Μόνο η ετικέτα, χωρίς έτος. Κενό αν δεν αναγνωρίζεται. */
    fun label(fileName: String): String {
        val name = fileName.substringAfterLast('/').trim()
        return PREFIXES.firstOrNull { (prefix, _) ->
            name.startsWith(prefix, ignoreCase = true)
        }?.second.orEmpty()
    }

    /**
     * Το έτος όπως φαίνεται στο όνομα, όταν δεν το ξέρουμε αλλιώς.
     *
     * Παίρνεται η **τελευταία** τετραψήφια χρονιά: το όνομα μπορεί να περιέχει
     * και ΑΦΜ, και ένα ΑΦΜ ξεκινά συχνά με ψηφία που μοιάζουν με έτος.
     */
    fun yearIn(fileName: String): String =
        Regex("""(?<!\d)(19|20)\d{2}(?!\d)""").findAll(fileName)
            .lastOrNull()?.value.orEmpty()

    /**
     * Η γραμμή που βλέπει ο πελάτης: «τι είναι» και, σε παρένθεση, «ποιο αρχείο».
     *
     * Το όνομα αρχείου μένει επίτηδες: ο πελάτης το χρειάζεται για να ταιριάξει
     * τη γραμμή με το συνημμένο που κατέβασε.
     */
    fun line(fileName: String, year: String = ""): String {
        val label = label(fileName)
        if (label.isBlank()) return fileName
        val shown = year.ifBlank { yearIn(fileName) }
        return buildString {
            append(label)
            if (shown.isNotBlank()) append(' ').append(shown)
            append(" (").append(fileName).append(')')
        }
    }

    fun line(document: DocumentEntity): String = line(document.fileName, document.year)
}
