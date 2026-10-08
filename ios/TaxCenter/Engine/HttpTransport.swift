import Foundation

/// Η μεταφορά HTTP για τον JS engine.
///
/// Δέχεται το αίτημα όπως το στέλνει το `fetch` shim —
/// `{url, method, headers, body, redirect}` — και επιστρέφει
/// `{status, url, headers, setCookie[], bodyB64}`.
protocol HttpTransport {
    func execute(_ requestJSON: String) async throws -> String
}

struct HttpTransportError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Η πραγματική μεταφορά, πάνω σε `URLSession`.
///
/// Ο engine (`hyper-http.js`) κρατά **δικό του** cookie jar και κυνηγά μόνος του
/// τις ανακατευθύνσεις. Τρία πράγματα είναι load-bearing, όπως και στο Android:
///
///  1. **Καμία αυτόματη ανακατεύθυνση.** Ο engine κρίνει `InvalidCredentials`
///     από το τελικό URL, άρα πρέπει να δει κάθε 3xx ο ίδιος.
///  2. **Κανένα cookie από το σύστημα.** Αλλιώς θα έφευγαν δεύτερα, αντικρουόμενα.
///  3. **Όλα τα `Set-Cookie` ακέραια, ένα-ένα.** Το `URLSession` τα ενώνει σε
///     μία κεφαλίδα με κόμματα· εδώ ξαναχωρίζονται — βλ. [splitSetCookie].
final class URLSessionTransport: NSObject, HttpTransport, URLSessionTaskDelegate {

    static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 90
        // Τα PDF της ΑΑΔΕ παράγονται on demand και αργούν.
        configuration.timeoutIntervalForResource = 180
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // `nil` = μην ακολουθήσεις· παράδωσε την ίδια την απάντηση 3xx.
        completionHandler(nil)
    }

    func execute(_ requestJSON: String) async throws -> String {
        guard let data = requestJSON.data(using: .utf8),
              let spec = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = spec["url"] as? String,
              let url = Self.url(from: raw)
        else { throw HttpTransportError(message: "Μη έγκυρο αίτημα") }

        let method = ((spec["method"] as? String) ?? "GET").uppercased()
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpShouldHandleCookies = false

        var hasUserAgent = false
        for (name, value) in (spec["headers"] as? [String: Any]) ?? [:] {
            guard let value = value as? String else { continue }
            if name.caseInsensitiveCompare("User-Agent") == .orderedSame { hasUserAgent = !value.isEmpty }
            // Αυτούσιες, ιδίως το `Content-Type: …; charset=UTF-8`: χωρίς το
            // charset ο JSF του e-ΕΦΚΑ γυρίζει τα ελληνικά ως «?».
            request.setValue(value, forHTTPHeaderField: name)
        }
        if !hasUserAgent { request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent") }

        if let body = spec["body"] as? String {
            request.httpBody = body.data(using: .utf8)
        } else if ["POST", "PUT", "PATCH"].contains(method) {
            request.httpBody = Data()
        }

        let (body, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HttpTransportError(message: "Η απάντηση δεν ήταν HTTP")
        }

        // Το URLSession αποσυμπιέζει μόνο του. Οι κεφαλίδες συμπίεσης δεν
        // περιγράφουν πια το σώμα που παραδίδουμε, οπότε φεύγουν.
        let encoding = (http.value(forHTTPHeaderField: "Content-Encoding") ?? "").lowercased()
        let decompressed = encoding.contains("gzip") || encoding.contains("deflate") || encoding.contains("br")

        var headers: [String: String] = [:]
        for (key, value) in http.allHeaderFields {
            guard let name = (key as? String)?.lowercased(), let value = value as? String else { continue }
            if name == "set-cookie" { continue }
            if decompressed && (name == "content-encoding" || name == "content-length") { continue }
            headers[name] = value
        }

        let reply: [String: Any] = [
            "status": http.statusCode,
            "url": http.url?.absoluteString ?? raw,
            "headers": headers,
            "setCookie": Self.splitSetCookie(http.value(forHTTPHeaderField: "Set-Cookie") ?? ""),
            "bodyB64": body.base64EncodedString(),
        ]
        let encoded = try JSONSerialization.data(withJSONObject: reply)
        return String(data: encoded, encoding: .utf8) ?? "{}"
    }

    /// Ο engine δίνει ήδη κωδικοποιημένα URL. Όπου όχι (ελληνικά σε query), τα
    /// κωδικοποιούμε εμείς — πριν από το iOS 17 το `URL(string:)` τα απέρριπτε.
    static func url(from raw: String) -> URL? {
        if let url = URL(string: raw), url.scheme != nil { return url }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~:/?#[]@!$&'()*+,;=%")
        guard let encoded = raw.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return URL(string: encoded)
    }

    private static let cookieBoundary = try? NSRegularExpression(
        pattern: ",(?=\\s*[A-Za-z0-9!#\\$%\\&'*+.\\^_`\\|~\\-]+=)",
        options: []
    )

    /// Χωρίζει την ενωμένη κεφαλίδα `Set-Cookie` στα cookies της.
    ///
    /// Το κόμμα δεν αρκεί ως όριο: υπάρχει και μέσα στο `Expires=Wed, 21 Oct …`.
    /// Όριο είναι μόνο το κόμμα που ακολουθείται από `όνομα=`.
    static func splitSetCookie(_ merged: String) -> [String] {
        let trimmed = merged.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return [] }
        guard let regex = cookieBoundary else { return [trimmed] }
        let text = trimmed as NSString
        var parts: [String] = []
        var start = 0
        for match in regex.matches(in: trimmed, options: [], range: NSRange(location: 0, length: text.length)) {
            parts.append(text.substring(with: NSRange(location: start, length: match.range.location - start)))
            start = match.range.location + 1
        }
        parts.append(text.substring(from: start))
        return parts.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}
