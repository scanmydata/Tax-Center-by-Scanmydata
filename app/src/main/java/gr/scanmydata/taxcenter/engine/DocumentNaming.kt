package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.data.db.DocumentEntity

/**
 * Από όνομα αρχείου σε **όνομα εντύπου**.
 *
 * Ο πελάτης λαμβάνει ένα email με συνημμένα που λέγονται
 * `TELH_KYKLOFORIAS_999999999_2026.pdf` και `PERIOUSIAKI_ab123456c789_2025.pdf`.
 * Κανένα από τα δύο δεν λέει τι είναι, ούτε σε ποια επιλογή του λογιστή
 * αντιστοιχεί.
 *
 * ## Από πού βγαίνει ο πίνακας
 *
 * **Όχι από μαντεψιά** — αυτό ήταν το λάθος της πρώτης έκδοσης, που είχε
 * `TELI` ενώ το config γράφει `TELH_KYKLOFORIAS`. Κάθε πρόθεμα εδώ είναι
 * αντιγραμμένο από τη γραμμή του config που φτιάχνει το όνομα:
 *
 * | config | τι γράφει |
 * |---|---|
 * | `aade-income` | `<ετικέτα>_<ΑΦΜ>_<έτος>.pdf` — Ε1, Ε2, Ε3, Εκκαθαριστικό… |
 * | `aade-general-forms` | `<κωδικός>_<ΑΦΜ>_<έτος>_<περίοδος>_<id>.pdf` — Φ2, ΦΜΥ… |
 * | `aade-traffic-fees` | `TELH_KYKLOFORIAS_<ΑΦΜ>_<έτος>.pdf` |
 * | `aade-tax-account` | `FOR_LOGARIASMOS_<ΑΦΜ>_<έτος>_<μήνας>.pdf` |
 * | `aade-enfia` | `ENFIA_EKK_…`, `PERIOUSIAKI_…` |
 * | `aade-registry` | `STOIXEIA_FYSIKOU_…`, `STOIXEIA_EPIXEIRISIS_[NOMIKO_]…` |
 * | `aade-debts` | `OFEILI_<ΑΦΜ>_<κατηγορία>_<ποσό>.pdf` |
 * | `aade-lease` | `MISTH_<ρόλος>_<αριθμός>_<όνομα>.pdf` |
 * | `aade-fenp` | `FENP_N_<ΑΦΜ>_<έτος>.pdf` |
 * | `efka-notices` | `EFKA_<EFKA\|TEKA>_<meta>.pdf` |
 * | `efka-teka-certificate` | `VEV_<EFKA\|TEKA>_<ΑΦΜ>_<έτος>.pdf` |
 * | `efka-employer-card` | `KARTELA_ERGODOTI_<EFKA\|TEKA>_<χρήστης>[_<έτος>].pdf` |
 * | `efka-obligations` | `KEAO_PBO_<ΑΦΜ>_<αριθμός>.pdf` |
 * | `keao-debts` | `KEAO_KARTELA_<ΑΦΜ>_<ΑΜ φορέα>.pdf` — το φτιάχνει η εφαρμογή |
 *
 * Η αντιστοίχιση γίνεται από το πρόθεμα και **όχι** από το `configId`: ένα
 * `aade-income` κρύβει οκτώ διαφορετικά έντυπα και ένα `aade-general-forms`
 * δώδεκα, οπότε το config δεν ξεχωρίζει τίποτα.
 *
 * Ό,τι δεν αναγνωρίζεται επιστρέφεται **ως έχει**. Τεχνικό όνομα είναι κακό·
 * λάθος όνομα είναι χειρότερο.
 */
object DocumentNaming {

    /**
     * @param prefix το σταθερό πρόθεμα του ονόματος, όπως το γράφει το config
     * @param label τι λέμε στον πελάτη
     * @param yearInName έχει το όνομα έτος; Όπου δεν έχει, δεν το μαντεύουμε:
     *   το `OFEILI_…_<ποσό>` τελειώνει σε αριθμό που μπορεί κάλλιστα να μοιάζει
     *   με χρονιά.
     */
    private data class Entry(
        val prefix: String,
        val label: String,
        val yearInName: Boolean = true,
    )

    /** Τα ειδικότερα προθέματα **πρώτα**: το ένα όνομα ξεκινά με το άλλο. */
    private val ENTRIES: List<Entry> = listOf(
        // ---------------------------------------------------- aade-income
        Entry("Εκκαθαριστικό_συζύγου", "Εκκαθαριστικό συζύγου"),
        Entry("Εκκαθαριστικό", "Εκκαθαριστικό δήλωσης"),
        Entry("E1_Synopsi", "Ε1 — συνοπτική εικόνα"),
        Entry("E2_Spouse", "Ε2 συζύγου — κατάσταση μισθωμάτων"),
        Entry("E3_myDATA", "Ε3 myDATA"),
        Entry("E1", "Ε1 — δήλωση φορολογίας εισοδήματος"),
        Entry("E2", "Ε2 — αναλυτική κατάσταση μισθωμάτων"),
        Entry("E3", "Ε3 — κατάσταση οικονομικών στοιχείων"),

        // ------------------------------------------------------- ακίνητα
        Entry("ENFIA_EKK", "ΕΝΦΙΑ — εκκαθαριστικό"),
        Entry("PERIOUSIAKI", "Ε9 — περιουσιακή κατάσταση"),
        Entry("MISTH", "Μισθωτήριο", yearInName = false),

        // -------------------------------------------------------- μητρώο
        Entry("STOIXEIA_EPIXEIRISIS_NOMIKO", "Στοιχεία μητρώου — νομικού προσώπου", yearInName = false),
        Entry("STOIXEIA_EPIXEIRISIS", "Στοιχεία μητρώου — επιχείρησης", yearInName = false),
        Entry("STOIXEIA_FYSIKOU", "Στοιχεία μητρώου — φυσικού προσώπου", yearInName = false),

        // ------------------------------------------------------- οφειλές
        Entry("FOR_LOGARIASMOS", "Φορολογικός λογαριασμός"),
        Entry("TELH_KYKLOFORIAS", "Τέλη κυκλοφορίας"),
        Entry("OFEILI", "Ταυτότητα οφειλής", yearInName = false),
        Entry("KEAO_PBO", "ΚΕΑΟ — πράξη βεβαίωσης οφειλής", yearInName = false),
        Entry("KEAO_KARTELA", "ΚΕΑΟ — καρτέλα οφειλέτη ανά φορέα", yearInName = false),

        // Τα δύο του `aade-eservices`. Το config ήρθε με τον συγχρονισμό του
        // engine και δεν προσφέρεται ακόμη στην οθόνη λήψης — οι ετικέτες όμως
        // μπαίνουν τώρα: αν προστεθεί αύριο, δεν θα φύγει σκέτο όνομα αρχείου
        // σε πελάτη επειδή κάποιος ξέχασε αυτό το σημείο.
        Entry("LOTARIA", "Φορολοταρία — βεβαίωση", yearInName = false),
        Entry("EPISTREPTEA", "Επιστρεπτέα προκαταβολή — βεβαίωση", yearInName = false),

        // ------------------------------------------------------ ασφάλιση
        Entry("KARTELA_ERGODOTI_TEKA", "Καρτέλα εργοδότη — ΤΕΚΑ"),
        Entry("KARTELA_ERGODOTI_EFKA", "Καρτέλα εργοδότη — ΕΦΚΑ"),
        Entry("KARTELA_ERGODOTI", "Καρτέλα εργοδότη"),
        Entry("VEV_TEKA", "Φορολογική βεβαίωση ΤΕΚΑ"),
        Entry("VEV_EFKA", "Φορολογική βεβαίωση ΕΦΚΑ"),
        Entry("VEV", "Φορολογική βεβαίωση"),
        Entry("EFKA_TEKA", "Ειδοποιητήριο ΤΕΚΑ", yearInName = false),
        Entry("EFKA_EFKA", "Ειδοποιητήριο ΕΦΚΑ", yearInName = false),
        Entry("EFKA", "Ειδοποιητήριο ΕΦΚΑ", yearInName = false),
        Entry("ATLAS", "ΑΤΛΑΣ — ασφαλιστικό ιστορικό", yearInName = false),

        // -------------------------------------------- λοιπά έντυπα ΑΑΔΕ
        Entry("FENP_N", "Έντυπο Ν — ΦΕΝΠ"),
        Entry("ΦΜΥ", "ΦΜΥ — φόρος μισθωτών υπηρεσιών"),
        Entry("Φ2", "Φ2 — δήλωση ΦΠΑ"),
        Entry("Φ4", "Φ4 — ανακεφαλαιωτικός πίνακας παραδόσεων"),
        Entry("Φ5", "Φ5 — ανακεφαλαιωτικός πίνακας αποκτήσεων"),
        Entry("ΕΠΙΧ", "Αμοιβές επιχειρηματικής δραστηριότητας"),
        Entry("ΜΕΡΙΣΜΑΤΑ", "Παρακρατούμενος φόρος μερισμάτων"),
        Entry("ΤΟΚΟΙ", "Παρακρατούμενος φόρος τόκων"),
        Entry("ΔΙΚΑΙΩΜΑΤΑ", "Παρακρατούμενος φόρος δικαιωμάτων"),
        Entry("ΕΡΓΟΛΑΒΩΝ", "Εργολάβων (Φ01-019)"),
        Entry("ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ", "Τέλος ανθεκτικότητας / διαμονής"),
        Entry("ΠΕΡΙΒΑΛΛΟΝ", "Περιβαλλοντικό τέλος"),
        Entry("ΣΥΜΦΩΝΗΤΙΚΑ", "Κατάσταση συμφωνητικών"),
    )

    private fun entryFor(fileName: String): Entry? {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        return ENTRIES.firstOrNull { name.startsWith(it.prefix, ignoreCase = true) }
    }

    /** Μόνο η ετικέτα, χωρίς έτος. Κενό αν δεν αναγνωρίζεται. */
    fun label(fileName: String): String = entryFor(fileName)?.label.orEmpty()

    /**
     * Το έτος όπως φαίνεται στο όνομα.
     *
     * Δουλεύει σε **ολόκληρα κομμάτια** του ονόματος και όχι με ελεύθερη
     * αναζήτηση: ένα `OFEILI_…_2024_00` (ποσό 2024,00) δεν είναι έτος, και ένας
     * ΑΦΜ μπορεί να ξεκινά με ψηφία που μοιάζουν με χρονιά.
     */
    fun yearIn(fileName: String): String {
        val entry = entryFor(fileName)
        if (entry != null && !entry.yearInName) return ""
        return fileName.substringBeforeLast('.')
            .split('_', '-', ' ')
            .lastOrNull { it.length == 4 && it.toIntOrNull()?.let { y -> y in 1990..2100 } == true }
            .orEmpty()
    }

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
