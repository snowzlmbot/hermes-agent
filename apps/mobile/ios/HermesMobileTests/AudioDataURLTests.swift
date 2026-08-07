import XCTest
@testable import HermesMobile

final class AudioDataURLTests: XCTestCase {
    func testDecodesAudioDataURLWithoutLoggingOrPersistingPayload() throws {
        let dataURL = "data:audio/wav;base64,UkFXX0FVRElP"
        let decoded = try AudioDataURL(dataURL)

        XCTAssertEqual(decoded.mimeType, "audio/wav")
        XCTAssertEqual(decoded.data, Data("RAW_AUDIO".utf8))
        XCTAssertEqual(decoded.redactedDescription, "data:audio/wav;base64,<redacted>")
    }

    func testRejectsNonBase64OrNonAudioDataURLs() {
        XCTAssertThrowsError(try AudioDataURL("https://example.com/audio.wav"))
        XCTAssertThrowsError(try AudioDataURL("data:text/plain;base64,aGVsbG8="))
        XCTAssertThrowsError(try AudioDataURL("data:audio/wav,raw"))
        XCTAssertThrowsError(try AudioDataURL("data:audio/wav;base64,"))
    }

    func testBuildsCanonicalDataURLForAudioBytes() throws {
        let value = try AudioDataURL(mimeType: "audio/mp4", data: Data("audio".utf8))

        XCTAssertEqual(value.string, "data:audio/mp4;base64,YXVkaW8=")
        XCTAssertEqual(value.redactedDescription, "data:audio/mp4;base64,<redacted>")
    }
}
