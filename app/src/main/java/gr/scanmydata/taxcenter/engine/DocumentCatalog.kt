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

    /**
     * Σε ποιους υπόχρεους έχει νόημα το έντυπο.
     *
     * Το [BUSINESS_ONLY] είναι η κατηγορία που έλειπε. Ο **ιδιώτης** — φυσικό
     * πρόσωπο χωρίς επιχειρηματική δραστηριότητα — δεν υποβάλλει Ε3, ούτε
     * δηλώσεις ΦΠΑ, ούτε παρακρατούμενους φόρους: δεν έχει βιβλία. Μέχρι τώρα
     * ο κατάλογος του τα πρόσφερε όλα, και τα ζητούσε από την πύλη, που
     * γυρνούσε άδειο χωρίς εξήγηση.
     *
     * Οι διαδικασίες ΕΦΚΑ/ΑΤΛΑΣ/ΚΕΑΟ μένουν [NATURAL_ONLY] και **όχι**
     * [BUSINESS_ONLY]: κρέμονται από τον ΑΜΚΑ, όχι από τα βιβλία. Ένας ιδιώτης
     * μπορεί κάλλιστα να έχει ασφαλιστικό ιστορικό ή παλιά οφειλή στο ΚΕΑΟ, και
     * το να του τα κρύψουμε θα ήταν χειρότερο λάθος από το να του τα δείξουμε.
     */
    enum class Applies { ALL, LEGAL_ONLY, NATURAL_ONLY, BUSINESS_ONLY }

    data class Item(
        val id: String,
        val label: String,
        val group: String,
        val configId: String,
        /** Σταθερά inputs που ξεχωρίζουν αυτό το έντυπο μέσα στη διαδικασία. */
        val inputs: Map<String, String> = emptyMap(),
        val needsYear: Boolean = false,
        /**
         * Δέχεται **όλα** τα έτη σε μία εκτέλεση, μέσω input `years`.
         *
         * Ισχύει σήμερα μόνο για το ETAK (ΕΝΦΙΑ/Ε9), και για σοβαρό λόγο: εκεί
         * η σύνδεση είναι πραγματικός browser με GSIS OAuth, και μία σύνδεση
         * ανά έτος σημαίνει τρεις συνεδρίες GSIS για τρία έτη — δηλαδή
         * ακριβώς η συνθήκη που φέρνει το κλείδωμα OAM-6. Μέσα στην ίδια
         * συνεδρία η αλλαγή έτους είναι ένα `select`.
         */
        val batchYears: Boolean = false,
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
                Applies.BUSINESS_ONLY -> normalised != ClientKind.PRIVATE
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

    /**
     * Η σειρά των ομάδων **στον κατάλογο επιλογής**.
     *
     * Το [GROUP_CARD] λείπει επίτηδες. Η ενημέρωση καρτέλας δεν είναι έντυπο
     * και δεν είναι πια χειροκίνητη επιλογή: γίνεται μόνη της από την «Άντληση
     * στοιχείων» της καρτέλας, που φέρνει ονοματεπώνυμο, ΔΟΥ, είδος, email
     * και — σε ιδιώτη ή ατομική — τον ΑΜΚΑ. Το να προσφέρεται και εδώ έδινε
     * δύο δρόμους για το ίδιο πράγμα, με διαφορετικό αποτέλεσμα ο καθένας.
     *
     * Τα ίδια τα [Item] μένουν στο [ALL]: τα χρησιμοποιεί ο κώδικας άντλησης.
     */
    val GROUPS = listOf(
        GROUP_INCOME, GROUP_VAT, GROUP_WITHHOLDING, GROUP_OTHER_FORMS,
        GROUP_PROPERTY, GROUP_DEBTS, GROUP_INSURANCE, GROUP_EMPLOYER,
        GROUP_REGISTRY,
    )

    val ALL: List<Item> = listOf(

        // ---------------------------------------------------------- εισόδημα
        Item("e1", "Ε1 — Δήλωση φορολογίας εισοδήματος", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E1"), needsYear = true),
        Item("e2", "Ε2 — Αναλυτική κατάσταση μισθωμάτων", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E2"), needsYear = true),
        Item("e3", "Ε3 — Κατάσταση οικονομικών στοιχείων", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E3"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("e3-mydata", "Ε3 myDATA", GROUP_INCOME,
            "aade-income", mapOf("forms" to "E3MYDATA"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("ekkatharistiko", "Εκκαθαριστικό (πράξη διοικητικού προσδιορισμού)", GROUP_INCOME,
            "aade-income", mapOf("forms" to "EKK"), needsYear = true),
        // Τυπώνεται με τους κωδικούς του **ίδιου** του υπόχρεου: η κοινή δήλωση
        // δίνει και τα δύο εκκαθαριστικά. Δεν χρειάζονται κωδικοί συζύγου, και
        // δεν ζητούνται. Σε άγαμο υπόχρεο το κουμπί λείπει από τη σελίδα και η
        // διαδικασία το προσπερνά μόνη της.
        Item("ekkatharistiko-syz", "Εκκαθαριστικό συζύγου", GROUP_INCOME,
            "aade-income", mapOf("forms" to "EKK_SYZ"), needsYear = true,
            applies = Applies.NATURAL_ONLY,
            note = "Από την κοινή δήλωση, με τους κωδικούς του υπόχρεου."),
        Item("fenp", "Έντυπο Ν — ΦΕΝΠ (νομικά πρόσωπα)", GROUP_INCOME,
            "aade-fenp", needsYear = true, applies = Applies.LEGAL_ONLY),

        // --------------------------------------------------------------- ΦΠΑ
        Item("f2", "Φ2 — Δήλωση ΦΠΑ", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ2"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("f4", "Φ4 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών παραδόσεων", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ4"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("f5", "Φ5 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών αποκτήσεων", GROUP_VAT,
            "aade-general-forms", mapOf("form" to "Φ5"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),

        // ------------------------------------------------- παρακρατούμενοι
        Item("fmy", "ΦΜΥ — Φόρος μισθωτών υπηρεσιών", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΦΜΥ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("epix", "Αμοιβές επιχειρηματικής δραστηριότητας", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΕΠΙΧ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("merismata", "Μερίσματα", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΜΕΡΙΣΜΑΤΑ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("tokoi", "Τόκοι", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΤΟΚΟΙ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("dikaiomata", "Δικαιώματα", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΔΙΚΑΙΩΜΑΤΑ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("ergolavon", "Εργολάβων (Φ01-019)", GROUP_WITHHOLDING,
            "aade-general-forms", mapOf("form" to "ΕΡΓΟΛΑΒΩΝ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),

        // ------------------------------------------------------ λοιπά έντυπα
        Item("anthektikotitas", "Τέλος ανθεκτικότητας / διαμονής", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("perivallon", "Περιβαλλοντικό τέλος", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΠΕΡΙΒΑΛΛΟΝ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),
        Item("symfonitika", "Κατάσταση συμφωνητικών", GROUP_OTHER_FORMS,
            "aade-general-forms", mapOf("form" to "ΣΥΜΦΩΝΗΤΙΚΑ"), needsYear = true,
            applies = Applies.BUSINESS_ONLY),

        // ---------------------------------------------------------- ακίνητα
        // Το ETAK δίνει **πάντα** την τελευταία εκκαθάριση, ό,τι έτος κι αν
        // επιλεγεί (επαληθεύτηκε ζωντανά: επιλογή 2025 -> PDF 2022). Το έτος
        // κρίνει μόνο αν προσφέρεται καθόλου ο σύνδεσμος.
        Item("enfia", "ΕΝΦΙΑ — Εκκαθαριστικό", GROUP_PROPERTY,
            "aade-enfia", mapOf("e9" to "όχι"), needsYear = true, batchYears = true,
            note = "Δίνει πάντα την τελευταία εκκαθάριση — το έτος του PDF είναι " +
                "στο όνομα του αρχείου. Ορατός browser· ίσως ζητηθεί κωδικός μιας χρήσης."),
        Item("e9", "Ε9 / Περιουσιακή κατάσταση (ETAK)", GROUP_PROPERTY,
            "aade-enfia", mapOf("e9" to "ναι"), needsYear = true, batchYears = true,
            note = "Πολλά έτη κατεβαίνουν με μία σύνδεση. Ορατός browser· ίσως " +
                "ζητηθεί κωδικός μιας χρήσης."),
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
            applies = Applies.BUSINESS_ONLY,
            note = "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),
        Item("employer-teka", "Οικονομική καρτέλα εργοδότη — ΤΕΚΑ", GROUP_EMPLOYER,
            "efka-employer-card", mapOf("which" to "TEKA"), needsYear = true,
            applies = Applies.BUSINESS_ONLY,
            note = "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),

        // ----------------------------------------------------------- μητρώο
        // Δύο διαφορετικά έντυπα, όχι δύο όψεις του ίδιου: το ένα είναι τα
        // στοιχεία του **ανθρώπου** (PersonalInfo), το άλλο της
        // **δραστηριότητας** (BusinessInfo/LegalInfo). Μια ατομική επιχείρηση
        // έχει και τα δύο, και ο λογιστής σχεδόν ποτέ δεν θέλει και τα δύο.
        Item("registry-natural", "Στοιχεία μητρώου — φυσικού προσώπου", GROUP_REGISTRY,
            "aade-registry", mapOf("type" to "ΦΥΣΙΚΟ"), applies = Applies.NATURAL_ONLY),
        Item("registry-business", "Στοιχεία μητρώου — επιχείρησης / οντότητας", GROUP_REGISTRY,
            "aade-registry", mapOf("type" to "ΕΠΙΧΕΙΡΗΣΗ"), applies = Applies.BUSINESS_ONLY,
            note = "Η μορφή (ατομική ή νομικό πρόσωπο) αναγνωρίζεται μόνη της."),

        // ------------------------------------------------- ενημέρωση καρτέλας
        Item("profile", "Άντληση ονοματεπωνύμου, ΔΟΥ και είδους", GROUP_CARD,
            "aade-profile", producesDocuments = false),
        Item("email", "Ενημέρωση email από το Μητρώο Επικοινωνίας", GROUP_CARD,
            "aade-email", producesDocuments = false),
        Item("amka", "Άντληση ΑΜΚΑ (MyAMKA)", GROUP_CARD,
            "amka-retrieve", producesDocuments = false, applies = Applies.NATURAL_ONLY),
    )

    fun byId(id: String): Item? = ALL.firstOrNull { it.id == id }

    /**
     * Τα έντυπα μιας ομάδας, με τη σειρά που δηλώθηκαν.
     *
     * Με [kind] συμπληρωμένο κρατά μόνο όσα αφορούν αυτό το είδος υπόχρεου.
     * Κενό [kind] σημαίνει «δεν ξέρω ποιανού» — δηλαδή παρτίδα με πολλούς
     * πελάτες, όπου δεν υπάρχει ένα σωστό φίλτρο.
     */
    fun inGroup(group: String, kind: String = ""): List<Item> =
        ALL.filter { it.group == group && (kind.isBlank() || it.matches(kind)) }

    /**
     * Κρύβει το φίλτρο αυτού του είδους έστω ένα έντυπο;
     *
     * Χρησιμεύει για να μη λέει η οθόνη «φιλτραρισμένα» όταν δεν φιλτράρει
     * τίποτα — ένα μήνυμα που δεν αντιστοιχεί σε αλλαγή είναι θόρυβος.
     */
    fun narrows(kind: String): Boolean =
        kind.isNotBlank() && ALL.any { it.group in GROUPS && !it.matches(kind) }
}
