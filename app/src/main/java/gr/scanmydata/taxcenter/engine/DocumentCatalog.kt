package gr.scanmydata.taxcenter.engine

import gr.scanmydata.taxcenter.data.ClientKind

/**
 * Ο κατάλογος **εγγράφων**, ένα επίπεδο πάνω από τον κατάλογο διαδικασιών.
 *
 * Τα configs είναι γραμμένα ανά *πύλη και ροή*, όχι ανά έντυπο: το
 * `aade-income` κατεβάζει Ε1, Ε2, Ε3, Ε3-myDATA και Εκκαθαριστικό ανάλογα με το
 * input `forms`, και το `aade-general-forms` κρύβει έντεκα διαφορετικά έντυπα
 * πίσω από το input `form`. Σωστό για τον engine — άχρηστο για τον λογιστή, που
 * δεν σκέφτεται «τρέξε το aade-income με forms=E2» αλλά «θέλω το Ε2».
 *
 * Εδώ γίνεται η μετάφραση. Κάθε [Item] είναι **ένα έντυπο** και ξέρει μόνο του
 * ποια διαδικασία το φέρνει και με ποια σταθερά inputs.
 *
 * Το πρακτικό κέρδος πέρα από τα ονόματα: το έτος γίνεται ιδιότητα της
 * *επιλογής* και όχι της παρτίδας. «Ε1 του 2025 και Ε9 του 2027 στην ίδια
 * εκτέλεση» είναι φυσιολογικό αίτημα και μέχρι τώρα ήταν αδύνατο.
 *
 * Τα Φ2/Φ4/Φ5 δρομολογούνται στο `aade-general-forms` και όχι στο
 * `aade-declarations`: οι δύο διαδικασίες υλοποιούν **την ίδια** ροή
 * (`GetGeneralType1Year`) με τους ίδιους filters, αλλά η πρώτη καλύπτει και τα
 * υπόλοιπα δέκα έντυπα. Δύο εγγραφές για το ίδιο PDF θα μπέρδευαν μόνο.
 */
object DocumentCatalog {

    /** Σε ποιους υπόχρεους έχει νόημα το έντυπο. */
    enum class Applies { ALL, LEGAL_ONLY, NATURAL_ONLY }

    data class Item(
        val id: String,
        val label: String,
        val group: String,
        val configId: String,
        /** Σταθερά inputs που ξεχωρίζουν αυτό το έντυπο μέσα στη διαδικασία. */
        val inputs: Map<String, String> = emptyMap(),
        val needsYear: Boolean = false,
        /** Δέχεται μήνα 1-12 (σήμερα μόνο ο Φορολογικός Λογαριασμός). */
        val needsMonth: Boolean = false,
        val applies: Applies = Applies.ALL,
        /** Παράγει PDF προς αποστολή· αλλιώς ενημερώνει την καρτέλα. */
        val producesDocuments: Boolean = true,
        val note: String = "",
    ) {
        /**
         * Ισχύει για αυτό το είδος υπόχρεου;
         *
         * Άγνωστο ή κενό είδος περνά: στην αμφιβολία δοκιμάζουμε, γιατί μια
         * σιωπηλή παράλειψη είναι χειρότερη από μια αποτυχία που εξηγείται.
         */
        fun matches(kind: String): Boolean {
            val normalised = ClientKind.normalise(kind)
            // Κενό ή άγνωστο είδος (π.χ. «Κοινοπραξία») δεν αποκλείεται: δεν
            // ξέρουμε αρκετά για να πούμε όχι, και η πύλη θα το πει καθαρά.
            if (normalised !in ClientKind.ALL) return true
            return when (applies) {
                Applies.ALL -> true
                Applies.LEGAL_ONLY -> normalised == ClientKind.LEGAL
                Applies.NATURAL_ONLY -> normalised != ClientKind.LEGAL
            }
        }
    }

    const val GROUP_INCOME = "Εισόδημα"
    const val GROUP_VAT = "ΦΠΑ"
    const val GROUP_WITHHOLDING = "Παρακρατούμενοι φόροι"
    const val GROUP_OTHER_FORMS = "Λοιπά έντυπα"
    const val GROUP_PROPERTY = "Ακίνητα"
    const val GROUP_DEBTS = "Οφειλές & λογαριασμός"
    const val GROUP_INSURANCE = "Ασφάλιση (ΕΦΚΑ)"
    const val GROUP_EMPLOYER = "Εργοδότης"
    const val GROUP_REGISTRY = "Μητρώο"
    const val GROUP_CARD = "Ενημέρωση καρτέλας"

    /** Η σειρά των ομάδων στην οθόνη. */
    val GROUPS = listOf(
        GROUP_INCOME, GROUP_VAT, GROUP_WITHHOLDING, GROUP_OTHER_FORMS,
        GROUP_PROPERTY, GROUP_DEBTS, GROUP_INSURANCE, GROUP_EMPLOYER,
        GROUP_REGISTRY, GROUP_CARD,
    )

    val ALL: List<Item> = listOf(

        // ---------------------------------------------------------- εισόδημα
        Item("e1", "Ε1 — Δήλωση φορολογίας εισοδήματος", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E1"), needsYear = true),
        Item("e2", "Ε2 — Αναλυτική κατάσταση μισθωμάτων", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E2"), needsYear = true),
        Item("e3", "Ε3 — Κατάσταση οικονομικών στοιχείων", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E3"), needsYear = true),
        Item("e3-mydata", "Ε3 myDATA", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E3MYDATA"), needsYear = true),
        Item("ekkatharistiko", "Εκκαθαριστικό (πράξη διοικητικού προσδιορισμού)", GROUP_INCOME,
            "aade-income", mapOf("forms" to "EKK"), needsYear = true),
        Item("fenp", "Έντυπο Ν — ΦΕΝΠ (νομικά πρόσωπα)", GROUP_INCOME,
            "aade-fenp", needsYear = true, applies = Applies.LEGAL_ONLY),

        // --------------------------------------------------------------- ΦΠΑ
        Item("f2", "Φ2 — Δήλωση ΦΠΑ", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ2"), needsYear = true),
        Item("f4", "Φ4 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών παραδόσεων", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ4"), needsYear = true),
        Item("f5", "Φ5 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών αποκτήσεων", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ5"), needsYear = true),

        // ------------------------------------------------- παρακρατούμενοι
        Item("fmy", "ΦΜΥ — Φόρος μισθωτών υπηρεσιών", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΦΜΥ"), needsYear = true),
        Item("epix", "Αμοιβές επιχειρηματικής δραστηριότητας", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΕΠΙΧ"), needsYear = true),
        Item("merismata", "Μερίσματα", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΜΕΡΙΣΜΑΤΑ"), needsYear = true),
        Item("tokoi", "Τόκοι", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΤΟΚΟΙ"), needsYear = true),
        Item("dikaiomata", "Δικαιώματα", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΔΙΚΑΙΩΜΑΤΑ"), needsYear = true),
        Item("ergolavon", "Εργολάβων (Φ01-019)", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΕΡΓΟΛΑΒΩΝ"), needsYear = true),

        // ------------------------------------------------------ λοιπά έντυπα
        Item("anthektikotitas", "Τέλος ανθεκτικότητας / διαμονής", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ"), needsYear = true),
        Item("perivallon", "Περιβαλλοντικό τέλος", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΠΕΡΙΒΑΛΛΟΝ"), needsYear = true),
        Item("symfonitika", "Κατάσταση συμφωνητικών", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΣΥΜΦΩΝΗΤΙΚΑ"), needsYear = true),

        // ---------------------------------------------------------- ακίνητα
        Item("enfia", "ΕΝΦΙΑ — Εκκαθαριστικό", GROUP_PROPERTY,
            "aade-enfia", mapOf("e9" to "όχι"), needsYear = true,
            note = "Χρειάζεται ορατό browser· ίσως ζητηθεί κωδικός μιας χρήσης."),
        Item("e9", "Ε9 / Περιουσιακή κατάσταση (ETAK)", GROUP_PROPERTY,
            "aade-enfia", mapOf("e9" to "ναι"), needsYear = true,
            note = "Χρειάζεται ορατό browser· ίσως ζητηθεί κωδικός μιας χρήσης."),
        Item("property", "Περιουσιακή κατάσταση (myPROPERTY)", GROUP_PROPERTY,
            "aade-property", needsYear = true),
        Item("lease", "Μισθωτήρια — πληροφοριακά στοιχεία μισθώσεων", GROUP_PROPERTY,
            "aade-lease"),

        // ----------------------------------------------------------- οφειλές
        Item("debts", "Οφειλές & ταυτότητες οφειλής", GROUP_DEBTS,
            "aade-debts", mapOf("doseis" to "ναι")),
        Item("tax-account", "Φορολογικός λογαριασμός (μηνιαία ενημέρωση)", GROUP_DEBTS,
            "aade-tax-account", needsYear = true, needsMonth = true),
        Item("traffic-fees", "Τέλη κυκλοφορίας (myCAR)", GROUP_DEBTS,
            "aade-traffic-fees", needsYear = true),

        // --------------------------------------------------------- ασφάλιση
        Item("efka-notices", "Ειδοποιητήρια εισφορών ΕΦΚΑ/ΤΕΚΑ", GROUP_INSURANCE,
            "efka-notices", applies = Applies.NATURAL_ONLY),
        Item("efka-certificate", "Φορολογικές βεβαιώσεις ΕΦΚΑ/ΤΕΚΑ", GROUP_INSURANCE,
            "efka-teka-certificate", needsYear = true, applies = Applies.NATURAL_ONLY),
        Item("keao", "Οφειλές ΚΕΑΟ + ΠΒΟ", GROUP_INSURANCE,
            "keao-debts", mapOf("pdf" to "ναι"), applies = Applies.NATURAL_ONLY),
        Item("atlas", "Ασφαλιστικό / εργασιακό ιστορικό (ΑΤΛΑΣ)", GROUP_INSURANCE,
            "atlas-insurance-history", applies = Applies.NATURAL_ONLY),

        // --------------------------------------------------------- εργοδότης
        Item("employer-efka", "Οικονομική καρτέλα εργοδότη — ΕΦΚΑ", GROUP_EMPLOYER,
            "efka-employer-card", mapOf("which" to "EFKA"), needsYear = true,
            note = "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),
        Item("employer-teka", "Οικονομική καρτέλα εργοδότη — ΤΕΚΑ", GROUP_EMPLOYER,
            "efka-employer-card", mapOf("which" to "TEKA"), needsYear = true,
            note = "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),

        // ----------------------------------------------------------- μητρώο
        Item("registry", "Στοιχεία μητρώου (PDF)", GROUP_REGISTRY, "aade-registry"),

        // ------------------------------------------------- ενημέρωση καρτέλας
        Item("profile", "Άντληση ονοματεπωνύμου, ΔΟΥ και είδους", GROUP_CARD,
            "aade-profile", producesDocuments = false),
        Item("email", "Ενημέρωση email από το Μητρώο Επικοινωνίας", GROUP_CARD,
            "aade-email", producesDocuments = false),
        Item("amka", "Άντληση ΑΜΚΑ (MyAMKA)", GROUP_CARD,
            "amka-retrieve", producesDocuments = false, applies = Applies.NATURAL_ONLY),
    )

    fun byId(id: String): Item? = ALL.firstOrNull { it.id == id }

    /** Τα έντυπα μιας ομάδας, με τη σειρά που δηλώθηκαν. */
    fun inGroup(group: String): List<Item> = ALL.filter { it.group == group }
}
