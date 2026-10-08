import XCTest
@testable import TaxCenter

/// Η πραγματική μεταφορά HTTP, απέναντι στις πραγματικές πύλες — **χωρίς σύνδεση**.
///
/// Τα υπόλοιπα τεστ τρέχουν με ψεύτικη πύλη, οπότε δεν λένε τίποτα για το
/// `URLSession`: αν το iOS δέχεται το TLS των πυλών, αν η ανακατεύθυνση μένει
/// πράγματι στα χέρια του engine, αν τα `Set-Cookie` φτάνουν χωριστά.
///
/// Εδώ ζητείται μόνο η **δημόσια** πρώτη σελίδα κάθε πύλης, όπως θα την ζητούσε
/// οποιοσδήποτε browser. Κανένας κωδικός, καμία φόρμα σύνδεσης δεν υποβάλλεται.
///
/// Τρέχει μόνο όταν ζητηθεί (`TEST_RUNNER_LIVE_PORTAL_PROBE=1`, στο CI), και
/// **δεν αποτυγχάνει ποτέ**: γράφει `LIVE-PROBE OK` ή `LIVE-PROBE FAILED` στο
/// log και το workflow το δείχνει ως προειδοποίηση. Μια πύλη εκτός λειτουργίας
/// δεν πρέπει να μπλοκάρει μια έκδοση.
final class LiveTransportTests: XCTestCase {

    private struct Reply {
        let status: Int
        let headers: [String: String]
        let cookies: Int
        let bytes: Int
    }

    private struct ProbeError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private func get(_ url: String) async throws -> Reply {
        let request: [String: Any] = ["url": url, "method": "GET", "headers": [String: String](), "redirect": "manual"]
        let encoded = try JSONSerialization.data(withJSONObject: request)
        let raw = try await URLSessionTransport().execute(String(data: encoded, encoding: .utf8) ?? "{}")
        guard let object = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw ProbeError(message: "μη αναγνώσιμη απάντηση της μεταφοράς")
        }
        return Reply(
            status: object["status"] as? Int ?? 0,
            headers: object["headers"] as? [String: String] ?? [:],
            cookies: (object["setCookie"] as? [String])?.count ?? 0,
            bytes: Data(base64Encoded: object["bodyB64"] as? String ?? "")?.count ?? 0
        )
    }

    private func probe() async throws {
        // ΑΑΔΕ: η προστατευμένη αρχική στέλνει τον ασύνδετο επισκέπτη στο GSIS.
        // Αν εδώ έρθει 200, το URLSession ακολούθησε μόνο του την ανακατεύθυνση
        // — και ο engine δεν θα μπορούσε να κρίνει το InvalidCredentials.
        let home = try await get("https://www1.aade.gr/taxisnet/info/protected/home.htm")
        let location = home.headers["location"] ?? ""
        print("LIVE-PROBE aade status=\(home.status) redirect=\(!location.isEmpty) cookies=\(home.cookies) bytes=\(home.bytes)")
        guard (300..<400).contains(home.status), !location.isEmpty else {
            throw ProbeError(message: "η ΑΑΔΕ δεν έδωσε ανακατεύθυνση (status \(home.status))")
        }

        // Η σελίδα σύνδεσης του GSIS — μόνο ανάγνωση.
        let login = try await get(location)
        let host = URL(string: location)?.host ?? "?"
        print("LIVE-PROBE login host=\(host) status=\(login.status) cookies=\(login.cookies) bytes=\(login.bytes)")
        guard (200..<400).contains(login.status) else {
            throw ProbeError(message: "το GSIS απάντησε \(login.status)")
        }

        // e-ΕΦΚΑ: αρκεί να ολοκληρωθεί η χειραψία TLS και να έρθει απάντηση HTTP.
        let efka = try await get("https://services.e-efka.gov.gr/")
        print("LIVE-PROBE efka status=\(efka.status) cookies=\(efka.cookies) bytes=\(efka.bytes)")
        guard efka.status > 0 else {
            throw ProbeError(message: "ο e-ΕΦΚΑ δεν απάντησε")
        }
    }

    func testPublicEntryPagesAnswerThroughOurTransport() async throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["LIVE_PORTAL_PROBE"] == "1",
            "ζωντανός έλεγχος — μόνο όταν ζητηθεί"
        )
        do {
            try await probe()
            print("LIVE-PROBE OK")
        } catch {
            print("LIVE-PROBE FAILED \(error.localizedDescription)")
            throw XCTSkip("η ζωντανή πύλη δεν απάντησε όπως αναμενόταν: \(error.localizedDescription)")
        }
    }
}
