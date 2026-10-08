import Foundation

/// Από όνομα αρχείου σε **όνομα εντύπου**.
///
/// Τα configs γράφουν αρχεία όπως `TELH_KYKLOFORIAS_999999999_2026.pdf`. Ο
/// πίνακας είναι ο ίδιος με του Android (`DocumentNaming.kt`): κάθε πρόθεμα
/// είναι αντιγραμμένο από τη γραμμή του config που φτιάχνει το όνομα. Ό,τι δεν
/// αναγνωρίζεται δείχνεται **ως έχει** — τεχνικό όνομα είναι κακό, λάθος όνομα
/// είναι χειρότερο.
enum DocumentNaming {

    private struct Entry {
        let prefix: String
        let label: String
        let yearInName: Bool
    }

    private static func entry(_ prefix: String, _ label: String, _ yearInName: Bool = true) -> Entry {
        Entry(prefix: prefix, label: label, yearInName: yearInName)
    }

    /// Τα ειδικότερα προθέματα **πρώτα**: το ένα όνομα ξεκινά με το άλλο.
    private static let entries: [Entry] = [
        entry("Εκκαθαριστικό_συζύγου", "Εκκαθαριστικό συζύγου"),
        entry("Εκκαθαριστικό", "Εκκαθαριστικό δήλωσης"),
        entry("E1_Synopsi", "Ε1 — συνοπτική εικόνα"),
        entry("E2_Spouse", "Ε2 συζύγου — κατάσταση μισθωμάτων"),
        entry("E3_myDATA", "Ε3 myDATA"),
        entry("E1", "Ε1 — δήλωση φορολογίας εισοδήματος"),
        entry("E2", "Ε2 — αναλυτική κατάσταση μισθωμάτων"),
        entry("E3", "Ε3 — κατάσταση οικονομικών στοιχείων"),

        entry("ENFIA_EKK", "ΕΝΦΙΑ — εκκαθαριστικό"),
        entry("PERIOUSIAKI", "Ε9 — περιουσιακή κατάσταση"),
        entry("MISTH", "Μισθωτήριο", false),

        entry("STOIXEIA_EPIXEIRISIS_NOMIKO", "Στοιχεία μητρώου — νομικού προσώπου", false),
        entry("STOIXEIA_EPIXEIRISIS", "Στοιχεία μητρώου — επιχείρησης", false),
        entry("STOIXEIA_FYSIKOU", "Στοιχεία μητρώου — φυσικού προσώπου", false),

        entry("FOR_LOGARIASMOS", "Φορολογικός λογαριασμός"),
        entry("TELH_KYKLOFORIAS", "Τέλη κυκλοφορίας"),
        entry("OFEILI", "Ταυτότητα οφειλής", false),
        entry("RYTHMISI", "Ταυτότητα ρυθμισμένης οφειλής"),
        entry("DOSEIS", "Ανάλυση δόσεων οφειλής", false),
        entry("KEAO_PBO", "ΚΕΑΟ — πράξη βεβαίωσης οφειλής", false),

        entry("KARTELA_ERGODOTI_TEKA", "Καρτέλα εργοδότη — ΤΕΚΑ"),
        entry("KARTELA_ERGODOTI_EFKA", "Καρτέλα εργοδότη — ΕΦΚΑ"),
        entry("KARTELA_ERGODOTI", "Καρτέλα εργοδότη"),
        entry("VEV_TEKA", "Φορολογική βεβαίωση ΤΕΚΑ"),
        entry("VEV_EFKA", "Φορολογική βεβαίωση ΕΦΚΑ"),
        entry("VEV", "Φορολογική βεβαίωση"),
        entry("EFKA_TEKA", "Ειδοποιητήριο ΤΕΚΑ", false),
        entry("EFKA_EFKA", "Ειδοποιητήριο ΕΦΚΑ", false),
        entry("EFKA", "Ειδοποιητήριο ΕΦΚΑ", false),
        entry("ATLAS", "ΑΤΛΑΣ — ασφαλιστικό ιστορικό", false),

        entry("FENP_N", "Έντυπο Ν — ΦΕΝΠ"),
        entry("ΦΜΥ", "ΦΜΥ — φόρος μισθωτών υπηρεσιών"),
        entry("Φ2", "Φ2 — δήλωση ΦΠΑ"),
        entry("Φ4", "Φ4 — ανακεφαλαιωτικός πίνακας παραδόσεων"),
        entry("Φ5", "Φ5 — ανακεφαλαιωτικός πίνακας αποκτήσεων"),
        entry("ΕΠΙΧ", "Αμοιβές επιχειρηματικής δραστηριότητας"),
        entry("ΜΕΡΙΣΜΑΤΑ", "Παρακρατούμενος φόρος μερισμάτων"),
        entry("ΤΟΚΟΙ", "Παρακρατούμενος φόρος τόκων"),
        entry("ΔΙΚΑΙΩΜΑΤΑ", "Παρακρατούμενος φόρος δικαιωμάτων"),
        entry("ΕΡΓΟΛΑΒΩΝ", "Εργολάβων (Φ01-019)"),
        entry("ΑΝΘΕΚΤΙΚΟΤΗΤΑΣ", "Τέλος ανθεκτικότητας / διαμονής"),
        entry("ΠΕΡΙΒΑΛΛΟΝ", "Περιβαλλοντικό τέλος"),
        entry("ΣΥΜΦΩΝΗΤΙΚΑ", "Κατάσταση συμφωνητικών"),
    ]

    private static func entry(for fileName: String) -> Entry? {
        let name = fileName.lowercased()
        return entries.first { name.hasPrefix($0.prefix.lowercased()) }
    }

    /// Μόνο η ετικέτα, χωρίς έτος. Κενό αν δεν αναγνωρίζεται.
    static func label(_ fileName: String) -> String {
        entry(for: fileName)?.label ?? ""
    }

    /// Το έτος όπως φαίνεται στο όνομα — σε **ολόκληρα κομμάτια** του ονόματος:
    /// ένα `OFEILI_…_2024,00` είναι ποσό, όχι έτος.
    static func year(in fileName: String) -> String {
        if let known = entry(for: fileName), !known.yearInName { return "" }
        let base = (fileName as NSString).deletingPathExtension
        let parts = base.split(whereSeparator: { $0 == "_" || $0 == "-" || $0 == " " }).map(String.init)
        return parts.last { part in
            guard part.count == 4, let year = Int(part) else { return false }
            return (1990...2100).contains(year)
        } ?? ""
    }

    /// «Τι είναι», για λίστα: ετικέτα και έτος, ή το ίδιο το όνομα.
    static func title(_ fileName: String) -> String {
        let name = label(fileName)
        if name.isEmpty { return fileName }
        let found = year(in: fileName)
        return found.isEmpty ? name : name + " " + found
    }
}
