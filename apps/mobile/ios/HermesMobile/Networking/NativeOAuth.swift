import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

public enum NativeOAuthError: Error, Equatable, Sendable {
    case authenticationFailed
    case cancelled
    case invalidCallback
    case invalidRequest
    case insecureTransport
    case missingAuthorizationCode
    case providerRejected
    case stateMismatch
}

enum NativeOAuthTransportPolicy {
    static func validate(endpoint: GatewayEndpoint) throws {
        guard endpoint.baseURL.scheme?.lowercased() == "https" else {
            throw NativeOAuthError.insecureTransport
        }
    }

    static func validate(credentials: GatewayCredentials, endpoint: GatewayEndpoint) throws {
        guard case .oauth = credentials.auth else { return }
        try validate(endpoint: endpoint)
        guard let storedEndpoint = credentials.endpoint,
              let stored = try? GatewayEndpoint(rawValue: storedEndpoint),
              canonicalURL(stored.baseURL) == canonicalURL(endpoint.baseURL) else {
            throw CredentialError.endpointMismatch
        }
    }

    static func canonicalURL(_ url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased() else { return nil }
        components.scheme = scheme
        components.host = host
        if (scheme == "https" && components.port == 443) ||
            (scheme == "http" && components.port == 80) {
            components.port = nil
        }
        components.path = components.path == "/" ? "" : components.path
        return components.url
    }
}

final class CredentialRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = CredentialRedirectPolicy()

    static func redirectedRequest(_ request: URLRequest, response: HTTPURLResponse) -> URLRequest? {
        guard let source = response.url,
              let destination = request.url,
              let sourceScheme = source.scheme?.lowercased(),
              let destinationScheme = destination.scheme?.lowercased(),
              ["http", "https"].contains(sourceScheme),
              sourceScheme == destinationScheme,
              source.host?.lowercased() == destination.host?.lowercased(),
              effectivePort(source) == effectivePort(destination),
              source.path == destination.path,
              destination.user == nil,
              destination.password == nil else { return nil }
        return request
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(Self.redirectedRequest(request, response: response))
    }

    private static func effectivePort(_ url: URL) -> Int? {
        if let port = url.port { return port }
        switch url.scheme?.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return nil
        }
    }
}

public struct NativeOAuthProvider: Equatable, Sendable {
    public let name: String
    public let displayName: String

    public static func parseList(_ object: [String: Any]) -> [Self] {
        guard let rows = object["providers"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard row["supports_password"] as? Bool != true,
                  let name = row["name"] as? String,
                  !name.isEmpty else { return nil }
            return Self(name: name, displayName: row["display_name"] as? String ?? name)
        }
    }
}

public struct NativeAuthorizationRequest: Equatable, Sendable {
    public static let redirectURI = URL(string: "com.snowzlmbot.hermes.mobile:/oauth/callback")!

    public let state: String
    public let verifier: String
    public let authorizationURL: URL

    public init(endpoint: GatewayEndpoint, provider: String? = nil) throws {
        let verifier = try Self.randomURLSafeValue()
        try self.init(
            endpoint: endpoint,
            provider: provider,
            verifier: verifier,
            challenge: Self.codeChallenge(for: verifier),
            state: try Self.randomURLSafeValue()
        )
    }

    init(
        endpoint: GatewayEndpoint,
        provider: String? = nil,
        verifier: String,
        challenge: String,
        state: String
    ) throws {
        try NativeOAuthTransportPolicy.validate(endpoint: endpoint)
        guard !state.isEmpty, (43 ... 128).contains(verifier.count), !challenge.isEmpty else {
            throw NativeOAuthError.invalidRequest
        }

        var components = URLComponents(
            url: endpoint.apiURL("auth/native/authorize"),
            resolvingAgainstBaseURL: false
        )
        var queryItems = [
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI.absoluteString)
        ]
        if let provider = provider?.trimmingCharacters(in: .whitespacesAndNewlines), !provider.isEmpty {
            queryItems.append(URLQueryItem(name: "provider", value: provider))
        }
        components?.queryItems = queryItems
        guard let authorizationURL = components?.url else { throw NativeOAuthError.invalidRequest }

        self.state = state
        self.verifier = verifier
        self.authorizationURL = authorizationURL
    }

    public func authorizationCode(from callbackURL: URL) throws -> String {
        guard callbackURL.scheme == Self.redirectURI.scheme,
              callbackURL.host == Self.redirectURI.host,
              callbackURL.path == Self.redirectURI.path,
              callbackURL.user == nil,
              callbackURL.password == nil,
              callbackURL.port == nil,
              callbackURL.fragment == nil,
              let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false) else {
            throw NativeOAuthError.invalidCallback
        }

        guard try Self.uniqueValue(named: "state", in: components.queryItems) == state else {
            throw NativeOAuthError.stateMismatch
        }
        if let error = components.queryItems?.first(where: { $0.name == "error" })?.value,
           !error.isEmpty {
            throw NativeOAuthError.providerRejected
        }
        let code = try Self.uniqueValue(named: "code", in: components.queryItems)
        guard !code.isEmpty else { throw NativeOAuthError.missingAuthorizationCode }
        return code
    }

    private static func codeChallenge(for verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncodedString()
    }

    private static func randomURLSafeValue(byteCount: Int = 32) throws -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, byteCount, buffer.baseAddress!)
        }
        guard status == errSecSuccess else {
            throw NativeOAuthError.invalidRequest
        }
        return Data(bytes).base64URLEncodedString()
    }

    private static func uniqueValue(named name: String, in items: [URLQueryItem]?) throws -> String {
        let values = items?.filter { $0.name == name }.compactMap(\.value) ?? []
        guard values.count == 1 else {
            if name == "code" { throw NativeOAuthError.missingAuthorizationCode }
            throw NativeOAuthError.invalidCallback
        }
        return values[0]
    }
}

@MainActor
public final class NativeAuthenticationSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    public func authenticate(_ request: NativeAuthorizationRequest) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let nextSession = ASWebAuthenticationSession(
                url: request.authorizationURL,
                callbackURLScheme: NativeAuthorizationRequest.redirectURI.scheme
            ) { [weak self] callbackURL, error in
                Task { @MainActor [weak self] in self?.session = nil }
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else if let error = error as? ASWebAuthenticationSessionError,
                          error.code == .canceledLogin {
                    continuation.resume(throwing: NativeOAuthError.cancelled)
                } else {
                    continuation.resume(throwing: NativeOAuthError.authenticationFailed)
                }
            }
            nextSession.presentationContextProvider = self
            nextSession.prefersEphemeralWebBrowserSession = false
            session = nextSession
            guard nextSession.start() else {
                session = nil
                continuation.resume(throwing: NativeOAuthError.authenticationFailed)
                return
            }
        }
    }

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first ?? ASPresentationAnchor()
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
