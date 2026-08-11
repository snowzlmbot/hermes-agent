import Foundation

public enum GatewayRESTRequestError: Error, Equatable, Sendable {
    case invalidSessionID
    case emptyBody
}

public enum GatewayRESTRequestBuilder {
    public static func nativeTokenRequest(
        endpoint: GatewayEndpoint,
        code: String,
        verifier: String
    ) throws -> URLRequest {
        guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !verifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw GatewayRESTRequestError.emptyBody
        }
        var request = URLRequest(url: endpoint.apiURL("auth/native/token"))
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "code": code,
            "code_verifier": verifier
        ])
        return request
    }

    public static func patchSessionRequest(
        endpoint: GatewayEndpoint,
        auth: StoredGatewayAuth,
        storedID: String,
        title: String?,
        archived: Bool?,
        pinned: Bool? = nil
    ) throws -> URLRequest {
        var body: [String: Any] = [:]
        if let title { body["title"] = title }
        if let archived { body["archived"] = archived }
        if let pinned { body["pinned"] = pinned }
        guard !body.isEmpty else { throw GatewayRESTRequestError.emptyBody }
        guard !storedID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw GatewayRESTRequestError.invalidSessionID
        }

        let sessionsURL = endpoint.apiURL("api/sessions")
        let url = sessionsURL.appendingPathComponent(storedID, isDirectory: false)
        return try request(endpoint: endpoint, url: url, method: "PATCH", auth: auth, body: body)
    }

    public static func deleteSessionRequest(
        endpoint: GatewayEndpoint,
        auth: StoredGatewayAuth,
        storedID: String
    ) throws -> URLRequest {
        guard !storedID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw GatewayRESTRequestError.invalidSessionID
        }

        let sessionsURL = endpoint.apiURL("api/sessions")
        let url = sessionsURL.appendingPathComponent(storedID, isDirectory: false)
        return try request(endpoint: endpoint, url: url, method: "DELETE", auth: auth, body: [:])
    }

    public static func request(
        endpoint: GatewayEndpoint,
        path: String,
        method: String = "POST",
        auth: StoredGatewayAuth,
        body: [String: Any] = [:]
    ) throws -> URLRequest {
        try request(endpoint: endpoint, url: endpoint.apiURL(path), method: method, auth: auth, body: body)
    }

    private static func request(
        endpoint: GatewayEndpoint,
        url: URL,
        method: String,
        auth: StoredGatewayAuth,
        body: [String: Any]
    ) throws -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 120
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        apply(auth: auth, to: &request)
        if !body.isEmpty {
            request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        }
        return request
    }

    public static func apply(auth: StoredGatewayAuth, to request: inout URLRequest) {
        switch auth {
        case .token(let token):
            request.setValue(token, forHTTPHeaderField: "X-Hermes-Session-Token")
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .oauth(let tokens):
            request.setValue("Bearer \(tokens.accessToken)", forHTTPHeaderField: "Authorization")
        }
    }
}