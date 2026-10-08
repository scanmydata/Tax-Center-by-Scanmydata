import Foundation

/// Τι συνέβη σε μια λήψη, με λόγια.
///
/// Ο λογιστής πρέπει να ξεχωρίζει με μια ματιά το «δεν υπάρχει έντυπο» από το
/// «δεν έγινε σύνδεση» και από το «κάτι χάλασε»: θέλουν άλλη ενέργεια, και το
/// να μπερδευτούν σημαίνει ότι θα πει στον πελάτη πως δεν έχει εκκαθαριστικό
/// ενώ απλώς άλλαξε ο κωδικός του. Ίδια κείμενα με το Android (`FetchOutcome.kt`).
enum FetchOutcome {

    enum Kind {
        case login, missing, notFound, network, portal

        var title: String {
            switch self {
            case .login: return "Δεν έγινε σύνδεση"
            case .missing: return "Λείπουν στοιχεία στην καρτέλα"
            case .notFound: return "Δεν βρέθηκε"
            case .network: return "Πρόβλημα σύνδεσης στο δίκτυο"
            case .portal: return "Η πύλη δεν απάντησε όπως αναμενόταν"
            }
        }
    }

    struct Explained: Equatable {
        let kind: Kind
        let text: String
    }

    static func explain(_ reason: String) -> Explained {
        let raw = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        if let hit = known[raw] { return hit }
        if let hit = prefixed.first(where: { raw.hasPrefix($0.0) }) { return hit.1 }

        let lower = raw.lowercased()
        if raw.isEmpty {
            return Explained(kind: .portal, text: "Η διαδικασία σταμάτησε χωρίς να πει γιατί. Δοκίμασε ξανά.")
        }
        if raw.hasPrefix("Λείπουν") || raw.hasPrefix("Λείπει") {
            return Explained(kind: .missing, text: raw)
        }
        if lower.contains("oam-6") || lower.contains("μέγιστος αριθμός περιόδων") {
            return Explained(
                kind: .login,
                text: "Το GSIS αρνήθηκε τη σύνδεση: ο λογαριασμός έχει ήδη ανοιχτές συνεδρίες. " +
                    "Περίμενε μερικά λεπτά πριν ξαναδοκιμάσεις."
            )
        }
        if raw.hasPrefix("Λήξη χρόνου") {
            return Explained(
                kind: .network,
                text: "Η πύλη άργησε πολύ να απαντήσει και η διαδικασία διακόπηκε. Δοκίμασε ξανά — " +
                    "αν επιμένει, η υπηρεσία μάλλον είναι φορτωμένη αυτή την ώρα."
            )
        }
        if networkHints.contains(where: { lower.contains($0) }) {
            return Explained(
                kind: .network,
                text: "Δεν υπήρξε σύνδεση με την πύλη. Έλεγξε το δίκτυο της συσκευής και δοκίμασε ξανά."
            )
        }
        return Explained(
            kind: .portal,
            text: "Η διαδικασία σταμάτησε απρόσμενα (\(raw)). Δοκίμασε ξανά· αν επιμένει, η πύλη " +
                "μάλλον άλλαξε και χρειάζεται νέα έκδοση της εφαρμογής."
        )
    }

    /// Η σύνδεση έγινε και δεν βρέθηκε τίποτα — τι σημαίνει αυτό για κάθε διαδικασία.
    static func empty(_ configId: String) -> String {
        let meaning: String
        switch configId {
        case "aade-income", "aade-fenp", "aade-general-forms", "aade-declarations":
            meaning = "Δεν υπάρχει υποβεβλημένο έντυπο για αυτό το έτος — έλεγξε το έτος, ή αν το " +
                "έντυπο αφορά αυτόν τον υπόχρεο."
        case "aade-debts":
            meaning = "Ο πελάτης δεν έχει οφειλές με ταυτότητα πληρωμής στην ΑΑΔΕ."
        case "efka-obligations":
            meaning = "Δεν υπάρχουν πράξεις βεβαίωσης οφειλής προς λήψη."
        case "efka-notices":
            meaning = "Δεν υπάρχουν ειδοποιητήρια εισφορών προς λήψη."
        case "efka-teka-certificate":
            meaning = "Δεν έχει εκδοθεί βεβαίωση για αυτό το έτος."
        case "efka-employer-card":
            meaning = "Δεν υπάρχει καρτέλα εργοδότη για αυτό το έτος."
        case "aade-enfia-http":
            meaning = "Δεν υπάρχει εκκαθάριση ΕΝΦΙΑ ή περιουσιακή κατάσταση για το έτος που ζητήθηκε."
        case "aade-property":
            meaning = "Δεν υπάρχει περιουσιακή κατάσταση για αυτό το έτος."
        case "aade-lease":
            meaning = "Δεν υπάρχουν δηλώσεις μίσθωσης."
        case "aade-traffic-fees":
            meaning = "Δεν βρέθηκαν τέλη κυκλοφορίας για αυτό το έτος."
        case "aade-tax-account":
            meaning = "Δεν υπάρχει μηνιαία ενημέρωση για τον μήνα που ζητήθηκε."
        case "atlas-insurance-history":
            meaning = "Δεν υπάρχει ασφαλιστικό ιστορικό προς εκτύπωση."
        default:
            meaning = "Δεν υπάρχει έντυπο για αυτόν τον πελάτη."
        }
        return "Η σύνδεση έγινε και η πύλη απάντησε. " + meaning
    }

    // -------------------------------------------------------------- πίνακας

    private static func login(_ text: String) -> Explained { Explained(kind: .login, text: text) }
    private static func notFound(_ text: String) -> Explained { Explained(kind: .notFound, text: text) }
    private static func portal(_ text: String) -> Explained { Explained(kind: .portal, text: text) }

    private static func changed(_ step: String) -> Explained {
        portal(
            "Η πύλη δεν έδωσε τη σελίδα που περιμέναμε (\(step)). Συνήθως είναι προσωρινό — " +
                "δοκίμασε ξανά. Αν επιμένει, η πύλη άλλαξε και χρειάζεται νέα έκδοση της εφαρμογής."
        )
    }

    private static let known: [String: Explained] = {
        var d: [String: Explained] = [:]
        d["InvalidCredentials"] = login(
            "Το TAXISnet απέρριψε το όνομα χρήστη ή το συνθηματικό. Διόρθωσέ τα στην καρτέλα " +
                "πριν ξαναδοκιμάσεις: μετά από αλλεπάλληλες αποτυχίες το GSIS κλειδώνει τον " +
                "λογαριασμό του πελάτη."
        )
        d["GSIS login failed"] = login(
            "Η σύνδεση στο GSIS δεν ολοκληρώθηκε. Έλεγξε τους κωδικούς TAXISnet στην καρτέλα."
        )
        d["NotLoggedIn"] = login("Η σύνδεση δεν ολοκληρώθηκε — δοκίμασε ξανά.")
        d["KeaoLoginFailed"] = login(
            "Η πλατφόρμα του ΚΕΑΟ δεν δέχτηκε τη σύνδεση με τους κωδικούς TAXISnet του πελάτη."
        )
        d["AmkaRejected"] = login(
            "Η πύλη δεν δέχτηκε τον ΑΜΚΑ της καρτέλας. Έλεγξε ότι είναι του ίδιου του πελάτη."
        )
        d["ContactNotConfirmed"] = login(
            "Ο e-ΕΦΚΑ ζητά από τον πελάτη να επιβεβαιώσει πρώτα τα στοιχεία επικοινωνίας του " +
                "(κωδικός επιβεβαίωσης σε email ή κινητό). Αυτό το κάνει μόνο ο ίδιος, μία φορά· " +
                "μετά η λήψη δουλεύει κανονικά."
        )
        d["NoEdebtorRights"] = login(
            "Ο λογαριασμός του πελάτη δεν έχει δικαίωμα πρόσβασης στην πλατφόρμα οφειλετών."
        )

        d["NoDeclarationsRow"] = notFound(
            "Η σύνδεση έγινε· δεν υπάρχει δήλωση για αυτό το έτος στις «Δηλώσεις προς επεξεργασία»."
        )
        d["NoMonth"] = notFound("Η σύνδεση έγινε· δεν υπάρχει μηνιαία ενημέρωση για τον μήνα που ζητήθηκε.")
        d["YearNotAvailable"] = notFound(
            "Η σύνδεση έγινε· το έτος που ζητήθηκε δεν προσφέρεται από την πύλη για αυτόν τον πελάτη."
        )
        d["NotFound"] = notFound("Η σύνδεση έγινε· δεν βρέθηκε εγγραφή για αυτόν τον πελάτη.")
        d["NoEmail"] = notFound("Δεν βρέθηκε διεύθυνση στο Μητρώο Επικοινωνίας.")
        d["NoRegistry"] = notFound("Ο ΑΦΜ δεν έχει μητρώο φυσικού προσώπου ούτε επιχείρησης.")

        d["NoYear"] = Explained(kind: .missing, text: "Δεν επιλέχθηκε έτος.")
        d["BadForm"] = Explained(kind: .missing, text: "Το έντυπο που ζητήθηκε δεν αναγνωρίζεται από τη διαδικασία.")
        d["NoAfm"] = portal("Η σύνδεση έγινε, αλλά η πύλη δεν έδειξε τον ΑΦΜ του λογαριασμού. Δοκίμασε ξανά.")

        d["NoObligations"] = portal(
            "Ο λογαριασμός e-ΕΦΚΑ του πελάτη δεν δείχνει την ενότητα «Υποχρεώσεις Ασφάλισης»."
        )
        d["NoPDF"] = portal("Η πύλη βρήκε το έντυπο αλλά δεν επέστρεψε αρχείο PDF. Δοκίμασε ξανά αργότερα.")
        d["NoViewPdf"] = portal("Η δήλωση βρέθηκε, αλλά η πύλη δεν πρόσφερε το κουμπί προβολής της. Δοκίμασε ξανά.")
        d["LandPage"] = changed("αρχική σελίδα")
        d["PageError"] = changed("σελίδα σύνδεσης")
        d["ViewState"] = changed("φόρμα της υπηρεσίας")
        d["NoViewState"] = changed("φόρμα της υπηρεσίας")
        d["SelectRole"] = changed("επιλογή ρόλου")
        d["HomeForm"] = changed("αρχική φόρμα")
        d["HomeFormEmployer"] = changed("αρχική φόρμα εργοδότη")
        d["GetGsisLink"] = changed("σύνδεσμος προς GSIS")
        d["GsisLink"] = changed("σύνδεσμος προς GSIS")
        d["EisodosButton"] = changed("κουμπί εισόδου")
        d["EaccessForm"] = changed("φόρμα εισόδου e-ΕΦΚΑ")
        d["AfmNotPrefilled"] = changed("φόρμα εισόδου e-ΕΦΚΑ")
        d["NoRequestId"] = changed("σελίδα σύνδεσης GSIS")
        d["NoToken"] = changed("διακριτικό της υπηρεσίας")
        d["NoAmkaPage"] = changed("σελίδα ΑΜΚΑ")
        d["NoList"] = changed("λίστα δηλώσεων")
        d["NoAccordion"] = changed("ενότητα υποχρεώσεων")
        d["NoGotoIncomeN"] = changed("μετάβαση στο έντυπο Ν")
        d["NoEntryLink"] = changed("είσοδος στην εφαρμογή")
        d["NoYearSelect"] = changed("επιλογή έτους")
        d["BadJson"] = changed("απάντηση της υπηρεσίας")
        return d
    }()

    private static let prefixed: [(String, Explained)] = [
        ("EtakEntry", changed("είσοδος στο ETAK")),
        ("AdfFlow", changed("πλοήγηση στο ETAK")),
        ("API_", changed("απάντηση της υπηρεσίας")),
        ("Μη αναγνώσιμο αποτέλεσμα", portal("Η διαδικασία τελείωσε χωρίς αναγνώσιμο αποτέλεσμα. Δοκίμασε ξανά.")),
    ]

    // Και τα μηνύματα του URLSession, που είναι στα αγγλικά ή στα ελληνικά
    // ανάλογα με τη γλώσσα της συσκευής.
    private static let networkHints = [
        "timeout", "timed out", "connection reset", "connection closed", "network is unreachable",
        "no route to host", "ssl", "socket", "fetch failed",
        "offline", "internet connection", "network connection", "could not connect", "cannot find host",
        "hostname could not be found", "σύνδεση στο διαδίκτυο", "εκτός σύνδεσης", "χρονικό όριο", "διακομιστή",
    ]
}
