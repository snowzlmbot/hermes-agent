import XCTest
@testable import HermesMobile

final class AudioRequestBuilderTests: XCTestCase {
    func testRESTRequestsCarryBothSupportedAuthenticationHeaders() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com/hermes")
        let request = try AudioRequestBuilder.transcriptionRequest(
            endpoint: endpoint,
            auth: .token("secret-token"),
            dataURL: "data:audio/mp4;base64,YXVkaW8=",
            mimeType: "audio/mp4"
        )

        XCTAssertEqual(request.url?.absoluteString, "https://gateway.example.com/hermes/api/audio/transcribe")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"), "secret-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertEqual(request.httpMethod, "POST")
    }

    func testOAuthRESTRequestsUseBearerOnlyAndNeverPutTokenInURL() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com")
        let request = try AudioRequestBuilder.speechRequest(
            endpoint: endpoint,
            auth: .oauth(NativeTokenSet(accessToken: "access-token", refreshToken: "refresh", expiresAt: 0, provider: "nous")),
            text: "Read this"
        )

        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
        XCTAssertFalse(try XCTUnwrap(request.url?.absoluteString).contains("access-token"))
        XCTAssertEqual(request.url?.path, "/api/audio/speak")
    }
}
