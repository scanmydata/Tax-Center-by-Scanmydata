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
/// Τρέχει μόνο όταν ζητηθεί (`TEST_RUNNER_LIVE_PORTAL_PROBE=1`), σε δικό του
/// βήμα του CI που **δεν** ρίχνει το build: μια πύλη εκτός λειτουργίας δεν
/// πρέπει να μπλοκάρει μια έκδοση.
final class LiveTransportTests: XCTestCase {

    private struct Reply {
        let status: Int
        let headers: [String: String]
        let cookies: Int
        let bytes: Int
    }

    private func get(_ url: String) async throws -> Reply {
        let request: [String: Any] = ["url": url, "method": "GET", "headers": [String: String](), "redirect": "manual"]
        let json = String(data: try JSONSerialization.data(withJSONObject: request), encoding: .utf8) ?? "{}"
        let raw = try await URLSessionTransport().execute(json)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
        return Reply(
            status: object["status"] as? Int ?? 0,
            headers: object["headers"] as? [String: String] ?? [:],
            cookies: (object["setCookie"] as? [String])?.count ?? 0,
            bytes: Data(base64Encoded: object["bodyB64"] as? String ?? "")?.count ?? 0
        )
    }

    func testPublicEntryPagesAnswerThroughOurTransport() async throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["LIVE_PORTAL_PROBE"] == "1",
            "ζωντανός έλεγχος — μόνο όταν ζητηθεί"
        )

        // ΑΑΔΕ: η προστατευμένη αρχική στέλνει τον ασύνδετο επισκέπτη στο GSIS.
        // Αν εδώ έρθει 200, το URLSession ακολούθησε μόνο του την ανακατεύθυνση
        // — και ο engine δεν θα μπορούσε να κρίνει το InvalidCredentials.
        let home = try await get("https://www1.aade.gr/taxisnet/info/protected/home.htm")
        let location = home.headers["location"] ?? ""
        print("LIVE-PROBE aade status=\(home.status) redirect=\(!location.isEmpty) cookies=\(home.cookies) bytes=\(home.bytes)")
        XCTAssertTrue((300..<400).contains(home.status), "status=\(home.status)")
        XCTAssertFalse(location.isEmpty, "δεν ήρθε Location")

        // Η σελίδα σύνδεσης του GSIS — μόνο ανάγνωση.
        if let host = URL(string: location)?.host {
            let login = try await get(location)
            print("LIVE-PROBE login host=\(host) status=\(login.status) cookies=\(login.cookies) bytes=\(login.bytes)")
            XCTAssertTrue((200..<400).contains(login.status), "status=\(login.status)")
        }

        // e-ΕΦΚΑ: αρκεί να ολοκληρωθεί η χειραψία TLS και να έρθει απάντηση HTTP.
        let efka = try await get("https://services.e-efka.gov.gr/")
        print("LIVE-PROBE efka status=\(efka.status) cookies=\(efka.cookies) bytes=\(efka.bytes)")
        XCTAssertGreaterThan(efka.status, 0)
    }
}
