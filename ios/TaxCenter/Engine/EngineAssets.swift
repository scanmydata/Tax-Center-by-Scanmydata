import Foundation

/// Ο engine, όπως πακεταρίστηκε: ο φάκελος `engine/` του bundle.
///
/// Είναι **ο ίδιος φάκελος** με του Android (`app/src/main/assets/engine`) — τα
/// configs των πυλών, το `hyper-http.js`, τα shims. Ό,τι αλλάζει στον runner
/// φτάνει και στις δύο εφαρμογές με το ίδιο commit.
final class EngineAssets {

    static let shared = EngineAssets()

    private let root: URL?
    private var cache: [String: String] = [:]
    private let lock = NSLock()

    init(bundle: Bundle = .main) {
        root = bundle.url(forResource: "engine", withExtension: nil)
    }

    /// Λείπει ο φάκελος; Τότε το build είναι χαλασμένο και καμία λήψη δεν θα τρέξει.
    var available: Bool { root != nil && !shims.isEmpty }

    func read(_ relative: String) -> String? {
        guard let root else { return nil }
        return try? String(contentsOf: root.appendingPathComponent(relative), encoding: .utf8)
    }

    /// Πηγαίος κώδικας module για το `require` του shims.js.
    ///
    /// Ίδια σειρά αναζήτησης με το Android: πρώτα η ρίζα του engine, μετά ο
    /// φάκελος `configs/`. Το όνομα έρχεται από JavaScript, οπότε δεν του
    /// επιτρέπεται να βγει από τον φάκελο.
    func moduleSource(_ name: String) -> String? {
        guard !name.isEmpty, !name.contains("/"), !name.contains("\\"), !name.contains("..") else { return nil }
        lock.lock()
        defer { lock.unlock() }
        if let hit = cache[name] { return hit }
        guard let source = read("\(name).js") ?? read("configs/\(name).js") else { return nil }
        cache[name] = source
        return source
    }

    var shims: String { read("shims.js") ?? "" }
    var runner: String { read("runner.js") ?? "" }

    /// Τα ids όλων των configs του καταλόγου (`configs.json`).
    func configIds() -> [String] {
        guard let raw = read("configs.json"), let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let configs = root["configs"] as? [[String: Any]]
        else { return [] }
        return configs.compactMap { $0["id"] as? String }
    }
}
