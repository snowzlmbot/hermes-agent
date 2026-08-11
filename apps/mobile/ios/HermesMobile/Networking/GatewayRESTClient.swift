import Foundation

public enum GatewayRESTError: Error, Equatable, Sendable {
    case invalidResponse
    case http(Int, String)
    case missingTicket
    case expiredSession
}

public struct GatewayStatus: Sendable, Equatable {
    public let authRequired: Bool
    public let authFlows: [String]
    public let authProviders: [String]
    public let version: String
    public let overall: String
    public let gatewayRunning: Bool

    public init(json: [String: Any]) {
        self.authRequired = json["auth_required"] as? Bool ?? false
        self.authFlows = json["auth_flows"] as? [String] ?? []
        self.authProviders = json["auth_providers"] as? [String] ?? []
        self.version = json["version"] as? String ?? ""
        self.overall = json["overall"] as? String ?? (json["status"] as? String ?? "")
        self.gatewayRunning = json["gateway_running"] as? Bool ?? false
    }

    public var authMode: GatewayAuthMode {
        authRequired ? .oauth : .token
    }

    public var nativeOAuthCapability: NativeOAuthCapability {
        NativeOAuthCapability(status: [
            "auth_required": authRequired,
            "auth_flows": authFlows
        ])
    }
}

public actor GatewayRESTClient {
    private let endpoint: GatewayEndpoint
    private let session: URLSession
    private var credentials: GatewayCredentials
    private var refreshTask: Task<GatewayCredentials, Error>?
    private var isInvalidated = false
    private let persistCredentials: @Sendable (GatewayCredentials) async throws -> Void

    public init(
        endpoint: GatewayEndpoint,
        credentials: GatewayCredentials,
        session: URLSession = .shared,
        persistCredentials: @escaping @Sendable (GatewayCredentials) async throws -> Void = { _ in }
    ) {
        self.endpoint = endpoint
        self.credentials = credentials
        self.session = session
        self.persistCredentials = persistCredentials
    }

    public static func status(endpoint: GatewayEndpoint, session: URLSession = .shared) async throws -> GatewayStatus {
        var request = URLRequest(url: endpoint.apiURL("api/status"))
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GatewayRESTError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw GatewayRESTError.http(http.statusCode, Self.detail(from: data))
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayRESTError.invalidResponse
        }
        return GatewayStatus(json: object)
    }

    public static func nativeOAuthProviders(
        endpoint: GatewayEndpoint,
        session: URLSession = .shared
    ) async throws -> [NativeOAuthProvider] {
        var request = URLRequest(url: endpoint.apiURL("api/auth/providers"))
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200 ..< 300).contains(http.statusCode),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayRESTError.invalidResponse
        }
        return NativeOAuthProvider.parseList(object)
    }

    public static func exchangeNativeCode(
        endpoint: GatewayEndpoint,
        code: String,
        verifier: String,
        session: URLSession = .shared
    ) async throws -> NativeTokenSet {
        let request = try GatewayRESTRequestBuilder.nativeTokenRequest(
            endpoint: endpoint,
            code: code,
            verifier: verifier
        )
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GatewayRESTError.invalidResponse }
        guard (200 ..< 300).contains(http.statusCode) else {
            throw GatewayRESTError.http(http.statusCode, Self.detail(from: data))
        }
        return try nativeTokenSet(from: data)
    }

    public func freshWebSocketTicket() async throws -> String {
        let data = try await sendJSON(path: "api/auth/ws-ticket", body: [:])
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let ticket = object["ticket"] as? String,
              !ticket.isEmpty else {
            throw GatewayRESTError.missingTicket
        }
        return ticket
    }

    public func invalidate() async {
        isInvalidated = true
        let task = refreshTask
        task?.cancel()
        if let task {
            _ = try? await task.value
        }
        refreshTask = nil
    }

    public func transcription(dataURL: String, mimeType: String) async throws -> String {
        let data = try await sendJSON(
            path: "api/audio/transcribe",
            body: ["data_url": dataURL, "mime_type": mimeType]
        )
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GatewayRESTError.invalidResponse
        }
        return object["transcript"] as? String ?? ""
    }

    public func speech(text: String) async throws -> AudioDataURL {
        let data = try await sendJSON(path: "api/audio/speak", body: ["text": text])
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let value = object["data_url"] as? String else {
            throw GatewayRESTError.invalidResponse
        }
        return try AudioDataURL(value)
    }

    public func patchSession(_ mutation: SessionMutation) async throws {
        _ = try await sendAuthorizedRequest { auth in
            try GatewayRESTRequestBuilder.patchSessionRequest(
                endpoint: endpoint,
                auth: auth,
                storedID: mutation.storedID,
                title: mutation.title,
                archived: mutation.archived,
                pinned: mutation.pinned
            )
        }
    }

    public func deleteSession(_ storedID: String) async throws {
        _ = try await sendAuthorizedRequest { auth in
            try GatewayRESTRequestBuilder.deleteSessionRequest(
                endpoint: endpoint,
                auth: auth,
                storedID: storedID
            )
        }
    }

    private func sendJSON(path: String, body: [String: Any], method: String = "POST") async throws -> Data {
        try await sendAuthorizedRequest { auth in
            try GatewayRESTRequestBuilder.request(
                endpoint: endpoint,
                path: path,
                method: method,
                auth: auth,
                body: body
            )
        }
    }

    private func sendAuthorizedRequest(
        makeRequest: (StoredGatewayAuth) throws -> URLRequest
    ) async throws -> Data {
        guard !isInvalidated else { throw GatewayRESTError.expiredSession }
        try await refreshOAuthIfNeeded()
        guard !isInvalidated else { throw GatewayRESTError.expiredSession }
        let attemptedAuth = credentials.auth
        let request = try makeRequest(attemptedAuth)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GatewayRESTError.invalidResponse }
        guard http.statusCode == 401 else {
            return try Self.validatedData(data, response: http)
        }

        try await refreshOAuthAfterUnauthorized(authUsed: attemptedAuth)
        guard !isInvalidated else { throw GatewayRESTError.expiredSession }
        let retry = try makeRequest(credentials.auth)
        let (retryData, retryResponse) = try await session.data(for: retry)
        guard let retryHTTP = retryResponse as? HTTPURLResponse else {
            throw GatewayRESTError.invalidResponse
        }
        return try Self.validatedData(retryData, response: retryHTTP)
    }

    private static func validatedData(_ data: Data, response: HTTPURLResponse) throws -> Data {
        guard (200 ..< 300).contains(response.statusCode) else {
            if response.statusCode == 401 { throw GatewayRESTError.expiredSession }
            throw GatewayRESTError.http(response.statusCode, detail(from: data))
        }
        return data
    }

    private func refreshOAuthIfNeeded(now: Double = Date().timeIntervalSince1970) async throws {
        if let current = refreshTask {
            credentials = try await current.value
            return
        }
        guard case .oauth(let tokens) = credentials.auth else { return }
        guard tokens.expiresAt <= 0 || now >= tokens.expiresAt - 60 else { return }
        try await refreshOAuth(tokens: tokens)
    }

    private func refreshOAuthAfterUnauthorized(authUsed: StoredGatewayAuth) async throws {
        guard case .oauth = authUsed,
              case .oauth(let currentTokens) = credentials.auth else {
            throw GatewayRESTError.expiredSession
        }
        guard credentials.auth == authUsed else { return }
        try await refreshOAuth(tokens: currentTokens)
    }

    private func refreshOAuth(tokens: NativeTokenSet) async throws {
        guard !isInvalidated else { throw GatewayRESTError.expiredSession }
        guard !tokens.refreshToken.isEmpty else { throw GatewayRESTError.expiredSession }
        if let current = refreshTask {
            credentials = try await current.value
            return
        }

        let endpoint = self.endpoint
        let session = self.session
        let storedEndpoint = credentials.endpoint
        let persistCredentials = self.persistCredentials
        let task = Task<GatewayCredentials, Error> {
            try await Self.rotateOAuth(
                tokens: tokens,
                endpoint: endpoint,
                session: session,
                storedEndpoint: storedEndpoint,
                persist: persistCredentials
            )
        }
        refreshTask = task
        defer { refreshTask = nil }
        credentials = try await task.value
    }

    private static func rotateOAuth(
        tokens: NativeTokenSet,
        endpoint: GatewayEndpoint,
        session: URLSession,
        storedEndpoint: String?,
        persist: @escaping @Sendable (GatewayCredentials) async throws -> Void
    ) async throws -> GatewayCredentials {
        var request = URLRequest(url: endpoint.apiURL("auth/native/refresh"))
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "refresh_token": tokens.refreshToken,
            "provider": tokens.provider
        ])

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw GatewayRESTError.invalidResponse }
        guard (200 ..< 300).contains(http.statusCode) else {
            if http.statusCode == 401 { throw GatewayRESTError.expiredSession }
            throw GatewayRESTError.http(http.statusCode, detail(from: data))
        }

        let responseTokens = try nativeTokenSet(from: data)
        let refreshed = NativeTokenSet(
            accessToken: responseTokens.accessToken,
            refreshToken: responseTokens.refreshToken,
            expiresAt: responseTokens.expiresAt,
            provider: responseTokens.provider.isEmpty ? tokens.provider : responseTokens.provider,
            userID: responseTokens.userID.isEmpty ? tokens.userID : responseTokens.userID
        )
        let updatedCredentials = GatewayCredentials.oauth(refreshed, endpoint: storedEndpoint)
        try await persist(updatedCredentials)
        return updatedCredentials
    }

    private static func detail(from data: Data) -> String {
        guard let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "Request failed" }
        return body["detail"] as? String ?? body["message"] as? String ?? "Request failed"
    }

    private static func nativeTokenSet(from data: Data) throws -> NativeTokenSet {
        guard let body = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let accessToken = body["access_token"] as? String,
              !accessToken.isEmpty,
              let refreshToken = body["refresh_token"] as? String,
              !refreshToken.isEmpty else {
            throw GatewayRESTError.invalidResponse
        }
        return NativeTokenSet(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAt: (body["expires_at"] as? NSNumber)?.doubleValue ?? 0,
            provider: body["provider"] as? String ?? "",
            userID: body["user_id"] as? String ?? ""
        )
    }
}
