import Foundation

public enum AudioDataURLError: Error, Equatable, Sendable {
    case malformed
    case unsupportedType
    case notBase64
    case empty
}

public struct AudioDataURL: Equatable, Sendable, CustomStringConvertible {
    public let mimeType: String
    public let data: Data

    public init(_ value: String) throws {
        guard value.hasPrefix("data:"), let comma = value.firstIndex(of: ",") else {
            throw AudioDataURLError.malformed
        }
        let header = String(value[value.index(value.startIndex, offsetBy: 5)..<comma])
        let segments = header.split(separator: ";").map(String.init)
        guard let mime = segments.first, mime.lowercased().hasPrefix("audio/") else {
            throw AudioDataURLError.unsupportedType
        }
        guard segments.dropFirst().contains("base64") else { throw AudioDataURLError.notBase64 }
        let encoded = String(value[value.index(after: comma)...])
        guard !encoded.isEmpty, let decoded = Data(base64Encoded: encoded), !decoded.isEmpty else {
            throw AudioDataURLError.empty
        }
        self.mimeType = mime.lowercased()
        self.data = decoded
    }

    public init(mimeType: String, data: Data) throws {
        guard mimeType.lowercased().hasPrefix("audio/") else { throw AudioDataURLError.unsupportedType }
        guard !data.isEmpty else { throw AudioDataURLError.empty }
        self.mimeType = mimeType.lowercased()
        self.data = data
    }

    public var string: String { "data:\(mimeType);base64,\(data.base64EncodedString())" }
    public var redactedDescription: String { "data:\(mimeType);base64,<redacted>" }
    public var description: String { redactedDescription }
}

public enum AudioRequestBuilder {
    public static func transcriptionRequest(
        endpoint: GatewayEndpoint,
        auth: StoredGatewayAuth,
        dataURL: String,
        mimeType: String
    ) throws -> URLRequest {
        try request(
            endpoint: endpoint,
            path: "api/audio/transcribe",
            auth: auth,
            body: ["data_url": dataURL, "mime_type": mimeType]
        )
    }

    public static func speechRequest(
        endpoint: GatewayEndpoint,
        auth: StoredGatewayAuth,
        text: String
    ) throws -> URLRequest {
        try request(endpoint: endpoint, path: "api/audio/speak", auth: auth, body: ["text": text])
    }

    private static func request(
        endpoint: GatewayEndpoint,
        path: String,
        auth: StoredGatewayAuth,
        body: [String: Any]
    ) throws -> URLRequest {
        var request = URLRequest(url: endpoint.apiURL(path))
        request.httpMethod = "POST"
        request.timeoutInterval = 120
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        switch auth {
        case .token(let token):
            request.setValue(token, forHTTPHeaderField: "X-Hermes-Session-Token")
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .oauth(let tokens):
            request.setValue("Bearer \(tokens.accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }
}
