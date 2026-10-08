import XCTest
@testable import TaxCenter

/// Μια ψεύτικη ΑΑΔΕ, ίδια με το `aadeScenario` του `tools/test-shims.mjs`:
/// φόρμα OAM στην πρώτη επίσκεψη, αρχική σελίδα μετά τη σύνδεση.
///
/// Κανένα αίτημα δεν φεύγει στο δίκτυο, και κανένας πραγματικός κωδικός δεν
/// υπάρχει σε αυτό το αρχείο.
actor MockPortal: HttpTransport {

    private let goodCredentials: Bool
    private var homeHits = 0
    private(set) var urls: [String] = []

    init(goodCredentials: Bool) {
        self.goodCredentials = goodCredentials
    }

    func execute(_ requestJSON: String) async throws -> String {
        let spec = (try JSONSerialization.jsonObject(with: Data(requestJSON.utf8))) as? [String: Any] ?? [:]
        let url = spec["url"] as? String ?? ""
        urls.append(url)

        let html: String
        if url.contains("/taxisnet/info/protected/home.htm") {
            homeHits += 1
            html = homeHits == 1 ? Self.loginForm : Self.homeLoggedIn
        } else if url.contains("auth_cred_submit") {
            html = goodCredentials
                ? "<html><body>Καλώς ήρθατε</body></html>"
                : "<html><body>Καθορίστηκε λανθασμένο όνομα χρήστη ή κωδικός</body></html>"
        } else {
            html = "<html><body>ok</body></html>"
        }

        let reply: [String: Any] = [
            "status": 200,
            "url": url,
            "headers": ["content-type": "text/html; charset=UTF-8"],
            "setCookie": [String](),
            "bodyB64": Data(html.utf8).base64EncodedString(),
        ]
        let encoded = try JSONSerialization.data(withJSONObject: reply)
        return String(data: encoded, encoding: .utf8) ?? "{}"
    }

    private static let loginForm =
        "<html><body><form><input name=\"request_id\" value=\"RQ-42\">" +
        "<input name=\"username\"><input name=\"password\"></form></body></html>"

    private static let homeLoggedIn =
        "<html><body><ul>" +
        "<li><a href=\"/webtax/incomefp/\">Δήλωση Ε1</a></li>" +
        "<li><a href=\"/taxisnet/info/protected/displayDebtInfoAndPay.htm\">Οφειλές</a></li>" +
        "<li><a href=\"https://example.gr/εκτός\">Άσχετο</a></li>" +
        "</ul></body></html>"
}

/// Το ουσιώδες ερώτημα του iOS build: **τρέχει ο ίδιος engine και εδώ;**
///
/// Τα τεστ φορτώνουν τον πραγματικό engine του bundle μέσα στο κρυφό WKWebView
/// και τρέχουν ένα ολόκληρο config του runner, από τη σύνδεση ώς το αρχείο.
final class EngineHostTests: XCTestCase {

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("engine-" + UUID().uuidString, isDirectory: true)
    }

    func testEngineIsBundledWithEveryCatalogConfig() {
        let assets = EngineAssets.shared
        XCTAssertTrue(assets.available, "λείπει ο φάκελος engine από το bundle")
        let ids = Set(assets.configIds())
        XCTAssertGreaterThanOrEqual(ids.count, 30)
        for item in DocumentCatalog.all {
            XCTAssertTrue(ids.contains(item.configId), "το έντυπο «\(item.label)» ζητά config που λείπει: \(item.configId)")
            XCTAssertNotNil(assets.moduleSource(item.configId), "λείπει το αρχείο του \(item.configId)")
        }
        // Το όνομα έρχεται από JavaScript: δεν βγαίνει από τον φάκελο.
        XCTAssertNil(assets.moduleSource("../Info"))
        XCTAssertNil(assets.moduleSource("configs/aade-income"))
    }

    @MainActor
    func testLoginCheckRunsEndToEnd() async throws {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let portal = MockPortal(goodCredentials: true)
        let host = EngineHost(transport: portal)

        let result = await host.run(
            configId: "aade-login-check",
            inputs: ["user": "testuser", "pass": "μυστικό-ΣΥΝΘΗΜΑΤΙΚΟ-42"],
            outDir: directory,
            timeout: 90,
            secrets: ["μυστικό-ΣΥΝΘΗΜΑΤΙΚΟ-42"]
        )

        XCTAssertTrue(result.ok, "reason=\(result.reason)\n" + result.log.suffix(25).joined(separator: "\n"))
        XCTAssertTrue(result.files.contains("aade_menu_links.json"), "files: \(result.files)")

        let data = try Data(contentsOf: directory.appendingPathComponent("aade_menu_links.json"))
        let links = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(links["Δήλωση Ε1"], "https://www1.aade.gr/webtax/incomefp/")
        XCTAssertNotNil(links["Οφειλές"])
        XCTAssertNil(links["Άσχετο"], "ο άσχετος σύνδεσμος δεν έπρεπε να περάσει το φίλτρο")

        // Τα αντίγραφα σελίδων και το run.log είναι διαγνωστικά: δεν γράφονται στον δίσκο.
        let written = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        XCTAssertEqual(written, ["aade_menu_links.json"])

        // Ο κωδικός δεν εμφανίζεται πουθενά στο log.
        XCTAssertFalse(result.log.isEmpty)
        XCTAssertFalse(result.log.joined(separator: "\n").contains("μυστικό-ΣΥΝΘΗΜΑΤΙΚΟ-42"))

        // Το HTTP πέρασε από τη δική μας μεταφορά, όχι από το WebView.
        let urls = await portal.urls
        XCTAssertTrue(urls.contains { $0.contains("auth_cred_submit") }, "urls: \(urls)")
    }

    @MainActor
    func testWrongPasswordIsInvalidCredentialsNotACrash() async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let host = EngineHost(transport: MockPortal(goodCredentials: false))

        let result = await host.run(
            configId: "aade-login-check",
            inputs: ["user": "testuser", "pass": "λάθος"],
            outDir: directory,
            timeout: 90
        )

        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.reason, "InvalidCredentials")
        XCTAssertEqual(FetchOutcome.explain(result.reason).kind, .login)
    }

    @MainActor
    func testUnknownConfigFailsCleanly() async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let host = EngineHost(transport: MockPortal(goodCredentials: true))

        let result = await host.run(configId: "δεν-υπάρχει", inputs: [:], outDir: directory, timeout: 60)

        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.reason.contains("Δεν βρέθηκε module"), "reason=\(result.reason)")
    }

    /// Μια πύλη που δεν απαντά δεν κρεμάει την εφαρμογή: η εκτέλεση τελειώνει
    /// στη λήξη χρόνου, με λόγο που η οθόνη ξέρει να εξηγήσει.
    @MainActor
    func testTimeoutEndsTheRun() async {
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let host = EngineHost(transport: SilentPortal())

        let started = Date()
        let result = await host.run(
            configId: "aade-login-check",
            inputs: ["user": "testuser", "pass": "testpass"],
            outDir: directory,
            timeout: 3
        )

        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.reason.hasPrefix("Λήξη χρόνου"), "reason=\(result.reason)")
        XCTAssertEqual(FetchOutcome.explain(result.reason).kind, .network)
        XCTAssertLessThan(Date().timeIntervalSince(started), 30)
    }
}

/// Μια πύλη που δεν απαντά ποτέ.
actor SilentPortal: HttpTransport {
    func execute(_ requestJSON: String) async throws -> String {
        try await Task.sleep(nanoseconds: 20 * 1_000_000_000)
        return "{}"
    }
}
