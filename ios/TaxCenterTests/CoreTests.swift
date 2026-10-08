import XCTest
@testable import TaxCenter

/// Ό,τι κρίνεται χωρίς πύλη: τι σβήνεται από τα logs, πού γράφονται τα αρχεία,
/// τι μπαίνει στα inputs, και τι λέει η οθόνη όταν μια λήψη δεν φέρει έντυπο.
///
/// Όλα τα ΑΦΜ, ΑΜΚΑ και οι κωδικοί είναι συνθετικά.
final class CoreTests: XCTestCase {

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("core-" + UUID().uuidString, isDirectory: true)
    }

    // ------------------------------------------------------------- Redactor

    func testRedactorMasksSecrets() {
        let line = "POST form username=testuser&password=Abc123!x&x=1"
        let scrubbed = Redactor.scrub(line)
        XCTAssertFalse(scrubbed.contains("Abc123"), scrubbed)
        XCTAssertTrue(scrubbed.contains("testuser"), "το όνομα χρήστη δεν είναι μυστικό")

        XCTAssertFalse(Redactor.scrub("Συνθηματικό: Κρυφός9").contains("Κρυφός9"))
        XCTAssertFalse(Redactor.scrub("Authorization: Bearer abcdef123456").contains("abcdef123456"))
        // ΑΜΚΑ: μένουν μόνο τα δύο πρώτα ψηφία.
        XCTAssertEqual(Redactor.scrub("amka 01019912345 ok"), "amka 01********* ok")
        // Ο ΑΦΜ (εννέα ψηφία) δεν είναι ΑΜΚΑ και δεν πειράζεται.
        XCTAssertEqual(Redactor.scrub("afm 123456783"), "afm 123456783")
    }

    /// Ο ισχυρότερος κανόνας: η ίδια η τιμή του κωδικού σβήνεται όπου κι αν
    /// εμφανιστεί, και στην κωδικοποιημένη της μορφή μέσα σε URL.
    func testRedactorMasksTheVerySecretValues() {
        let secret = "ππππ 42"
        let line = "GET https://x.gr/a?q=%CF%80%CF%80%CF%80%CF%80%2042 [ππππ 42] body p=%CF%80%CF%80%CF%80%CF%80+42"
        let scrubbed = Redactor.scrub(line, secrets: [secret])
        XCTAssertFalse(scrubbed.contains("ππππ"), scrubbed)
        XCTAssertFalse(scrubbed.contains("%CF%80"), scrubbed)
        XCTAssertTrue(scrubbed.contains("https://x.gr/a?q="), scrubbed)
        // Πολύ κοντές τιμές δεν σβήνονται: θα κατέστρεφαν άσχετο κείμενο.
        XCTAssertEqual(Redactor.scrub("abc", secrets: ["ab"]), "abc")
    }

    // ----------------------------------------------------------- FileBridge

    func testFileBridgeStaysUnderItsRoot() throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let bridge = FileBridge(root: root)
        let pdf = Data("%PDF-1.4".utf8).base64EncodedString()

        XCTAssertEqual(bridge.write("../../escape.pdf", base64: pdf, append: false), "")
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("_/_/escape.pdf").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.deletingLastPathComponent().appendingPathComponent("escape.pdf").path))

        XCTAssertEqual(bridge.write("./sub/E1_123456783_2025.pdf", base64: pdf, append: false), "")
        XCTAssertEqual(bridge.size("sub/E1_123456783_2025.pdf"), 8)
        XCTAssertEqual(bridge.read("sub/E1_123456783_2025.pdf"), pdf)
        XCTAssertTrue(bridge.exists("sub"))
        XCTAssertNil(bridge.read("δεν-υπάρχει.pdf"))
        XCTAssertEqual(bridge.size("δεν-υπάρχει.pdf"), -1)
    }

    func testDiagnosticsAreNotWrittenUnlessAsked() {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let page = Data("<html>ΑΦΜ 123456783</html>".utf8).base64EncodedString()

        let quiet = FileBridge(root: root)
        XCTAssertEqual(quiet.write("01_aade_oam.html", base64: page, append: false), "")
        XCTAssertEqual(quiet.write("run.log", base64: page, append: true), "")
        XCTAssertFalse(quiet.exists("01_aade_oam.html"))
        XCTAssertFalse(quiet.exists("run.log"))
        XCTAssertEqual(quiet.write("data.json", base64: Data("{}".utf8).base64EncodedString(), append: false), "")
        XCTAssertTrue(quiet.exists("data.json"))

        let verbose = FileBridge(root: root, keepDiagnostics: true)
        XCTAssertEqual(verbose.write("run.log", base64: Data("a".utf8).base64EncodedString(), append: true), "")
        XCTAssertEqual(verbose.write("run.log", base64: Data("b".utf8).base64EncodedString(), append: true), "")
        XCTAssertEqual(verbose.size("run.log"), 2)
    }

    func testSegmentsAreSanitised() {
        XCTAssertEqual(FileBridge.sanitiseSegment(".."), "_")
        XCTAssertEqual(FileBridge.sanitiseSegment("a/b:c*d?.pdf"), "a_b_c_d_.pdf")
        XCTAssertEqual(FileBridge.sanitiseSegment("όνομα. "), "όνομα")
        XCTAssertEqual(FileBridge.sanitiseSegment(String(repeating: "α", count: 200) + ".pdf").count, 120)
    }

    // -------------------------------------------------------------- cookies

    /// Το URLSession ενώνει τα `Set-Cookie` με κόμματα. Το κόμμα υπάρχει όμως
    /// και μέσα στο `Expires` — ένας αφελής χωρισμός θα έκοβε τη συνεδρία στα δύο.
    func testSetCookieIsSplitOnCookieBoundariesOnly() {
        let merged = "JSESSIONID=abc; Path=/; HttpOnly, OAMAuthnCookie=x%2Fy==; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, lang=el"
        XCTAssertEqual(
            URLSessionTransport.splitSetCookie(merged),
            [
                "JSESSIONID=abc; Path=/; HttpOnly",
                "OAMAuthnCookie=x%2Fy==; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/",
                "lang=el",
            ]
        )
        XCTAssertEqual(URLSessionTransport.splitSetCookie(""), [])
        XCTAssertEqual(URLSessionTransport.splitSetCookie("a=b"), ["a=b"])
    }

    func testUrlsWithGreekAreEncodedNotRejected() {
        XCTAssertNotNil(URLSessionTransport.url(from: "https://www1.aade.gr/a?x=1&y=%CE%B1"))
        let greek = URLSessionTransport.url(from: "https://www1.aade.gr/a?form=Φ2")
        XCTAssertNotNil(greek)
        XCTAssertEqual(greek?.host, "www1.aade.gr")
    }

    // -------------------------------------------------------------- πελάτες

    func testAfmChecksum() {
        XCTAssertTrue(Client.isAfm("123456783"))
        XCTAssertTrue(Client.afmChecksumHolds("123456783"))
        XCTAssertFalse(Client.afmChecksumHolds("123456789"))
        XCTAssertFalse(Client.isAfm("12345678"))
        XCTAssertFalse(Client.isAfm("12345678α"))
    }

    @MainActor
    func testClientsSurviveARestartAndSecretsStayOutOfTheFile() throws {
        let support = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: support) }
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        let secrets = MemorySecretStore()

        var client = Client()
        client.afm = "123456783"
        client.name = "ΠΑΠΑΔΟΠΟΥΛΟΣ"
        client.firstName = "ΓΕΩΡΓΙΟΣ"
        client.kind = .sole

        let store = ClientStore(support: support, secrets: secrets)
        store.save(client, secrets: [.taxisUser: "testuser", .taxisPass: "  Abc123!x \n", .amka: "01019912345"])

        let reopened = ClientStore(support: support, secrets: secrets)
        XCTAssertEqual(reopened.clients.count, 1)
        XCTAssertEqual(reopened.clients.first?.displayName, "ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΕΩΡΓΙΟΣ")
        XCTAssertEqual(reopened.clients.first?.kind, .sole)
        // Τα κενά γύρω από τον κωδικό είναι ατύχημα επικόλλησης.
        XCTAssertEqual(reopened.secret(.taxisPass, for: client), "Abc123!x")

        let file = try String(contentsOf: support.appendingPathComponent("clients.json"), encoding: .utf8)
        XCTAssertFalse(file.contains("Abc123"), "κωδικός μέσα στο αρχείο πελατών")
        XCTAssertFalse(file.contains("testuser"))
        XCTAssertFalse(file.contains("01019912345"))

        // Η διαγραφή παίρνει μαζί κωδικούς και έντυπα.
        let folder = AppPaths.runDirectory(in: support, afm: client.afm, configId: "aade-income")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("%PDF".utf8).write(to: folder.appendingPathComponent("E1_123456783_2025.pdf"))
        XCTAssertEqual(DocumentFile.list(support: support, afm: client.afm).count, 1)

        reopened.delete(client)
        XCTAssertTrue(reopened.clients.isEmpty)
        XCTAssertEqual(reopened.secret(.taxisPass, for: client), "")
        XCTAssertTrue(DocumentFile.list(support: support, afm: client.afm).isEmpty)
    }

    // --------------------------------------------------------------- inputs

    @MainActor
    func testInputsFollowTheLoginEachConfigNeeds() throws {
        let support = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: support) }
        let store = ClientStore(support: support, secrets: MemorySecretStore())
        var client = Client()
        client.afm = "123456783"
        store.save(client, secrets: [.taxisUser: "testuser", .taxisPass: "testpass"])

        let e1 = try XCTUnwrap(DocumentCatalog.all.first { $0.id == "e1" })
        let inputs = try FetchRunner.inputs(for: FetchJob(item: e1, year: "2025"), client: client, store: store)
        XCTAssertEqual(inputs["user"], "testuser")
        XCTAssertEqual(inputs["pass"], "testpass")
        XCTAssertEqual(inputs["forms"], "E1")
        XCTAssertEqual(inputs["year"], "2025")
        XCTAssertEqual(inputs["vat"], "123456783")
        XCTAssertNil(inputs["amka"])

        // Τα ειδοποιητήρια ΕΦΚΑ θέλουν ΑΜΚΑ: χωρίς αυτό δεν ανοίγει καν σύνδεση.
        let notices = try XCTUnwrap(DocumentCatalog.all.first { $0.id == "efka-notices" })
        XCTAssertThrowsError(try FetchRunner.inputs(for: FetchJob(item: notices), client: client, store: store)) { error in
            XCTAssertTrue((error as? MissingCredentials)?.message.hasPrefix("Λείπει το ΑΜΚΑ") == true)
        }
        store.save(client, secrets: [.amka: "01019912345"])
        let withAmka = try FetchRunner.inputs(for: FetchJob(item: notices), client: client, store: store)
        XCTAssertEqual(withAmka["amka"], "01019912345")
        XCTAssertEqual(withAmka["afm"], "123456783")

        // Η καρτέλα εργοδότη θέλει κωδικούς ΙΚΑ — οι TAXISnet δεν κάνουν.
        let employer = try XCTUnwrap(DocumentCatalog.all.first { $0.id == "employer-efka" })
        XCTAssertThrowsError(try FetchRunner.inputs(for: FetchJob(item: employer, year: "2025"), client: client, store: store))

        // Έντυπο με έτος, χωρίς έτος: δεν ξεκινά.
        XCTAssertThrowsError(try FetchRunner.inputs(for: FetchJob(item: e1), client: client, store: store))
    }

    func testWrittenFilesIncludeRewrittenOnes() {
        let before = ["a.pdf": "1|10", "b.pdf": "1|10"]
        let after = ["a.pdf": "1|10", "b.pdf": "2|12", "c.pdf": "3|5"]
        XCTAssertEqual(FetchRunner.written(before: before, after: after).sorted(), ["b.pdf", "c.pdf"])
    }

    // ------------------------------------------------------------- κατάλογος

    func testCatalogMatchesTheKindOfClient() {
        let privateItems = DocumentCatalog.all.filter { $0.matches(.privateIndividual) }.map { $0.id }
        XCTAssertTrue(privateItems.contains("e1"))
        XCTAssertFalse(privateItems.contains("f2"), "ο ιδιώτης δεν υποβάλλει ΦΠΑ")
        XCTAssertFalse(privateItems.contains("fenp"))

        let legal = DocumentCatalog.all.filter { $0.matches(.legal) }.map { $0.id }
        XCTAssertTrue(legal.contains("fenp"))
        XCTAssertFalse(legal.contains("efka-notices"))

        // Άγνωστο είδος: δεν κρύβεται τίποτα.
        XCTAssertEqual(DocumentCatalog.all.filter { $0.matches(.unknown) }.count, DocumentCatalog.all.count)
        XCTAssertEqual(Set(DocumentCatalog.all.map { $0.id }).count, DocumentCatalog.all.count, "διπλό id στον κατάλογο")
        XCTAssertTrue(DocumentCatalog.all.allSatisfy { DocumentCatalog.groups.contains($0.group) })
    }

    func testOutcomeExplainsWhatHappened() {
        XCTAssertEqual(FetchOutcome.explain("InvalidCredentials").kind, .login)
        XCTAssertEqual(FetchOutcome.explain("NoDeclarationsRow").kind, .notFound)
        XCTAssertEqual(FetchOutcome.explain("LandPage").kind, .portal)
        XCTAssertEqual(FetchOutcome.explain("EtakEntry:_afrLoop not found").kind, .portal)
        XCTAssertEqual(FetchOutcome.explain("The request timed out.").kind, .network)
        XCTAssertEqual(FetchOutcome.explain("Λείπει το ΑΜΚΑ για τον πελάτη 123456783").kind, .missing)
        let unknown = FetchOutcome.explain("SomethingNew")
        XCTAssertEqual(unknown.kind, .portal)
        XCTAssertTrue(unknown.text.contains("SomethingNew"))
        XCTAssertTrue(FetchOutcome.empty("aade-debts").hasPrefix("Η σύνδεση έγινε"))
    }

    func testFileNamesBecomeDocumentNames() {
        XCTAssertEqual(DocumentNaming.title("Εκκαθαριστικό_123456783_2025.pdf"), "Εκκαθαριστικό δήλωσης 2025")
        XCTAssertEqual(DocumentNaming.title("E1_123456783_2025.pdf"), "Ε1 — δήλωση φορολογίας εισοδήματος 2025")
        // Το ποσό στο τέλος δεν είναι έτος.
        XCTAssertEqual(DocumentNaming.title("OFEILI_123456783_ΦΠΑ_2024,00.pdf"), "Ταυτότητα οφειλής")
        XCTAssertEqual(DocumentNaming.title("άγνωστο.pdf"), "άγνωστο.pdf")
    }
}
