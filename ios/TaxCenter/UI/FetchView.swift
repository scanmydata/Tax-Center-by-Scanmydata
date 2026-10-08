import QuickLook
import SwiftUI

/// Λήψη εντύπων για έναν πελάτη: διαλέγεις έντυπα και έτος, και βλέπεις τι
/// συνέβη σε κάθε γραμμή.
struct FetchView: View {

    let client: Client

    @EnvironmentObject private var store: ClientStore
    @EnvironmentObject private var fetch: FetchRunner

    @State private var picked: Set<String> = []
    @State private var year = FetchView.defaultYear
    @State private var month = ""
    @State private var preview: URL?

    /// Το προηγούμενο έτος: τον περισσότερο χρόνο αυτό ζητείται (η δήλωση του
    /// 2026 αφορά τα εισοδήματα του 2025).
    private static var defaultYear: String {
        String(Calendar.current.component(.year, from: Date()) - 1)
    }

    private static var years: [String] {
        let now = Calendar.current.component(.year, from: Date())
        return (0..<8).map { String(now - $0) }
    }

    private var chosen: [DocumentCatalog.Item] {
        DocumentCatalog.all.filter { picked.contains($0.id) }
    }

    var body: some View {
        Group {
            if fetch.rows.isEmpty {
                picker
            } else {
                progress
            }
        }
        .navigationTitle("Λήψη εντύπων")
        .navigationBarTitleDisplayMode(.inline)
        .quickLookPreview($preview)
    }

    // -------------------------------------------------------------- επιλογή

    private var picker: some View {
        List {
            Section {
                Picker("Έτος", selection: $year) {
                    ForEach(Self.years, id: \.self) { value in
                        Text(value).tag(value)
                    }
                }
                if chosen.contains(where: { $0.needsMonth }) {
                    Picker("Μήνας", selection: $month) {
                        Text("Ο πιο πρόσφατος").tag("")
                        ForEach(1...12, id: \.self) { value in
                            Text(String(value)).tag(String(value))
                        }
                    }
                }
            } footer: {
                Text("Το έτος ισχύει για όσα έντυπα το χρειάζονται. Η εφαρμογή συνδέεται με τους κωδικούς του πελάτη, ένα έντυπο τη φορά.")
            }

            ForEach(DocumentCatalog.groups, id: \.self) { group in
                let items = DocumentCatalog.items(in: group, for: client.kind)
                if !items.isEmpty {
                    Section(group) {
                        ForEach(items) { item in
                            Button {
                                toggle(item)
                            } label: {
                                HStack(alignment: .top, spacing: 10) {
                                    Image(systemName: picked.contains(item.id) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(picked.contains(item.id) ? Color.accentColor : Color.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.label)
                                            .foregroundStyle(.primary)
                                        if !item.note.isEmpty {
                                            Text(item.note)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                start()
            } label: {
                Text(picked.isEmpty ? "Διάλεξε έντυπα" : (picked.count == 1 ? "Λήψη 1 εντύπου" : "Λήψη \(picked.count) εντύπων"))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(picked.isEmpty)
            .padding()
            .background(.bar)
        }
    }

    private func toggle(_ item: DocumentCatalog.Item) {
        if picked.contains(item.id) { picked.remove(item.id) } else { picked.insert(item.id) }
    }

    private func start() {
        let jobs = chosen.map { item in
            FetchJob(item: item, year: item.needsYear ? year : "", month: item.needsMonth ? month : "")
        }
        fetch.start(client: client, jobs: jobs, store: store)
    }

    // -------------------------------------------------------------- πρόοδος

    private var progress: some View {
        List {
            Section {
                ForEach(fetch.rows) { row in
                    FetchRowView(row: row) { name in
                        preview = AppPaths.runDirectory(in: store.support, afm: fetch.clientAfm, configId: row.job.item.configId)
                            .appendingPathComponent(name)
                    }
                }
            } header: {
                Text(fetch.clientName)
            } footer: {
                Text(fetch.running
                    ? "Κράτησε την εφαρμογή ανοιχτή: στο παρασκήνιο το iOS παγώνει τη λήψη."
                    : summary)
            }

            Section {
                if fetch.running {
                    Button("Διακοπή μετά το τρέχον έντυπο", role: .destructive) { fetch.cancel() }
                } else {
                    Button("Νέα λήψη") { fetch.clear() }
                }
            }
        }
    }

    private var summary: String {
        var found = 0, empty = 0, noLogin = 0, broken = 0
        for row in fetch.rows {
            switch row.status {
            case .found: found += 1
            case .empty: empty += 1
            case .failed(let kind, _):
                if kind == .login || kind == .missing { noLogin += 1 } else { broken += 1 }
            default: break
            }
        }
        var parts = ["Τέλος — \(found)/\(fetch.rows.count) με έντυπα"]
        if empty > 0 { parts.append("\(empty) χωρίς έντυπο") }
        if noLogin > 0 { parts.append("\(noLogin) χωρίς σύνδεση") }
        if broken > 0 { parts.append("\(broken) με σφάλμα") }
        return parts.joined(separator: " · ")
    }
}

/// Η κατάσταση μιας γραμμής: **τι συνέβη**, και από κάτω γιατί και τι κάνεις τώρα.
private struct FetchRowView: View {

    let row: FetchRow
    let onOpen: (String) -> Void

    private var headline: String {
        switch row.status {
        case .pending: return "Σε αναμονή"
        case .running: return "Εκτελείται…"
        case .found(let count): return count == 1 ? "✓ Βρέθηκε 1 έντυπο" : "✓ Βρέθηκαν \(count) έντυπα"
        case .empty: return "— Δεν βρέθηκε έντυπο"
        case .failed(let kind, _): return "✗ " + kind.title
        case .cancelled: return "Διακόπηκε πριν ξεκινήσει"
        }
    }

    private var detail: String {
        switch row.status {
        case .empty(let text): return text
        case .failed(_, let text): return text
        default: return ""
        }
    }

    private var tone: Color {
        switch row.status {
        case .found: return .green
        case .empty: return .orange
        case .failed: return .red
        default: return .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(row.job.label)
            HStack(spacing: 6) {
                if row.status == .running {
                    ProgressView()
                }
                Text(headline)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(tone)
            }
            if !detail.isEmpty {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(row.files, id: \.self) { name in
                Button {
                    onOpen(name)
                } label: {
                    Label(DocumentNaming.title(name), systemImage: "doc.text")
                        .font(.footnote)
                }
                .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }
}
