import Foundation

public struct GatewayProfile: Identifiable, Codable, Equatable, Hashable, Sendable {
    public let id: String
    public var name: String
    public var endpoint: String
    public var authMode: GatewayAuthMode
    public var allowInsecure: Bool

    public init(
        id: String = "default",
        name: String = "Hermes",
        endpoint: String,
        authMode: GatewayAuthMode,
        allowInsecure: Bool = false
    ) {
        self.id = id
        self.name = name
        self.endpoint = endpoint
        self.authMode = authMode
        self.allowInsecure = allowInsecure
    }
}

public struct StoredGatewayConnection: Equatable, Sendable {
    public let profile: GatewayProfile
    public let credentials: GatewayCredentials

    public init(profile: GatewayProfile, credentials: GatewayCredentials) {
        self.profile = profile
        self.credentials = credentials
    }
}

public protocol GatewayProfileStore: Sendable {
    func load() async throws -> [GatewayProfile]
    func save(_ profile: GatewayProfile) async throws
    func deleteAll() async throws
}

public actor InMemoryGatewayProfileStore: GatewayProfileStore {
    private var profiles: [GatewayProfile]

    public init(profiles: [GatewayProfile] = []) {
        self.profiles = profiles
    }

    public func load() async throws -> [GatewayProfile] { profiles }

    public func save(_ profile: GatewayProfile) async throws {
        profiles.removeAll { $0.id == profile.id }
        profiles.insert(profile, at: 0)
    }

    public func deleteAll() async throws {
        profiles.removeAll()
    }
}

public actor UserDefaultsGatewayProfileStore: GatewayProfileStore {
    private let defaults: UserDefaults
    private let storageKey: String

    public init(suiteName: String? = nil, storageKey: String = "hermes.mobile.gateway.profiles") {
        if let suiteName {
            self.defaults = UserDefaults(suiteName: suiteName) ?? .standard
        } else {
            self.defaults = .standard
        }
        self.storageKey = storageKey
    }

    public func load() async throws -> [GatewayProfile] {
        guard let data = defaults.data(forKey: storageKey) else { return [] }
        return try JSONDecoder().decode([GatewayProfile].self, from: data)
    }

    public func save(_ profile: GatewayProfile) async throws {
        var profiles = try await load()
        profiles.removeAll { $0.id == profile.id }
        profiles.insert(profile, at: 0)
        defaults.set(try JSONEncoder().encode(profiles), forKey: storageKey)
    }

    public func deleteAll() async throws {
        defaults.removeObject(forKey: storageKey)
    }
}

public actor GatewayProfileRepository {
    private let profileStore: any GatewayProfileStore
    private let credentialStore: any CredentialStore

    public init(profileStore: any GatewayProfileStore, credentialStore: any CredentialStore) {
        self.profileStore = profileStore
        self.credentialStore = credentialStore
    }

    public func load() async throws -> StoredGatewayConnection? {
        let profiles = try await profileStore.load()
        guard let profile = profiles.first,
              let credentials = try await credentialStore.load() else { return nil }
        return StoredGatewayConnection(profile: profile, credentials: credentials)
    }

    public func save(profile: GatewayProfile, credentials: GatewayCredentials) async throws {
        _ = try GatewayEndpoint(rawValue: profile.endpoint, allowInsecureRemote: profile.allowInsecure)
        try await credentialStore.save(credentials)
        try await profileStore.save(profile)
    }

    public func clear() async throws {
        try await credentialStore.delete()
        try await profileStore.deleteAll()
    }
}