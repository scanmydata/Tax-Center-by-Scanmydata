import Foundation

/// Σβήνει μυστικά από κείμενο που πάει σε log.
///
/// Ο engine λογάρει URL και ενίοτε πεδία φόρμας. Ένας κωδικός TAXISnet που
/// ξέφυγε σε log ξέφυγε για πάντα — γι' αυτό **κάθε** γραμμή περνά από εδώ πριν
/// κρατηθεί οπουδήποτε.
///
/// Ίδιοι κανόνες με το `Redactor.kt` του Android, και ένας επιπλέον: οι ίδιες οι
/// τιμές που δόθηκαν ως κωδικοί σε αυτή την εκτέλεση σβήνονται αυτούσιες, όπου
/// κι αν εμφανιστούν.
enum Redactor {

    static let mask = "«κρυφό»"

    private static let secretKeys = [
        "password", "passwd", "pwd", "pass",
        "j_password",
        "secret", "token", "access_token", "refresh_token", "id_token",
        "apikey", "api_key", "api-key",
        "subscription_key", "subscription-key", "ocp-apim-subscription-key",
        "aade-user-id",
        "klidarithmos", "kleidarithmos",
        "συνθηματικό", "συνθηματικο", "κωδικός", "κωδικος", "κλειδάριθμος", "κλειδαριθμος",
    ]

    private static func regex(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression? {
        try? NSRegularExpression(pattern: pattern, options: options)
    }

    private static let keyValue: NSRegularExpression? = {
        let keys = secretKeys.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|")
        let pattern = "(?<![\\p{L}\\p{N}_])(" + keys + ")(?![\\p{L}\\p{N}_])\\s*[\"']?\\s*[:=]\\s*[\"']?([^\\s&\"',;}<]{1,200})"
        return regex(pattern, [.caseInsensitive])
    }()

    private static let authHeader = regex("(authorization\\s*:\\s*)(\\S+\\s+)?(\\S{8,})", [.caseInsensitive])
    private static let hex32 = regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")
    private static let amka = regex("(?<!\\d)(\\d{2})\\d{9}(?!\\d)")
    /// Μόνο ASCII: το `.alphanumerics` του Foundation θεωρεί γράμμα και το «π»,
    /// και θα το άφηνε ακωδικοποίητο — ενώ στο URL ταξιδεύει ως %CF%80.
    private static let unreserved = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    private static let longToken = regex("(?<![\\p{L}\\p{N}_-])[a-z]{2,}\\d{4,}[a-z\\d]{4,}(?![\\p{L}\\p{N}_-])")

    /// - Parameter secrets: τιμές που δεν πρέπει να εμφανιστούν ποτέ (κωδικοί της εκτέλεσης).
    static func scrub(_ text: String, secrets: [String] = []) -> String {
        if text.isEmpty { return "" }
        var s = text
        // Πρώτα οι ίδιες οι τιμές: είναι ο μόνος κανόνας που δεν εξαρτάται από
        // το πώς τις έγραψε ο engine. Πολύ κοντές τιμές μένουν — θα έσβηναν
        // άσχετο κείμενο χωρίς να προστατεύουν τίποτα.
        for secret in secrets where secret.count >= 4 {
            s = s.replacingOccurrences(of: secret, with: mask)
            // Και όπως ταξιδεύει μέσα σε URL ή σε σώμα φόρμας.
            if let encoded = secret.addingPercentEncoding(withAllowedCharacters: unreserved), encoded != secret {
                s = s.replacingOccurrences(of: encoded, with: mask)
                s = s.replacingOccurrences(of: encoded.replacingOccurrences(of: "%20", with: "+"), with: mask)
            }
        }
        s = replace(keyValue, in: s, with: "$1=" + mask)
        s = replace(authHeader, in: s, with: "$1$2" + mask)
        s = replace(hex32, in: s, with: mask)
        s = replace(amka, in: s, with: "$1*********")
        s = replace(longToken, in: s, with: mask)
        return s
    }

    private static func replace(_ regex: NSRegularExpression?, in text: String, with template: String) -> String {
        guard let regex else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }
}
