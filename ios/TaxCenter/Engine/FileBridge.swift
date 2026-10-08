import Foundation

/// Τα αρχεία του engine, κλεισμένα κάτω από **έναν** φάκελο.
///
/// Ο engine γράφει με σχετικές διαδρομές (`fs.writeFileSync`). Κάθε διαδρομή
/// καθαρίζεται τμήμα προς τμήμα και ξαναχτίζεται κάτω από τη ρίζα: ένα όνομα
/// αρχείου που έρχεται από πύλη (`../../κάτι`) δεν μπορεί να γράψει αλλού.
///
/// Ό,τι δεν είναι PDF ή JSON είναι διαγνωστικό του engine — αντίγραφα σελίδων
/// της πύλης, με τα στοιχεία του πελάτη μέσα. Δεν γράφονται στον δίσκο, εκτός
/// αν ζητηθεί ρητά.
final class FileBridge {

    let root: URL
    private let keepDiagnostics: Bool
    private let fm = FileManager.default

    init(root: URL, keepDiagnostics: Bool = false) {
        self.root = root.standardizedFileURL
        self.keepDiagnostics = keepDiagnostics
        try? fm.createDirectory(at: self.root, withIntermediateDirectories: true)
    }

    /// Επιστρέφει κενό σε επιτυχία, αλλιώς το μήνυμα σφάλματος — όπως το περιμένει το shim.
    func write(_ path: String, base64: String, append: Bool) -> String {
        guard let target = resolve(path) else { return "διαδρομή εκτός ρίζας: \(path)" }
        if !keepDiagnostics && Self.isDiagnostic(target.lastPathComponent) { return "" }
        guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
            return "μη έγκυρα δεδομένα"
        }
        do {
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if append, fm.fileExists(atPath: target.path) {
                let handle = try FileHandle(forWritingTo: target)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } else {
                // Κρυπτογραφημένα από το σύστημα όσο η συσκευή είναι κλειδωμένη.
                try data.write(to: target, options: [.atomic, .completeFileProtection])
            }
            return ""
        } catch {
            return error.localizedDescription
        }
    }

    /// Base64, ή `nil` όταν το αρχείο δεν υπάρχει.
    func read(_ path: String) -> String? {
        guard let target = resolve(path), isFile(target), let data = try? Data(contentsOf: target) else { return nil }
        return data.base64EncodedString()
    }

    func exists(_ path: String) -> Bool {
        guard let target = resolve(path) else { return false }
        return fm.fileExists(atPath: target.path)
    }

    /// Μέγεθος σε bytes, ή `-1` όταν δεν είναι αρχείο.
    func size(_ path: String) -> Int {
        guard let target = resolve(path), isFile(target),
              let attributes = try? fm.attributesOfItem(atPath: target.path),
              let size = attributes[.size] as? NSNumber
        else { return -1 }
        return size.intValue
    }

    func mkdirs(_ path: String) -> String {
        guard let target = resolve(path) else { return "διαδρομή εκτός ρίζας: \(path)" }
        do {
            try fm.createDirectory(at: target, withIntermediateDirectories: true)
            return ""
        } catch {
            return error.localizedDescription
        }
    }

    private func isFile(_ url: URL) -> Bool {
        var directory: ObjCBool = false
        return fm.fileExists(atPath: url.path, isDirectory: &directory) && !directory.boolValue
    }

    /// Η διαδρομή κάτω από τη ρίζα, ή `nil` αν — παρά τον καθαρισμό — βγαίνει έξω.
    func resolve(_ path: String) -> URL? {
        let segments = path.replacingOccurrences(of: "\\", with: "/")
            .split(separator: "/", omittingEmptySubsequences: true)
            .map(String.init)
            .filter { $0 != "." }
            .map(Self.sanitiseSegment)
        var target = root
        for segment in segments { target.appendPathComponent(segment) }
        target = target.standardizedFileURL
        let rootPath = root.path
        guard target.path == rootPath || target.path.hasPrefix(rootPath + "/") else { return nil }
        return target
    }

    static func isDiagnostic(_ name: String) -> Bool {
        let lower = name.lowercased()
        return !(lower.hasSuffix(".pdf") || lower.hasSuffix(".json"))
    }

    private static let illegal = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.controlCharacters)
    private static let maxSegment = 120

    /// Ένα τμήμα διαδρομής που δεν μπορεί να ανέβει φάκελο ούτε να μπερδέψει το filesystem.
    static func sanitiseSegment(_ raw: String) -> String {
        var s = String(raw.unicodeScalars.map { illegal.contains($0) ? "_" : Character($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if s == ".." || s.isEmpty { s = "_" }
        while let last = s.last, last == "." || last == " " { s.removeLast() }
        if s.isEmpty { s = "_" }
        if s.count > maxSegment {
            if let dot = s.lastIndex(of: "."), dot != s.startIndex, s.distance(from: dot, to: s.endIndex) <= 6 {
                let ext = String(s[dot...])
                s = String(s.prefix(maxSegment - ext.count)) + ext
            } else {
                s = String(s.prefix(maxSegment))
            }
        }
        return s
    }
}
