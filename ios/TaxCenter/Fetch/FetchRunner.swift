import Foundation
import UIKit

/// Μία γραμμή της λήψης: ένα έντυπο, για ένα έτος.
struct FetchJob: Identifiable, Hashable {
    let id = UUID()
    let item: DocumentCatalog.Item
    var year: String = ""
    var month: String = ""

    var label: String {
        var text = item.label
        if !year.isEmpty { text += " · " + year }
        if !month.isEmpty { text += "/" + month }
        return text
    }
}

enum FetchStatus: Equatable {
    case pending
    case running
    /// Κατέβηκαν τόσα έντυπα.
    case found(Int)
    /// Η σύνδεση έγινε· αυτό που ζητήθηκε δεν υπάρχει.
    case empty(String)
    /// Δεν ξέρουμε αν υπάρχει έντυπο: δεν έγινε σύνδεση, ή κάτι χάλασε.
    case failed(FetchOutcome.Kind, String)
    case cancelled

    var finished: Bool {
        switch self {
        case .pending, .running: return false
        default: return true
        }
    }
}

struct FetchRow: Identifiable {
    let job: FetchJob
    var status: FetchStatus = .pending
    var files: [String] = []
    var id: UUID { job.id }
}

/// Ό,τι λείπει από την καρτέλα για να τρέξει μια διαδικασία.
struct MissingCredentials: Error {
    let message: String
}

/// Η ουρά λήψης. **Μία για όλη την εφαρμογή, και αυστηρά σειριακή.**
///
/// Δύο συνεδρίες GSIS ταυτόχρονα κλειδώνουν τον λογαριασμό του πελάτη
/// (`OAM-6`). Γι' αυτό δεν υπάρχει δεύτερο αντικείμενο αυτού του τύπου, και μια
/// νέα λήψη δεν ξεκινά όσο τρέχει άλλη.
@MainActor
final class FetchRunner: ObservableObject {

    @Published private(set) var rows: [FetchRow] = []
    @Published private(set) var running = false
    @Published private(set) var clientName = ""
    /// Ποιανού είναι οι γραμμές που φαίνονται — για το άνοιγμα των αρχείων τους.
    @Published private(set) var clientAfm = ""

    private let host: EngineHost
    private var task: Task<Void, Never>?

    init(host: EngineHost? = nil) {
        self.host = host ?? EngineHost()
    }

    var finished: Bool { !rows.isEmpty && !running }

    func clear() {
        guard !running else { return }
        rows = []
        clientName = ""
        clientAfm = ""
    }

    func cancel() {
        task?.cancel()
    }

    func start(client: Client, jobs: [FetchJob], store: ClientStore) {
        guard !running, !jobs.isEmpty else { return }
        rows = jobs.map { FetchRow(job: $0) }
        clientName = client.displayName
        clientAfm = client.afm
        running = true
        // Μια λήψη κρατά λεπτά· η οθόνη δεν πρέπει να σβήσει και να παγώσει τον engine.
        UIApplication.shared.isIdleTimerDisabled = true
        task = Task { [weak self] in
            await self?.run(client: client, store: store)
            UIApplication.shared.isIdleTimerDisabled = false
            self?.running = false
        }
    }

    private func run(client: Client, store: ClientStore) async {
        // Τα logins που απέρριψε η πύλη σε αυτή τη σειρά. Με την πρώτη απόρριψη
        // οι υπόλοιπες γραμμές με τους ίδιους κωδικούς **δεν τρέχουν**: κάθε
        // επιπλέον αποτυχία φέρνει τον λογαριασμό πιο κοντά στο κλείδωμα.
        var rejected: Set<String> = []

        for index in rows.indices {
            if Task.isCancelled {
                rows[index].status = .cancelled
                continue
            }
            let job = rows[index].job
            let loginKey = Self.loginKey(job.item.login)
            if rejected.contains(loginKey) {
                rows[index].status = .failed(
                    .login,
                    "Δεν δοκιμάστηκε: οι ίδιοι κωδικοί απορρίφθηκαν λίγο πριν. Διόρθωσέ τους στην καρτέλα."
                )
                continue
            }

            let inputs: [String: String]
            do {
                inputs = try Self.inputs(for: job, client: client, store: store)
            } catch let missing as MissingCredentials {
                rows[index].status = .failed(.missing, missing.message)
                continue
            } catch {
                rows[index].status = .failed(.missing, error.localizedDescription)
                continue
            }

            rows[index].status = .running
            let directory = AppPaths.runDirectory(in: store.support, afm: client.afm, configId: job.item.configId)
            let before = Self.snapshot(directory)
            let result = await host.run(
                configId: job.item.configId,
                inputs: inputs,
                outDir: directory,
                secrets: [inputs["pass"] ?? ""]
            )
            let produced = Self.written(before: before, after: Self.snapshot(directory))
                .filter { $0.lowercased().hasSuffix(".pdf") }
                .sorted()
            rows[index].files = produced

            if result.ok {
                rows[index].status = produced.isEmpty
                    ? .empty(FetchOutcome.empty(job.item.configId))
                    : .found(produced.count)
            } else {
                let explained = FetchOutcome.explain(result.reason)
                switch explained.kind {
                case .notFound:
                    rows[index].status = .empty(explained.text)
                case .login:
                    rejected.insert(loginKey)
                    rows[index].status = .failed(.login, explained.text)
                default:
                    rows[index].status = .failed(explained.kind, explained.text)
                }
            }
        }
    }

    private static func loginKey(_ login: DocumentCatalog.Login) -> String {
        switch login {
        case .taxis, .taxisWithAmka: return "taxis"
        case .ikaEmployer: return "ika"
        }
    }

    /// Τα inputs που περιμένει το config — βλ. `ProcessRunner.buildInputs` στο Android.
    ///
    /// Οι κωδικοί διαβάζονται εδώ από το Keychain, ζουν όσο διαρκεί η εκτέλεση
    /// και δεν γράφονται πουθενά.
    static func inputs(for job: FetchJob, client: Client, store: ClientStore) throws -> [String: String] {
        let login = job.item.login
        let user: String
        let pass: String
        switch login {
        case .taxis, .taxisWithAmka:
            user = store.secret(.taxisUser, for: client)
            pass = store.secret(.taxisPass, for: client)
            if user.isEmpty || pass.isEmpty {
                throw MissingCredentials(message: "Λείπουν κωδικοί TAXISnet για τον πελάτη \(client.afm)")
            }
        case .ikaEmployer:
            user = store.secret(.ikaUser, for: client)
            pass = store.secret(.ikaPass, for: client)
            if user.isEmpty || pass.isEmpty {
                throw MissingCredentials(message: "Λείπουν κωδικοί ΙΚΑ εργοδότη για τον πελάτη \(client.afm)")
            }
        }

        var inputs: [String: String] = ["user": user, "pass": pass, "vat": client.afm]
        if login == .taxisWithAmka {
            let amka = store.secret(.amka, for: client)
            if amka.isEmpty {
                throw MissingCredentials(message: "Λείπει το ΑΜΚΑ για τον πελάτη \(client.afm)")
            }
            inputs["afm"] = client.afm
            inputs["amka"] = amka
        }
        for (key, value) in job.item.inputs { inputs[key] = value }
        if job.item.needsYear {
            if job.year.isEmpty { throw MissingCredentials(message: "Λείπει το έτος για το έντυπο «\(job.item.label)»") }
            inputs["year"] = job.year
        }
        if job.item.needsMonth && !job.month.isEmpty { inputs["month"] = job.month }
        return inputs
    }

    // ---------------------------------------------------------------- αρχεία

    /// Όνομα -> (χρόνος τροποποίησης, μέγεθος) για κάθε αρχείο του φακέλου.
    nonisolated static func snapshot(_ directory: URL) -> [String: String] {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: directory.path) else { return [:] }
        var out: [String: String] = [:]
        for name in names {
            let path = directory.appendingPathComponent(name).path
            guard let attributes = try? fm.attributesOfItem(atPath: path),
                  (attributes[.type] as? FileAttributeType) == .typeRegular
            else { continue }
            let modified = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
            let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            out[name] = "\(modified)|\(size)"
        }
        return out
    }

    /// Ό,τι **γράφτηκε** σε αυτή την εκτέλεση — νέο αρχείο ή ξαναγραμμένο. Η
    /// δεύτερη λήψη του ίδιου εκκαθαριστικού γράφει στο ίδιο όνομα.
    nonisolated static func written(before: [String: String], after: [String: String]) -> [String] {
        after.filter { before[$0.key] != $0.value }.map { $0.key }
    }
}
