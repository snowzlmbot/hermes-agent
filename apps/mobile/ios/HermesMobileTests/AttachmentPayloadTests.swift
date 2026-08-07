import XCTest
@testable import HermesMobile

final class AttachmentPayloadTests: XCTestCase {
    func testImagePayloadUsesBase64BytesAndFilenameOnly() throws {
        let payload = try AttachmentPayload.image(
            filename: "photo.png",
            mimeType: "image/png",
            data: Data([0x89, 0x50, 0x4E, 0x47])
        ).rpcParameters(sessionID: "runtime-1")

        XCTAssertEqual(payload["session_id"], .string("runtime-1"))
        XCTAssertEqual(payload["filename"], .string("photo.png"))
        XCTAssertEqual(payload["mime_type"], .string("image/png"))
        XCTAssertNotNil(payload["content_base64"])
        XCTAssertNil(payload["path"])
    }

    func testPDFPayloadUsesBase64AndNeverSendsLocalPath() throws {
        let payload = try AttachmentPayload.pdf(
            filename: "report.pdf",
            data: Data("%PDF-fixture".utf8)
        ).rpcParameters(sessionID: "runtime-1")

        XCTAssertNotNil(payload["content_base64"])
        XCTAssertEqual(payload["filename"], .string("report.pdf"))
        XCTAssertNil(payload["path"])
    }

    func testFilePayloadUsesDataURLAndDoesNotSendLocalPath() throws {
        let payload = try AttachmentPayload.file(
            filename: "notes.txt",
            mimeType: "text/plain",
            data: Data("hello".utf8)
        ).rpcParameters(sessionID: "runtime-1")

        XCTAssertNotNil(payload["data_url"])
        XCTAssertEqual(payload["name"], .string("notes.txt"))
        XCTAssertNil(payload["path"])
    }

    func testAttachmentRejectsEmptyDataAndUnsafeFilename() {
        XCTAssertThrowsError(try AttachmentPayload.image(filename: "x.png", mimeType: "image/png", data: Data()))
        XCTAssertThrowsError(try AttachmentPayload.file(filename: "../secret.txt", mimeType: "text/plain", data: Data([1])))
    }
}
