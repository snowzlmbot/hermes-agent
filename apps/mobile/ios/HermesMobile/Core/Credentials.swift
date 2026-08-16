import Foundation

public struct NativeTokenSet: Codable, Equatable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let expiresAt: Double
    public let provider: String
    public let userID: String

    public init(accessToken: String, refreshToken: String, expiresAt: Double, provider: String, userID: String = "") {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
        self.provider = provider
        self.userID = userID
    }
}

public enum StoredGatewayAuth: Codable, Equatable, Sendable {
    case token(String)
    case oauth(NativeTokenSet)

    enum CodingKeys: String, CodingKey { case kind, token, tokens }
    enum Kind: String, Codable { case token, oauth }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .token(let token):
            try container.encode(Kind.token, forKey: .kind)
            try container.encode(token, forKey: .token)
        case .oauth(let tokens):
            try container.encode(Kind.oauth, forKey: .kind)
            try container.encode(tokens, forKey: .tokens)
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(Kind.self, forKey: .kind) {
        case .token: self = .token(try container.decode(String.self, forKey: .token))
        case .oauth: self = .oauth(try container.decode(NativeTokenSet.self, forKey: .tokens))
        }
    }
}

public struct GatewayCredentials: Codable, Equatable, Sendable {
    public let endpoint: String?
    public let profileID: String?
    public let auth: StoredGatewayAuth

    public init(endpoint: String? = nil, token: String) throws {
        if let endpoint, endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw CredentialError.invalidEndpoint
        }
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CredentialError.emptyToken
        }
        self.endpoint = endpoint
        self.profileID = nil
        self.auth = .token(token)
    }

    public init(endpoint: String? = nil, auth: StoredGatewayAuth) throws {
        if let endpoint, endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw CredentialError.invalidEndpoint
        }
        guard !auth.isUsable else {
            self.endpoint = endpoint
            self.profileID = nil
            self.auth = auth
            return
        }
        throw CredentialError.emptyToken
    }

    public static func oauth(
        _ tokens: NativeTokenSet,
        endpoint: String? = nil,
        profileID: String? = nil
    ) -> Self {
        Self(endpoint: endpoint, profileID: profileID, auth: .oauth(tokens), unchecked: ())
    }

    private init(endpoint: String?, profileID: String?, auth: StoredGatewayAuth, unchecked: Void) {
        self.endpoint = endpoint
        self.profileID = profileID
        self.auth = auth
    }

    func bindingOAuth(to profileID: String) -> Self {
        guard case .oauth = auth else { return self }
        return Self(endpoint: endpoint, profileID: profileID, auth: auth, unchecked: ())
    }

    public var token: String? {
        guard case .token(let value) = auth else { return nil }
        return value
    }

    public var nativeTokens: NativeTokenSet? {
        guard case .oauth(let value) = auth else { return nil }
        return value
    }
}

private extension StoredGatewayAuth {
    var isUsable: Bool {
        switch self {
        case .token(let value): return !value.isEmpty
        case .oauth(let tokens): return !tokens.accessToken.isEmpty
        }
    }
}

public enum CredentialError: Error, Equatable, Sendable {
    case emptyToken
    case invalidEndpoint
    case authModeMismatch
    case endpointMismatch
    case profileMismatch
    case keychainFailure(OSStatusCode)
}

public struct OSStatusCode: Equatable, Sendable, Codable {
    public let rawValue: Int32
    public init(_ rawValue: Int32) { self.rawValue = rawValue }
}

public protocol CredentialStore: Sendable {
    func save(_ credentials: GatewayCredentials) async throws
    func load() async throws -> GatewayCredentials?
    func delete() async throws
}

public actor InMemoryCredentialStore: CredentialStore {
    private var value: GatewayCredentials?

    public init() {}

    public func save(_ credentials: GatewayCredentials) async throws { value = credentials }
    public func load() async throws -> GatewayCredentials? { value }
    public func delete() async throws { value = nil }
}
