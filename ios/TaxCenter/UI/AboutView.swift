import SwiftUI

/// Πληροφορίες: τι κάνει αυτή η έκδοση, πού μένουν τα δεδομένα, και η διαγραφή τους.
struct AboutView: View {

    @EnvironmentObject private var store: ClientStore
    @EnvironmentObject private var fetch: FetchRunner
    @Environment(\.dismiss) private var dismiss

    @State private var confirmErase = false

    private var version: String {
        let info = Bundle.main.infoDictionary
        let name = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "\(name) (\(build))"
    }

    var body: some View {
        List {
            Section {
                LabeledContent("Έκδοση", value: version)
                LabeledContent("Πελάτες", value: String(store.clients.count))
            } header: {
                Text("ScanMyData Tax Center")
            } footer: {
                Text("Πρώτη έκδοση για iOS. Χρησιμοποιεί τον ίδιο μηχανισμό λήψης με την εφαρμογή του Android. Δεν έχουν μεταφερθεί ακόμη: εισαγωγή από Excel, αποστολή με Gmail, καρτέλα οφειλών και ΚΕΑΟ, αιτήματα ΑΑΔΕ, αυτόματες ενημερώσεις, αντίγραφο στο Drive.")
            }

            Section("Προστασία δεδομένων") {
                Label("Όλα μένουν στη συσκευή. Η εφαρμογή μιλά μόνο με τις πύλες του Δημοσίου, με τους κωδικούς του κάθε πελάτη.", systemImage: "iphone")
                Label("Οι κωδικοί φυλάσσονται στο Keychain, όχι σε αρχείο.", systemImage: "key")
                Label("Πελάτες και έντυπα δεν μπαίνουν σε αντίγραφα ασφαλείας ούτε στο iCloud.", systemImage: "icloud.slash")
                Label("Καμία τηλεμετρία, καμία διαφήμιση.", systemImage: "eye.slash")
                if !AppLock.deviceHasPasscode {
                    Label("Η συσκευή δεν έχει κωδικό κλειδώματος: η εφαρμογή ανοίγει χωρίς ταυτοποίηση, και το σύστημα δεν κρυπτογραφεί τα αρχεία της. Όρισε κωδικό στις Ρυθμίσεις του iPhone.", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }
            .font(.subheadline)

            Section {
                Button("Διαγραφή όλων των δεδομένων", role: .destructive) {
                    confirmErase = true
                }
                .disabled(fetch.running)
            } footer: {
                Text("Σβήνει όλους τους πελάτες, τους κωδικούς τους και κάθε έντυπο από αυτή τη συσκευή. Δεν αναιρείται.")
            }
        }
        .navigationTitle("Πληροφορίες")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Κλείσιμο") { dismiss() }
            }
        }
        .confirmationDialog(
            "Διαγραφή όλων των πελατών, των κωδικών και των εντύπων;",
            isPresented: $confirmErase,
            titleVisibility: .visible
        ) {
            Button("Διαγραφή όλων", role: .destructive) {
                fetch.clear()
                store.eraseEverything()
            }
            Button("Άκυρο", role: .cancel) {}
        }
    }
}
