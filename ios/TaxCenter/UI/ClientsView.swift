import SwiftUI

/// Η λίστα πελατών — η αρχική οθόνη.
struct ClientsView: View {

    @EnvironmentObject private var store: ClientStore
    @EnvironmentObject private var fetch: FetchRunner

    @State private var query = ""
    @State private var adding = false
    @State private var showingAbout = false

    private var filtered: [Client] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return store.clients }
        return store.clients.filter { $0.afm.contains(q) || $0.displayName.lowercased().contains(q) }
    }

    var body: some View {
        List {
            if store.clients.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Δεν υπάρχει ακόμη πελάτης.")
                            .font(.headline)
                        Text("Πάτησε το «+» για να καταχωρήσεις τον πρώτο: ΑΦΜ, επωνυμία και κωδικούς TAXISnet. Μετά διαλέγεις έντυπα και η εφαρμογή τα κατεβάζει από την ΑΑΔΕ και τον e-ΕΦΚΑ.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                }
            }

            if fetch.running {
                Section {
                    Label("Τρέχει λήψη για: \(fetch.clientName)", systemImage: "arrow.down.circle")
                        .font(.subheadline)
                }
            }

            ForEach(filtered) { client in
                NavigationLink(value: client.id) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(client.displayName)
                        Text(client.afm)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Πελάτες")
        .navigationDestination(for: UUID.self) { id in
            ClientDetailView(clientId: id)
        }
        .searchable(text: $query, prompt: "ΑΦΜ ή επωνυμία")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    showingAbout = true
                } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel("Πληροφορίες")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    adding = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Νέος πελάτης")
            }
        }
        .sheet(isPresented: $adding) {
            NavigationStack {
                ClientEditView(client: nil)
            }
        }
        .sheet(isPresented: $showingAbout) {
            NavigationStack {
                AboutView()
            }
        }
    }
}
