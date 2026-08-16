import Foundation

public enum GatewayEndpointError: Error, Equatable, Sendable {
    case required
    case unsupportedScheme
    case credentialsInURL
    case queryOrFragmentNotAllowed
    case insecureRemoteEndpoint
    case malformed
}

public enum GatewayAuth: Sendable {
    case token(String)
    case ticket(String)
    case oauth(NativeTokenSet)
    case ticketProvider(@Sendable () async throws -> String)
    case oauthTicketProvider(@Sendable () async throws -> String)

    public var bearerToken: String? {
        switch self {
        case .token(let value): return value
        case .oauth(let tokens): return tokens.accessToken
        case .ticket, .ticketProvider, .oauthTicketProvider: return nil
        }
    }
}

public struct GatewayEndpoint: Equatable, Hashable, Sendable, Codable {
    public let baseURL: URL
    public let redactedDescription: String

    public init(rawValue: String, allowInsecureRemote: Bool = false) throws {
        let raw = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { throw GatewayEndpointError.required }
        guard let parsed = URL(string: raw), let scheme = parsed.scheme?.lowercased() else {
            throw GatewayEndpointError.malformed
        }
        guard scheme == "http" || scheme == "https" else {
            throw GatewayEndpointError.unsupportedScheme
        }
        guard parsed.user == nil && parsed.password == nil else {
            throw GatewayEndpointError.credentialsInURL
        }
        guard parsed.query == nil && parsed.fragment == nil else {
            throw GatewayEndpointError.queryOrFragmentNotAllowed
        }
        guard let host = parsed.host, !host.isEmpty else { throw GatewayEndpointError.malformed }

        let normalizedPath: String
        if parsed.path.hasSuffix("/api/ws") {
            normalizedPath = String(parsed.path.dropLast("/api/ws".count))
        } else if parsed.path.hasSuffix("/api") {
            normalizedPath = String(parsed.path.dropLast("/api".count))
        } else {
            normalizedPath = parsed.path
        }

        var components = URLComponents(url: parsed, resolvingAgainstBaseURL: false)
        components?.path = normalizedPath.isEmpty ? "" : "/" + normalizedPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let base = components?.url else { throw GatewayEndpointError.malformed }

        let isLoopback = Self.loopbackHosts.contains(host.lowercased())
        if scheme == "http" && !isLoopback && !allowInsecureRemote {
            throw GatewayEndpointError.insecureRemoteEndpoint
        }
        self.baseURL = base
        self.redactedDescription = base.absoluteString
    }

    public func apiURL(_ path: String) -> URL {
        let prefix = baseURL.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let suffix = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.path = ([prefix, suffix].filter { !$0.isEmpty }).joined(separator: "/").isEmpty
            ? ""
            : "/" + [prefix, suffix].filter { !$0.isEmpty }.joined(separator: "/")
        return components.url!
    }

    public func webSocketURL(auth: GatewayAuth) -> URL {
        let wsScheme = baseURL.scheme == "https" ? "wss" : "ws"
        var components = URLComponents(url: apiURL("api/ws"), resolvingAgainstBaseURL: false)!
        components.scheme = wsScheme
        switch auth {
        case .token(let token): components.queryItems = [URLQueryItem(name: "token", value: token)]
        case .ticket(let ticket): components.queryItems = [URLQueryItem(name: "ticket", value: ticket)]
        case .oauth:
            components.queryItems = []
        case .ticketProvider, .oauthTicketProvider:
            components.queryItems = []
        }
        return components.url!
    }

    public static let loopbackHosts: Set<String> = ["127.0.0.1", "localhost", "::1"]
}

public enum GatewayAuthMode: String, Codable, Equatable, Sendable {
    case token
    case oauth

    public static func fromStatus(_ status: [String: Any]) -> Self {
        status["auth_required"] as? Bool == true ? .oauth : .token
    }

    public static func nativePKCESupported(status: [String: Any]) -> Bool {
        (status["auth_flows"] as? [String])?.contains("native_pkce_mobile") == true
    }

    public var displayName: String {
        switch self {
        case .token: return String(localized: "connection.mode.token")
        case .oauth: return String(localized: "connection.mode.oauth")
        }
    }
}

public enum NativeOAuthState: Equatable, Sendable {
    case available
    case unavailable
}

public struct NativeOAuthCapability: Equatable, Sendable {
    public let state: NativeOAuthState
    public let supportsASWebAuthenticationSessionCallback: Bool
    public let explanation: String

    public init(status: [String: Any]) {
        if GatewayAuthMode.nativePKCESupported(status: status) {
            self.state = .available
            self.supportsASWebAuthenticationSessionCallback = true
            self.explanation = String(localized: "oauth.available.explanation")
        } else {
            self.state = .unavailable
            self.supportsASWebAuthenticationSessionCallback = false
            self.explanation = String(localized: "oauth.unavailable.explanation")
        }
    }
}
