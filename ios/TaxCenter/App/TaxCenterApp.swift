import SwiftUI

/// ScanMyData Tax Center για iOS.
///
/// Η εφαρμογή του Android είναι γραμμένη σε Kotlin και δεν μεταγλωττίζεται για
/// iPhone. Αυτό που **μοιράζονται** οι δύο είναι το πιο δύσκολο κομμάτι: ο
/// engine που μιλά με τις πύλες (ΑΑΔΕ, e-ΕΦΚΑ), γραμμένος σε JavaScript, μπαίνει
/// αυτούσιος και εδώ. Το περίβλημα — πελάτες, λήψη, έγγραφα — είναι SwiftUI.
@main
@MainActor
struct TaxCenterApp: App {

    @StateObject private var store = ClientStore()
    @StateObject private var fetch = FetchRunner()
    @StateObject private var lock = AppLock()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(fetch)
                .environmentObject(lock)
                .onChange(of: phase) { newPhase in
                    lock.scenePhaseChanged(newPhase, busy: fetch.running)
                }
        }
    }
}

struct RootView: View {

    @EnvironmentObject private var lock: AppLock
    @Environment(\.scenePhase) private var phase

    var body: some View {
        ZStack {
            NavigationStack {
                ClientsView()
            }
            .allowsHitTesting(!lock.locked)
            .accessibilityHidden(lock.locked)

            if lock.locked {
                LockView()
            } else if phase != .active {
                // Η μικρογραφία στα «πρόσφατα» δεν πρέπει να δείχνει λίστα
                // πελατών με ΑΦΜ.
                PrivacyCover()
            }
        }
    }
}

private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            Image(systemName: "lock.doc")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
        }
    }
}

private struct LockView: View {

    @EnvironmentObject private var lock: AppLock

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.tint)
                Text("ScanMyData Tax Center")
                    .font(.title2.weight(.semibold))
                Text("Τα στοιχεία των πελατών είναι κλειδωμένα.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Ξεκλείδωμα") { lock.unlock() }
                    .buttonStyle(.borderedProminent)
                if !lock.message.isEmpty {
                    Text(lock.message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding()
        }
        .onAppear { lock.unlock() }
    }
}
