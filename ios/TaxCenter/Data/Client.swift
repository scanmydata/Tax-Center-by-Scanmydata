import Foundation

/// Το είδος του υπόχρεου — ίδιες τιμές με το Android, ώστε ένα μελλοντικό
/// αντίγραφο ασφαλείας να διαβάζεται και από τις δύο εφαρμογές.
enum ClientKind: String, Codable, CaseIterable, Identifiable {
    case unknown = ""
    case privateIndividual = "ΙΔΙΩΤΗΣ"
    case sole = "ΑΤΟΜΙΚΗ ΕΠΙΧΕΙΡΗΣΗ"
    case legal = "ΝΟΜΙΚΟ ΠΡΟΣΩΠΟ"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .unknown: return "Δεν έχει οριστεί"
        case .privateIndividual: return "Ιδιώτης"
        case .sole: return "Ατομική επιχείρηση"
        case .legal: return "Νομικό πρόσωπο"
        }
    }
}

/// Τα μυστικά ενός πελάτη. Ζουν στο Keychain, ποτέ στο αρχείο των πελατών.
enum SecretField: String, CaseIterable {
    case taxisUser
    case taxisPass
    case amka
    case ikaUser
    case ikaPass
}

/// Ένας πελάτης του γραφείου — **χωρίς** τους κωδικούς του.
struct Client: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var afm: String = ""
    var name: String = ""
    var firstName: String = ""
    var kind: ClientKind = .unknown
    var doy: String = ""
    var email: String = ""
    var mobile: String = ""
    var updatedAt: Date = Date()

    init() {}

    // Με `decodeIfPresent`: ένα πεδίο που θα προστεθεί αύριο δεν πρέπει να
    // κάνει το αρχείο πελατών της προηγούμενης έκδοσης μη αναγνώσιμο.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        afm = try c.decodeIfPresent(String.self, forKey: .afm) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        firstName = try c.decodeIfPresent(String.self, forKey: .firstName) ?? ""
        let rawKind = try c.decodeIfPresent(String.self, forKey: .kind) ?? ""
        kind = ClientKind(rawValue: rawKind) ?? .unknown
        doy = try c.decodeIfPresent(String.self, forKey: .doy) ?? ""
        email = try c.decodeIfPresent(String.self, forKey: .email) ?? ""
        mobile = try c.decodeIfPresent(String.self, forKey: .mobile) ?? ""
        updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
    }

    var displayName: String {
        let full = [name, firstName].filter { !$0.isEmpty }.joined(separator: " ")
        return full.isEmpty ? afm : full
    }

    /// Εννέα ψηφία.
    static func isAfm(_ raw: String) -> Bool {
        raw.count == 9 && raw.allSatisfy { $0.isASCII && $0.isNumber }
    }

    /// Το ψηφίο ελέγχου του ΑΦΜ (mod 11). Ένα λάθος πλήκτρο το χαλάει σχεδόν πάντα.
    static func afmChecksumHolds(_ raw: String) -> Bool {
        guard isAfm(raw) else { return false }
        let digits = raw.compactMap { $0.wholeNumberValue }
        var sum = 0
        for index in 0..<8 { sum += digits[index] << (8 - index) }
        return (sum % 11) % 10 == digits[8]
    }
}
