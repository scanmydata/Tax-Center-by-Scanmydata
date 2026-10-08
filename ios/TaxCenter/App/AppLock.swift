import Foundation
import LocalAuthentication
import SwiftUI

/// Το κλείδωμα της εφαρμογής: Face ID, Touch ID ή ο κωδικός της συσκευής.
///
/// Μια εφαρμογή με ΑΦΜ, ΑΜΚΑ και κωδικούς τρίτων δεν ανοίγει σε όποιον πιάσει
/// ένα ξεκλείδωτο τηλέφωνο. Ζητά ταυτοποίηση στην εκκίνηση, και ξανά όταν
/// επιστρέψει από το παρασκήνιο μετά από λίγο.
///
/// Αν η συσκευή **δεν έχει καθόλου κωδικό**, δεν υπάρχει τι να ζητηθεί: η
/// εφαρμογή ανοίγει, και οι Πληροφορίες το γράφουν ως προειδοποίηση.
@MainActor
final class AppLock: ObservableObject {

    @Published private(set) var locked: Bool
    @Published private(set) var message = ""

    /// Πόσο μπορεί να λείψει η εφαρμογή στο παρασκήνιο χωρίς να ξανακλειδώσει.
    static let grace: TimeInterval = 60

    private var leftAt: Date?
    private var asking = false

    /// Τα τεστ φιλοξενούνται μέσα στην εφαρμογή· εκεί δεν υπάρχει άνθρωπος να ξεκλειδώσει.
    static var underTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    static var deviceHasPasscode: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    init() {
        locked = !Self.underTest && Self.deviceHasPasscode
    }

    func unlock() {
        guard locked, !asking else { return }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            locked = false
            return
        }
        asking = true
        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: "Ξεκλείδωμα των στοιχείων των πελατών"
        ) { success, _ in
            Task { @MainActor [weak self] in
                self?.asking = false
                if success {
                    self?.locked = false
                    self?.message = ""
                } else {
                    self?.message = "Δεν έγινε ταυτοποίηση."
                }
            }
        }
    }

    /// - Parameter busy: τρέχει λήψη. Τότε η εφαρμογή δεν ξανακλειδώνει: η
    ///   λήψη συνεχίζει μόλις γυρίσει μπροστά, και ο χρήστης πρέπει να τη βλέπει.
    func scenePhaseChanged(_ phase: ScenePhase, busy: Bool) {
        switch phase {
        case .background:
            if leftAt == nil { leftAt = Date() }
        case .active:
            if let left = leftAt, Date().timeIntervalSince(left) > Self.grace, !busy, !Self.underTest, Self.deviceHasPasscode {
                locked = true
            }
            leftAt = nil
        default:
            break
        }
    }
}
