import Foundation

public enum AttachmentError: Error, Equatable, Sendable {
    case empty
    case invalidFilename
    case unsupportedType
    case tooLarge
}

public enum AttachmentPayload: Equatable, Sendable {
    case image(filename: String, mimeType: String, data: Data)
    case pdf(filename: String, data: Data)
    case file(filename: String, mimeType: String, data: Data)

    public static func image(filename: String, mimeType: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 25 * 1_024 * 1_024)
        guard mimeType.lowercased().hasPrefix("image/") else { throw AttachmentError.unsupportedType }
        return .image(filename: filename, mimeType: mimeType, data: data)
    }

    public static func pdf(filename: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 50 * 1_024 * 1_024)
        return .pdf(filename: filename, data: data)
    }

    public static func file(filename: String, mimeType: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 25 * 1_024 * 1_024)
        guard !mimeType.isEmpty else { throw AttachmentError.unsupportedType }
        return .file(filename: filename, mimeType: mimeType, data: data)
    }

    public var method: String {
        switch self {
        case .image: return GatewayMethod.imageAttachBytes
        case .pdf: return GatewayMethod.pdfAttach
        case .file: return GatewayMethod.fileAttach
        }
    }

    public var filename: String {
        switch self {
        case .image(let filename, _, _), .pdf(let filename, _), .file(let filename, _, _): return filename
        }
    }

    public func rpcParameters(sessionID: String) throws -> [String: JSONValue] {
        switch self {
        case .image(let filename, let mimeType, let data):
            return [
                "session_id": .string(sessionID),
                "filename": .string(filename),
                "mime_type": .string(mimeType),
                "content_base64": .string(data.base64EncodedString())
            ]
        case .pdf(let filename, let data):
            return [
                "session_id": .string(sessionID),
                "filename": .string(filename),
                "content_base64": .string(data.base64EncodedString())
            ]
        case .file(let filename, let mimeType, let data):
            return [
                "session_id": .string(sessionID),
                "name": .string(filename),
                "data_url": .string("data:\(mimeType);base64,\(data.base64EncodedString())")
            ]
        }
    }

    private static func validate(filename: String, data: Data, maximum: Int) throws {
        guard !data.isEmpty else { throw AttachmentError.empty }
        let name = filename.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty,
              name == URL(fileURLWithPath: name).lastPathComponent,
              !name.contains("/"), !name.contains("\\"), name != ".", name != ".." else {
            throw AttachmentError.invalidFilename
        }
        guard data.count <= maximum else { throw AttachmentError.tooLarge }
    }
}
