package gr.scanmydata.taxcenter.data

/**
 * Αντιστοίχιση επικεφαλίδων Excel σε πεδία πελάτη.
 *
 * **Η αντιστοίχιση γίνεται σε ΟΛΟΚΛΗΡΗ την κανονικοποιημένη επικεφαλίδα, ποτέ με
 * substring.** Αυτό δεν είναι λεπτομέρεια ύφους — είναι ο λόγος ύπαρξης του
 * [FORBIDDEN]. Στο πρότυπο «Κωδικοί Υπόχρεων» συνυπάρχουν:
 *
 *   στήλη BI  «Api myData»                    -> το πραγματικό myDATA key
 *   στήλη BL  «Subscription key e-timologio»  -> ΑΛΛΟ προϊόν
 *
 * Ένα substring match στο «subscription key» θα άρπαζε το κλειδί του e-timologio
 * και θα το έστελνε ως myDATA key — 403 σε κάθε πελάτη, σιωπηλά. Το ίδιο ισχύει
 * για το «Συνθηματικό myData», που είναι ο κωδικός της ιστοσελίδας και όχι το
 * κλειδί του API.
 *
 * Κρατάμε 14 από τις 83 στήλες: ό,τι χρειάζονται οι διαδικασίες του runner και
 * τίποτε άλλο (ελαχιστοποίηση δεδομένων, GDPR άρθρο 5 παρ. 1 στοιχείο γ).
 */
object ColumnAliases {

    /** Τα πεδία που κρατάμε. Η σειρά είναι η σειρά εμφάνισης στο preview. */
    enum class Field {
        AFM, NAME, FIRST_NAME, KIND, AMKA, DOY, ACTIVE,
        TAXIS_USER, TAXIS_PASS, TAXIS_KLIDARITHMOS,
        IKA_EMPLOYER_USER, IKA_EMPLOYER_PASS,
        IKA_INSURED_USER, IKA_INSURED_PASS,
        MYDATA_USER, MYDATA_KEY,
    }

    /**
     * Κανονικοποιημένες (βλ. [Normalize.header]) επικεφαλίδες ανά πεδίο.
     * Όλες γραμμένες πεζές, χωρίς τόνους, χωρίς τελείες.
     */
    private val ALIASES: Map<Field, Set<String>> = mapOf(
        Field.AFM to setOf("αφμ", "afm", "vat", "vat number", "αφμ υποχρεου"),
        Field.NAME to setOf("επωνυμια επωνυμο", "επωνυμια", "επωνυμο", "ονομασια", "name"),
        Field.FIRST_NAME to setOf("ονομα", "first name"),
        Field.KIND to setOf("ειδος"),
        Field.AMKA to setOf("αμκα", "amka"),
        Field.DOY to setOf("δου", "doy"),
        Field.ACTIVE to setOf("ενεργοσ ανενεργοσ", "ενεργοσ"),

        Field.TAXIS_USER to setOf("ονομα χρηστη taxisnet", "ονομα χρηστη taxis", "taxisnet username"),
        Field.TAXIS_PASS to setOf("συνθηματικο taxisnet", "συνθηματικο taxis", "taxisnet password"),
        Field.TAXIS_KLIDARITHMOS to setOf("κλειδαριθμοσ"),

        Field.IKA_EMPLOYER_USER to setOf("ονομα χρηστη εργοδοτη ικα"),
        Field.IKA_EMPLOYER_PASS to setOf("συνθηματικο εργοδοτη ικα"),
        Field.IKA_INSURED_USER to setOf("ονομα χρηστη ασφαλισμενου ικα"),
        Field.IKA_INSURED_PASS to setOf("συνθηματικο ασφαλισμενου ικα"),

        Field.MYDATA_USER to setOf("ονομα χρηστη mydata", "ονομα χρηστη my data", "aade user id", "aade user"),
        Field.MYDATA_KEY to setOf("api mydata", "api my data", "ocp apim subscription key"),
    )

    /**
     * Επικεφαλίδες που μοιάζουν χρήσιμες αλλά **δεν** είναι — με τον λόγο, ώστε
     * να μπορεί να εμφανιστεί στον χρήστη αν χρειαστεί.
     */
    val FORBIDDEN: Map<String, String> = mapOf(
        "subscription key e timologio" to "κλειδί e-timologio (άλλο προϊόν)",
        "ονομα χρηστη e timologio" to "χρήστης e-timologio (άλλο προϊόν)",
        "συνθηματικο e timologio" to "συνθηματικό e-timologio (άλλο προϊόν)",
        "συνθηματικο mydata" to "συνθηματικό ιστοσελίδας myDATA, όχι το κλειδί API",
        "συνθηματικο my data" to "συνθηματικό ιστοσελίδας myDATA, όχι το κλειδί API",
    )

    /** Το πεδίο στο οποίο αντιστοιχεί η επικεφαλίδα, ή null. */
    fun match(rawHeader: String?): Field? {
        val h = Normalize.header(rawHeader)
        if (h.isEmpty() || FORBIDDEN.containsKey(h)) return null
        return ALIASES.entries.firstOrNull { (_, names) -> h in names }?.key
    }

    /** Γιατί αγνοήθηκε μια επικεφαλίδα που ίσως περίμενε ο χρήστης. */
    fun forbiddenReason(rawHeader: String?): String? = FORBIDDEN[Normalize.header(rawHeader)]

    /**
     * Χαρτογραφεί μια γραμμή επικεφαλίδων σε `γράμμα στήλης -> πεδίο`.
     * Αν δύο στήλες διεκδικούν το ίδιο πεδίο, κερδίζει η **πρώτη**.
     */
    fun mapHeaderRow(headers: Map<String, String>): Map<String, Field> {
        val out = LinkedHashMap<String, Field>()
        val taken = HashSet<Field>()
        for ((column, text) in headers.entries.sortedBy { columnIndex(it.key) }) {
            val field = match(text) ?: continue
            if (taken.add(field)) out[column] = field
        }
        return out
    }

    /** `A` -> 0, `Z` -> 25, `AA` -> 26, `BI` -> 60. */
    fun columnIndex(column: String): Int {
        var n = 0
        for (ch in column.uppercase()) {
            if (ch !in 'A'..'Z') continue
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }
}
