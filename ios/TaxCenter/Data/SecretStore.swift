import Foundation
import Security

/// Πού ζουν οι κωδικοί των πελατών.
protocol SecretStore {
    func get(_ key: String) -> String
    /// Κενή τιμή = διαγραφή.
    func set(_ value: String, for key: String)
    func removeAll(prefix: String)
}

/// Το Keychain του iOS.
///
/// Οι κωδικοί TAXISnet **δεν γράφονται ποτέ σε αρχείο** της εφαρμογής: μένουν
/// στο Keychain, κρυπτογραφημένοι από το σύστημα, διαθέσιμοι μόνο όσο η συσκευή
/// είναι ξεκλείδωτη (`WhenUnlockedThisDeviceOnly`). Το «ThisDeviceOnly» σημαίνει
/// ότι δεν μπαίνουν σε αντίγραφα ασφαλείας και δεν συγχρονίζονται στο iCloud.
final class KeychainStore: SecretStore {

    private let service: String

    init(service: String = "gr.scanmydata.taxcenter.credentials") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    func get(_ key: String) -> String {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    func set(_ value: String, for key: String) {
        SecItemDelete(query(key) as CFDictionary)
        guard !value.isEmpty, let data = value.data(using: .utf8) else { return }
        var q = query(key)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    func removeAll(prefix: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &items) == errSecSuccess,
              let list = items as? [[String: Any]]
        else { return }
        for entry in list {
            guard let account = entry[kSecAttrAccount as String] as? String, account.hasPrefix(prefix) else { continue }
            SecItemDelete(query(account) as CFDictionary)
        }
    }
}

/// Για τα τεστ: τίποτα δεν αγγίζει το Keychain.
final class MemorySecretStore: SecretStore {
    private var values: [String: String] = [:]

    func get(_ key: String) -> String { values[key] ?? "" }

    func set(_ value: String, for key: String) {
        if value.isEmpty { values.removeValue(forKey: key) } else { values[key] = value }
    }

    func removeAll(prefix: String) {
        values = values.filter { !$0.key.hasPrefix(prefix) }
    }
}
