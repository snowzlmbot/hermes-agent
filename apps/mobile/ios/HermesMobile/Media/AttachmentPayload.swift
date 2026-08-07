import Foundation

public enum AttachmentError: Error, Equatable, Sendable {
    case empty
    case invalidFilename
    case unsupportedType
    case tooLarge
}

public enum AttachmentPayload: Equatable, Sendable {
    case imageContent(filename: String, mimeType: String, data: Data)
    case pdfContent(filename: String, data: Data)
    case fileContent(filename: String, mimeType: String, data: Data)

    public static func image(filename: String, mimeType: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 25 * 1_024 * 1_024)
        guard mimeType.lowercased().hasPrefix("image/") else { throw AttachmentError.unsupportedType }
        return .imageContent(filename: filename, mimeType: mimeType, data: data)
    }

    public static func pdf(filename: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 50 * 1_024 * 1_024)
        return .pdfContent(filename: filename, data: data)
    }

    public static func file(filename: String, mimeType: String, data: Data) throws -> Self {
        try validate(filename: filename, data: data, maximum: 25 * 1_024 * 1_024)
        guard !mimeType.isEmpty else { throw AttachmentError.unsupportedType }
        return .fileContent(filename: filename, mimeType: mimeType, data: data)
    }

    public var method: String {
        switch self {
        case .imageContent: return GatewayMethod.imageAttachBytes
        case .pdfContent: return GatewayMethod.pdfAttach
        case .fileContent: return GatewayMethod.fileAttach
        }
    }

    public var filename: String {
        switch self {
        case .imageContent(let filename, _, _),
             .pdfContent(let filename, _),
             .fileContent(let filename, _, _):
            return filename
        }
    }

    public func rpcParameters(sessionID: String) throws -> [String: JSONValue] {
        switch self {
        case .imageContent(let filename, let mimeType, let data):
            return [
                "session_id": .string(sessionID),
                "filename": .string(filename),
                "mime_type": .string(mimeType),
                "content_base64": .string(data.base64EncodedString())
            ]
        case .pdfContent(let filename, let data):
            return [
                "session_id": .string(sessionID),
                "filename": .string(filename),
                "content_base64": .string(data.base64EncodedString())
            ]
        case .fileContent(let filename, let mimeType, let data):
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
