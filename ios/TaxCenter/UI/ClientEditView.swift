import SwiftUI

/// Καταχώρηση και διόρθωση πελάτη.
struct ClientEditView: View {

    @EnvironmentObject private var store: ClientStore
    @Environment(\.dismiss) private var dismiss

    private let isNew: Bool

    @State private var draft: Client
    @State private var taxisUser = ""
    @State private var taxisPass = ""
    @State private var amka = ""
    @State private var ikaUser = ""
    @State private var ikaPass = ""
    @State private var loaded = false
    @State private var confirmDelete = false

    // Στη **δημιουργία** το συνθηματικό TAXISnet φαίνεται: εκεί πληκτρολογείται
    // και αμέσως δοκιμάζεται στο GSIS, και ένα αόρατο λάθος πλήκτρο είναι
    // αποτυχημένη σύνδεση. Σε υπάρχουσα καρτέλα μένει κρυφό.
    @State private var revealTaxis: Bool
    @State private var revealOther = false

    init(client: Client?) {
        isNew = client == nil
        _draft = State(initialValue: client ?? Client())
        _revealTaxis = State(initialValue: client == nil)
    }

    private var afm: String { draft.afm.trimmingCharacters(in: .whitespaces) }

    private var problem: String {
        if !Client.isAfm(afm) { return "Ο ΑΦΜ έχει εννέα ψηφία." }
        if store.afmTaken(afm, except: draft.id) { return "Υπάρχει ήδη πελάτης με αυτόν τον ΑΦΜ." }
        return ""
    }

    var body: some View {
        Form {
            Section {
                TextField("ΑΦΜ", text: $draft.afm)
                    .keyboardType(.numberPad)
                    // Ο ΑΦΜ ονομάζει τον φάκελο των εντύπων· δεν αλλάζει εκ των υστέρων.
                    .disabled(!isNew)
                if Client.isAfm(afm) && !Client.afmChecksumHolds(afm) {
                    Text("Το ψηφίο ελέγχου δεν ταιριάζει — ξανακοίτα τον ΑΦΜ.")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                TextField("Επώνυμο ή επωνυμία", text: $draft.name)
                TextField("Όνομα", text: $draft.firstName)
                Picker("Είδος", selection: $draft.kind) {
                    ForEach(ClientKind.allCases) { kind in
                        Text(kind.label).tag(kind)
                    }
                }
                TextField("ΔΟΥ", text: $draft.doy)
            } header: {
                Text("Στοιχεία")
            } footer: {
                Text("Το είδος κρίνει ποια έντυπα προτείνονται στη λήψη.")
            }

            Section("Επικοινωνία") {
                TextField("Email", text: $draft.email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Κινητό", text: $draft.mobile)
                    .keyboardType(.phonePad)
            }

            Section {
                SecretInput(title: "Όνομα χρήστη", text: $taxisUser, reveal: true)
                SecretInput(title: "Συνθηματικό", text: $taxisPass, reveal: revealTaxis)
                Toggle("Εμφάνιση συνθηματικού", isOn: $revealTaxis)
            } header: {
                Text("TAXISnet")
            } footer: {
                Text("Οι κωδικοί φυλάσσονται στο Keychain της συσκευής — όχι σε αρχείο της εφαρμογής, όχι σε αντίγραφο ασφαλείας, όχι στο iCloud.")
            }

            Section {
                SecretInput(title: "ΑΜΚΑ", text: $amka, reveal: revealOther, numeric: true)
                SecretInput(title: "Χρήστης ΙΚΑ εργοδότη", text: $ikaUser, reveal: true)
                SecretInput(title: "Κωδικός ΙΚΑ εργοδότη", text: $ikaPass, reveal: revealOther)
                Toggle("Εμφάνιση ΑΜΚΑ και κωδικού ΙΚΑ", isOn: $revealOther)
            } header: {
                Text("e-ΕΦΚΑ")
            } footer: {
                Text("Το ΑΜΚΑ χρειάζεται για ειδοποιητήρια, βεβαιώσεις και ΑΤΛΑΣ. Οι κωδικοί ΙΚΑ μόνο για την καρτέλα εργοδότη.")
            }

            if !isNew {
                Section {
                    Button("Διαγραφή πελάτη", role: .destructive) {
                        confirmDelete = true
                    }
                } footer: {
                    Text("Σβήνει την καρτέλα, τους κωδικούς και όλα τα έντυπα που έχουν κατέβει για αυτόν.")
                }
            }

            if !problem.isEmpty {
                Section {
                    Text(problem)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(isNew ? "Νέος πελάτης" : "Διόρθωση")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Άκυρο") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Αποθήκευση") { save() }
                    .disabled(!problem.isEmpty)
            }
        }
        .onAppear(perform: loadSecrets)
        .confirmationDialog(
            "Διαγραφή του πελάτη και όλων των εντύπων του;",
            isPresented: $confirmDelete,
            titleVisibility: .visible
        ) {
            Button("Διαγραφή", role: .destructive) {
                store.delete(draft)
                dismiss()
            }
            Button("Άκυρο", role: .cancel) {}
        }
    }

    private func loadSecrets() {
        guard !loaded else { return }
        loaded = true
        guard !isNew else { return }
        taxisUser = store.secret(.taxisUser, for: draft)
        taxisPass = store.secret(.taxisPass, for: draft)
        amka = store.secret(.amka, for: draft)
        ikaUser = store.secret(.ikaUser, for: draft)
        ikaPass = store.secret(.ikaPass, for: draft)
    }

    private func save() {
        var client = draft
        client.afm = afm
        client.name = draft.name.trimmingCharacters(in: .whitespaces)
        client.firstName = draft.firstName.trimmingCharacters(in: .whitespaces)
        client.email = draft.email.trimmingCharacters(in: .whitespaces)
        client.mobile = draft.mobile.trimmingCharacters(in: .whitespaces)
        store.save(client, secrets: [
            .taxisUser: taxisUser,
            .taxisPass: taxisPass,
            .amka: amka,
            .ikaUser: ikaUser,
            .ikaPass: ikaPass,
        ])
        dismiss()
    }
}

/// Πεδίο για κωδικό: ορατό ή κρυφό, χωρίς αυτόματη διόρθωση και χωρίς κεφαλαία.
private struct SecretInput: View {
    let title: String
    @Binding var text: String
    let reveal: Bool
    var numeric = false

    var body: some View {
        Group {
            if reveal {
                TextField(title, text: $text)
            } else {
                SecureField(title, text: $text)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .keyboardType(numeric ? .numberPad : .asciiCapable)
    }
}
