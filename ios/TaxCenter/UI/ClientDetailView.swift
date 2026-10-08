import QuickLook
import SwiftUI

/// Η καρτέλα ενός πελάτη: στοιχεία, λήψη, και τα έντυπα που έχουν κατέβει.
struct ClientDetailView: View {

    let clientId: UUID

    @EnvironmentObject private var store: ClientStore
    @EnvironmentObject private var fetch: FetchRunner

    @State private var documents: [DocumentFile] = []
    @State private var editing = false
    @State private var preview: URL?

    var body: some View {
        if let client = store.client(clientId) {
            content(client)
        } else {
            Text("Ο πελάτης δεν υπάρχει πια.")
                .foregroundStyle(.secondary)
        }
    }

    private func content(_ client: Client) -> some View {
        List {
            Section {
                LabeledContent("ΑΦΜ", value: client.afm)
                if client.kind != .unknown {
                    LabeledContent("Είδος", value: client.kind.label)
                }
                if !client.doy.isEmpty {
                    LabeledContent("ΔΟΥ", value: client.doy)
                }
                if !client.email.isEmpty {
                    LabeledContent("Email", value: client.email)
                }
                if !client.mobile.isEmpty {
                    LabeledContent("Κινητό", value: client.mobile)
                }
            }

            Section {
                NavigationLink {
                    FetchView(client: client)
                } label: {
                    Label("Λήψη εντύπων", systemImage: "arrow.down.doc")
                }
            } footer: {
                if store.secret(.taxisUser, for: client).isEmpty {
                    Text("Δεν έχουν καταχωρηθεί κωδικοί TAXISnet — πάτησε «Διόρθωση».")
                }
            }

            Section {
                if documents.isEmpty {
                    Text("Δεν έχει κατέβει ακόμη έντυπο για αυτόν τον πελάτη.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(documents) { document in
                    Button {
                        preview = document.url
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(document.title)
                                .foregroundStyle(.primary)
                            Text(document.name)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                            Text(Self.stamp(document.modified))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .contextMenu {
                        ShareLink(item: document.url) {
                            Label("Αποστολή", systemImage: "square.and.arrow.up")
                        }
                        Button(role: .destructive) {
                            remove(document, client)
                        } label: {
                            Label("Διαγραφή", systemImage: "trash")
                        }
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            remove(document, client)
                        } label: {
                            Label("Διαγραφή", systemImage: "trash")
                        }
                    }
                }
            } header: {
                Text(documents.isEmpty ? "Έγγραφα" : "Έγγραφα (\(documents.count))")
            } footer: {
                if !documents.isEmpty {
                    Text("Πάτησε για άνοιγμα. Κράτησε πατημένο για αποστολή με email, Viber ή όποια εφαρμογή έχει η συσκευή.")
                }
            }
        }
        .navigationTitle(client.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if !documents.isEmpty {
                    ShareLink(items: documents.map { $0.url }) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel("Αποστολή όλων των εγγράφων")
                }
                Button("Διόρθωση") { editing = true }
            }
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                ClientEditView(client: client)
            }
        }
        .quickLookPreview($preview)
        .onAppear { reload(client) }
        .onChange(of: fetch.running) { _ in reload(client) }
    }

    private func reload(_ client: Client) {
        documents = DocumentFile.list(support: store.support, afm: client.afm)
    }

    private func remove(_ document: DocumentFile, _ client: Client) {
        DocumentFile.delete(document)
        reload(client)
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "el_GR")
        formatter.timeZone = TimeZone(identifier: "Europe/Athens")
        formatter.dateFormat = "dd/MM/yyyy HH:mm"
        return formatter
    }()

    private static func stamp(_ date: Date) -> String {
        formatter.string(from: date)
    }
}
