import Foundation

/// Πού γράφει η εφαρμογή.
///
/// Όλα κάτω από το `Application Support/TaxCenter`, ιδιωτικό στην εφαρμογή και
/// **εκτός αντιγράφων ασφαλείας**: φορολογικά έντυπα πελατών δεν έχουν θέση σε
/// ένα iCloud backup που κανείς δεν θυμάται ότι υπάρχει.
enum AppPaths {

    static var support: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return prepared(base.appendingPathComponent("TaxCenter", isDirectory: true))
    }

    /// `runs/<ΑΦΜ>/<configId>/` — ίδια διάταξη με το Android.
    static func runs(in support: URL) -> URL {
        prepared(support.appendingPathComponent("runs", isDirectory: true))
    }

    static func runDirectory(in support: URL, afm: String, configId: String) -> URL {
        runs(in: support)
            .appendingPathComponent(FileBridge.sanitiseSegment(afm), isDirectory: true)
            .appendingPathComponent(FileBridge.sanitiseSegment(configId), isDirectory: true)
    }

    private static func prepared(_ directory: URL) -> URL {
        var url = directory
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }
}

/// Οι πελάτες του γραφείου.
///
/// Δύο αποθήκες, επίτηδες χωριστές:
///
///  * `clients.json` — ονόματα, ΑΦΜ, στοιχεία επικοινωνίας. Κρυπτογραφημένο από
///    το σύστημα όσο η συσκευή είναι κλειδωμένη.
///  * **Keychain** — κωδικοί TAXISnet, ΑΜΚΑ, κωδικοί ΙΚΑ. Τίποτα από αυτά δεν
///    αγγίζει αρχείο.
@MainActor
final class ClientStore: ObservableObject {

    @Published private(set) var clients: [Client] = []

    let support: URL
    private let secrets: SecretStore
    private let file: URL

    init(support: URL = AppPaths.support, secrets: SecretStore = KeychainStore()) {
        self.support = support
        self.secrets = secrets
        self.file = support.appendingPathComponent("clients.json")
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: file) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        clients = ((try? decoder.decode([Client].self, from: data)) ?? []).sorted(by: Self.order)
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(clients) else { return }
        try? data.write(to: file, options: [.atomic, .completeFileProtection])
    }

    private static func order(_ a: Client, _ b: Client) -> Bool {
        a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
    }

    func client(_ id: UUID) -> Client? { clients.first { $0.id == id } }

    /// Υπάρχει ήδη άλλος πελάτης με αυτό το ΑΦΜ;
    func afmTaken(_ afm: String, except id: UUID) -> Bool {
        clients.contains { $0.afm == afm && $0.id != id }
    }

    func save(_ client: Client, secrets values: [SecretField: String]) {
        var updated = client
        updated.updatedAt = Date()
        if let index = clients.firstIndex(where: { $0.id == client.id }) {
            clients[index] = updated
        } else {
            clients.append(updated)
        }
        clients.sort(by: Self.order)
        for (field, value) in values {
            // Κενά γύρω από κωδικό είναι σχεδόν πάντα ατύχημα επικόλλησης, και
            // στο GSIS μετρούν ως λάθος κωδικός.
            secrets.set(value.trimmingCharacters(in: .whitespacesAndNewlines), for: key(client.id, field))
        }
        persist()
    }

    func secret(_ field: SecretField, for client: Client) -> String {
        secrets.get(key(client.id, field))
    }

    /// Διαγραφή πελάτη: η εγγραφή, **οι κωδικοί** και **όλα τα έντυπά του**.
    func delete(_ client: Client) {
        clients.removeAll { $0.id == client.id }
        secrets.removeAll(prefix: client.id.uuidString + ".")
        if !client.afm.isEmpty {
            let folder = AppPaths.runs(in: support)
                .appendingPathComponent(FileBridge.sanitiseSegment(client.afm), isDirectory: true)
            try? FileManager.default.removeItem(at: folder)
        }
        persist()
    }

    /// Τα πάντα: πελάτες, κωδικοί, έντυπα.
    func eraseEverything() {
        for client in clients { secrets.removeAll(prefix: client.id.uuidString + ".") }
        clients = []
        try? FileManager.default.removeItem(at: AppPaths.runs(in: support))
        try? FileManager.default.removeItem(at: file)
    }

    private func key(_ id: UUID, _ field: SecretField) -> String {
        id.uuidString + "." + field.rawValue
    }
}
