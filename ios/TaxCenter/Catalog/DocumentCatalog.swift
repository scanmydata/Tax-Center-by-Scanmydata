import Foundation

/// Ποια έντυπα προσφέρει η εφαρμογή, και με ποιο config κατεβαίνει το καθένα.
///
/// Είναι ο κατάλογος του Android (`DocumentCatalog.kt`), με τις ίδιες ετικέτες
/// και τα ίδια inputs. Λείπουν **επίτηδες** όσα δεν γίνονται ακόμη στο iOS:
///
///  * η καρτέλα οφειλέτη ΚΕΑΟ — η πύλη δεν δίνει PDF· το έντυπο το συνθέτει η
///    εφαρμογή του Android, και αυτός ο κώδικας δεν έχει μεταφερθεί,
///  * το παλιό `aade-enfia` — θέλει ορατό browser (το `aade-enfia-http` που
///    χρησιμοποιείται εδώ δεν τον χρειάζεται),
///  * οι «ενημερώσεις καρτέλας» (άντληση ονόματος, email, ΑΜΚΑ).
///
/// Ένα έντυπο που φαίνεται στη λίστα και δεν δουλεύει είναι χειρότερο από ένα
/// που λείπει.
enum DocumentCatalog {

    enum Applies {
        case all, legalOnly, naturalOnly, businessOnly
    }

    /// Ποιο ζεύγος κωδικών θέλει το config — βλ. `CredentialMap.kt`.
    enum Login {
        /// TAXISnet.
        case taxis
        /// TAXISnet, και επιπλέον ΑΦΜ και ΑΜΚΑ στη φόρμα της πύλης.
        case taxisWithAmka
        /// Κωδικοί ΙΚΑ εργοδότη — άλλη πύλη, χωρίς GSIS.
        case ikaEmployer
    }

    struct Item: Identifiable, Hashable {
        let id: String
        let label: String
        let group: String
        let configId: String
        var inputs: [String: String] = [:]
        var needsYear = false
        var needsMonth = false
        var applies: Applies = .all
        var note = ""

        func matches(_ kind: ClientKind) -> Bool {
            switch (applies, kind) {
            case (_, .unknown), (.all, _): return true
            case (.legalOnly, _): return kind == .legal
            case (.naturalOnly, _): return kind != .legal
            case (.businessOnly, _): return kind != .privateIndividual
            }
        }

        var login: Login { DocumentCatalog.login(for: configId) }
    }

    static let groupIncome = "Εισόδημα"
    static let groupVat = "ΦΠΑ"
    static let groupWithholding = "Παρακρατούμενοι φόροι"
    static let groupOtherForms = "Λοιπά έντυπα"
    static let groupProperty = "Ακίνητα"
    static let groupDebts = "Οφειλές & λογαριασμός"
    static let groupInsurance = "Ασφάλιση (ΕΦΚΑ)"
    static let groupEmployer = "Εργοδότης"
    static let groupRegistry = "Μητρώο"

    static let groups = [
        groupIncome, groupVat, groupWithholding, groupOtherForms,
        groupProperty, groupDebts, groupInsurance, groupEmployer, groupRegistry,
    ]

    static func login(for configId: String) -> Login {
        switch configId {
        case "efka-notices", "efka-teka-certificate", "efka-obligations", "atlas-insurance-history", "keao-debts":
            return .taxisWithAmka
        case "efka-employer-card":
            return .ikaEmployer
        default:
            return .taxis
        }
    }

    private static func general(_ id: String, _ label: String, _ group: String, _ form: String) -> Item {
        Item(id: id, label: label, group: group, configId: "aade-general-forms",
             inputs: ["form": form], needsYear: true, applies: .businessOnly)
    }

    static let all: [Item] = [
        // ------------------------------------------------------------ Εισόδημα
        Item(id: "e1", label: "Ε1 — Δήλωση φορολογίας εισοδήματος", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "E1"], needsYear: true),
        Item(id: "e2", label: "Ε2 — Αναλυτική κατάσταση μισθωμάτων", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "E2"], needsYear: true),
        Item(id: "e3", label: "Ε3 — Κατάσταση οικονομικών στοιχείων", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "E3"], needsYear: true, applies: .businessOnly),
        Item(id: "e3-mydata", label: "Ε3 myDATA", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "E3MYDATA"], needsYear: true, applies: .businessOnly),
        Item(id: "ekkatharistiko", label: "Εκκαθαριστικό (πράξη διοικητικού προσδιορισμού)", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "EKK"], needsYear: true),
        Item(id: "ekkatharistiko-syz", label: "Εκκαθαριστικό συζύγου", group: groupIncome,
             configId: "aade-income", inputs: ["forms": "EKK_SYZ"], needsYear: true, applies: .naturalOnly,
             note: "Από την κοινή δήλωση, με τους κωδικούς του υπόχρεου."),
        Item(id: "fenp", label: "Έντυπο Ν — ΦΕΝΠ (νομικά πρόσωπα)", group: groupIncome,
             configId: "aade-fenp", needsYear: true, applies: .legalOnly),

        // ----------------------------------------------------------------- ΦΠΑ
        general("f2", "Φ2 — Δήλωση ΦΠΑ", groupVat, "Φ2"),
        general("f4", "Φ4 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών παραδόσεων", groupVat, "Φ4"),
        general("f5", "Φ5 — Ανακεφαλαιωτικός πίνακας ενδοκοινοτικών αποκτήσεων", groupVat, "Φ5"),

        // ------------------------------------------------------ Παρακρατούμενοι
        general("fmy", "ΦΜΥ — Φόρος μισθωτών υπηρεσιών", groupWithholding, "ΦΜΥ"),
        general("epix", "Αμοιβές επιχειρηματικής δραστηριότητας", groupWithholding, "ΕΠΙΧ"),
        general("merismata", "Μερίσματα", groupWithholding, "ΜΕΡΙΣΜΑΤΑ"),
        general("tokoi", "Τόκοι", groupWithholding, "ΤΟΚΟΙ"),
        general("dikaiomata", "Δικαιώματα", groupWithholding, "ΔΙΚΑΙΩΜΑΤΑ"),
        general("ergolavon", "Εργολάβων (Φ01-019)", groupWithholding, "ΕΡΓΟΛΑΒΩΝ"),

        // -------------------------------------------------------- Λοιπά έντυπα
        general("anthektikotitas", "Τέλος ανθεκτικότητας / διαμονής", groupOtherForms, "ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ"),
        general("perivallon", "Περιβαλλοντικό τέλος", groupOtherForms, "ΠΕΡΙΒΑΛΛΟΝ"),
        general("symfonitika", "Κατάσταση συμφωνητικών", groupOtherForms, "ΣΥΜΦΩΝΗΤΙΚΑ"),

        // ------------------------------------------------------------- Ακίνητα
        Item(id: "enfia", label: "ΕΝΦΙΑ — Εκκαθαριστικό (με δόσεις)", group: groupProperty,
             configId: "aade-enfia-http", inputs: ["docs": "EKK"], needsYear: true,
             note: "Οι δόσεις του ΕΝΦΙΑ είναι μέσα στο ίδιο το εκκαθαριστικό."),
        Item(id: "e9", label: "Ε9 / Περιουσιακή κατάσταση", group: groupProperty,
             configId: "aade-enfia-http", inputs: ["docs": "PERIOUSIAKI"], needsYear: true,
             note: "Το έτος N αφορά την περιουσία της 1ης Ιανουαρίου N."),
        Item(id: "property", label: "Περιουσιακή κατάσταση (myPROPERTY)", group: groupProperty,
             configId: "aade-property", needsYear: true),
        Item(id: "lease", label: "Μισθωτήρια — πληροφοριακά στοιχεία μισθώσεων", group: groupProperty,
             configId: "aade-lease"),

        // ------------------------------------------------------------- Οφειλές
        Item(id: "debts", label: "Οφειλές & ταυτότητες οφειλής (ΑΑΔΕ)", group: groupDebts,
             configId: "aade-debts",
             note: "Ένα έντυπο ανά οφειλή, εντός και εκτός ρύθμισης, με την ταυτότητα πληρωμής της."),
        Item(id: "tax-account", label: "Φορολογικός λογαριασμός (μηνιαία ενημέρωση)", group: groupDebts,
             configId: "aade-tax-account", needsYear: true, needsMonth: true),
        Item(id: "traffic-fees", label: "Τέλη κυκλοφορίας (myCAR)", group: groupDebts,
             configId: "aade-traffic-fees", needsYear: true),

        // ------------------------------------------------------------ Ασφάλιση
        Item(id: "efka-notices", label: "Ειδοποιητήρια εισφορών ΕΦΚΑ/ΤΕΚΑ", group: groupInsurance,
             configId: "efka-notices", applies: .naturalOnly),
        Item(id: "efka-certificate", label: "Φορολογικές βεβαιώσεις ΕΦΚΑ/ΤΕΚΑ", group: groupInsurance,
             configId: "efka-teka-certificate", needsYear: true, applies: .naturalOnly),
        Item(id: "efka-obligations", label: "Υποχρεώσεις ασφάλισης & ΠΒΟ ΚΕΑΟ", group: groupInsurance,
             configId: "efka-obligations", inputs: ["pdf": "ναι"], applies: .naturalOnly),
        Item(id: "atlas", label: "Ασφαλιστικό / εργασιακό ιστορικό (ΑΤΛΑΣ)", group: groupInsurance,
             configId: "atlas-insurance-history", applies: .naturalOnly),

        // ----------------------------------------------------------- Εργοδότης
        Item(id: "employer-efka", label: "Οικονομική καρτέλα εργοδότη — ΕΦΚΑ", group: groupEmployer,
             configId: "efka-employer-card", inputs: ["which": "EFKA"], needsYear: true, applies: .businessOnly,
             note: "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),
        Item(id: "employer-teka", label: "Οικονομική καρτέλα εργοδότη — ΤΕΚΑ", group: groupEmployer,
             configId: "efka-employer-card", inputs: ["which": "TEKA"], needsYear: true, applies: .businessOnly,
             note: "Θέλει κωδικούς ΙΚΑ εργοδότη, όχι TAXISnet."),

        // -------------------------------------------------------------- Μητρώο
        Item(id: "registry-natural", label: "Στοιχεία μητρώου — φυσικού προσώπου", group: groupRegistry,
             configId: "aade-registry", inputs: ["type": "ΦΥΣΙΚΟ"], applies: .naturalOnly),
        Item(id: "registry-business", label: "Στοιχεία μητρώου — επιχείρησης / οντότητας", group: groupRegistry,
             configId: "aade-registry", inputs: ["type": "ΕΠΙΧΕΙΡΗΣΗ"], applies: .businessOnly,
             note: "Η μορφή (ατομική ή νομικό πρόσωπο) αναγνωρίζεται μόνη της."),
    ]

    static func items(in group: String, for kind: ClientKind) -> [Item] {
        all.filter { $0.group == group && $0.matches(kind) }
    }
}
