import Foundation

/// Ένα έντυπο που έχει κατέβει για έναν πελάτη.
///
/// Δεν υπάρχει βάση εγγράφων: η αλήθεια είναι ο ίδιος ο φάκελος
/// `runs/<ΑΦΜ>/<configId>/`. Ό,τι υπάρχει εκεί φαίνεται, ό,τι σβηστεί χάνεται —
/// και δεν μπορεί να μείνει εγγραφή που δείχνει σε αρχείο που δεν υπάρχει πια.
struct DocumentFile: Identifiable, Hashable {
    let url: URL
    let configId: String
    let modified: Date
    let size: Int64

    var id: String { url.path }
    var name: String { url.lastPathComponent }
    var title: String { DocumentNaming.title(name) }

    /// Τα PDF του πελάτη, νεότερα πρώτα.
    static func list(support: URL, afm: String) -> [DocumentFile] {
        guard !afm.isEmpty else { return [] }
        let fm = FileManager.default
        let root = AppPaths.runs(in: support)
            .appendingPathComponent(FileBridge.sanitiseSegment(afm), isDirectory: true)
        var out: [DocumentFile] = []
        for folder in (try? fm.contentsOfDirectory(atPath: root.path)) ?? [] {
            let directory = root.appendingPathComponent(folder, isDirectory: true)
            for name in (try? fm.contentsOfDirectory(atPath: directory.path)) ?? [] {
                guard name.lowercased().hasSuffix(".pdf") else { continue }
                let url = directory.appendingPathComponent(name)
                let attributes = try? fm.attributesOfItem(atPath: url.path)
                out.append(
                    DocumentFile(
                        url: url,
                        configId: folder,
                        modified: (attributes?[.modificationDate] as? Date) ?? Date.distantPast,
                        size: (attributes?[.size] as? NSNumber)?.int64Value ?? 0
                    )
                )
            }
        }
        return out.sorted { $0.modified > $1.modified }
    }

    static func delete(_ file: DocumentFile) {
        try? FileManager.default.removeItem(at: file.url)
    }
}
