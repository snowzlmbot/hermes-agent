import Foundation
import Security

public actor KeychainCredentialStore: CredentialStore {
    private let service: String
    private let profileID: String

    public init(service: String = "com.snowzlmbot.hermes.mobile", profileID: String = "default") {
        self.service = service
        self.profileID = profileID
    }

    public static func itemIdentifier(profileID: String) -> String {
        "com.snowzlmbot.hermes.mobile.credentials.\(profileID)"
    }

    public func save(_ credentials: GatewayCredentials) async throws {
        let data = try JSONEncoder().encode(credentials)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.itemIdentifier(profileID: profileID)
        ]
        let attributes: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw CredentialError.keychainFailure(OSStatusCode(updateStatus))
        }
        var item = query
        item[kSecValueData as String] = data
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw CredentialError.keychainFailure(OSStatusCode(status)) }
    }

    public func load() async throws -> GatewayCredentials? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.itemIdentifier(profileID: profileID),
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw CredentialError.keychainFailure(OSStatusCode(status))
        }
        return try JSONDecoder().decode(GatewayCredentials.self, from: data)
    }

    public func delete() async throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.itemIdentifier(profileID: profileID)
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw CredentialError.keychainFailure(OSStatusCode(status))
        }
    }
}
