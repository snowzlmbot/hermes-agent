import Foundation
import UniformTypeIdentifiers

public protocol AttachmentImporting: Sendable {
    func imagePayload(data: Data, contentType: UTType?) async throws -> AttachmentPayload
    func filePayload(at url: URL) async throws -> AttachmentPayload
}

public actor AttachmentImportService {
    public init() {}

    public func imagePayload(data: Data, contentType: UTType?) async throws -> AttachmentPayload {
        let type = contentType ?? .jpeg
        let mimeType = type.preferredMIMEType ?? "image/jpeg"
        let fileExtension = type.preferredFilenameExtension ?? "jpg"
        return try AttachmentPayload.image(
            filename: "photo.\(fileExtension)",
            mimeType: mimeType,
            data: data
        )
    }

    public func filePayload(at url: URL) async throws -> AttachmentPayload {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        let values = try url.resourceValues(forKeys: [.contentTypeKey, .fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile != false else { throw AttachmentError.unsupportedType }
        let contentType = values.contentType ?? UTType(filenameExtension: url.pathExtension) ?? .data
        let maximum = contentType.conforms(to: .pdf) ? 50 * 1_024 * 1_024 : 25 * 1_024 * 1_024
        if let fileSize = values.fileSize, fileSize > maximum { throw AttachmentError.tooLarge }

        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        let filename = url.lastPathComponent
        if contentType.conforms(to: .image) {
            return try AttachmentPayload.image(
                filename: filename,
                mimeType: contentType.preferredMIMEType ?? "image/jpeg",
                data: data
            )
        }
        if contentType.conforms(to: .pdf) {
            return try AttachmentPayload.pdf(filename: filename, data: data)
        }
        return try AttachmentPayload.file(
            filename: filename,
            mimeType: contentType.preferredMIMEType ?? "application/octet-stream",
            data: data
        )
    }
}

extension AttachmentImportService: AttachmentImporting {}
