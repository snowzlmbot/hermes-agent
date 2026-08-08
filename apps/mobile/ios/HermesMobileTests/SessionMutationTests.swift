import XCTest
@testable import HermesMobile

final class SessionMutationTests: XCTestCase {
    func testArchivePatchUsesStoredSessionIDAndNeverPlacesCredentialsInURL() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com/hermes")
        let request = try GatewayRESTRequestBuilder.patchSessionRequest(
            endpoint: endpoint,
            auth: .token("secret-token"),
            storedID: "stored-1",
            title: nil,
            archived: true
        )

        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.url?.absoluteString, "https://gateway.example.com/hermes/api/sessions/stored-1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"), "secret-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        XCTAssertFalse(try XCTUnwrap(request.url?.absoluteString).contains("secret-token"))
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody)) as? [String: Bool],
            ["archived": true]
        )
    }

    func testRenamePatchKeepsTitleAndArchiveFieldsIndependent() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com")
        let request = try GatewayRESTRequestBuilder.patchSessionRequest(
            endpoint: endpoint,
            auth: .oauth(NativeTokenSet(accessToken: "access", refreshToken: "refresh", expiresAt: 0, provider: "nous")),
            storedID: "stored-2",
            title: "Roadmap",
            archived: nil,
            pinned: true
        )

        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: try XCTUnwrap(request.httpBody)) as? NSDictionary,
            ["title": "Roadmap", "pinned": true] as NSDictionary
        )
    }

    func testDeleteRequestUsesStoredSessionIDAndNeverPlacesCredentialsInURL() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com/hermes")
        let request = try GatewayRESTRequestBuilder.deleteSessionRequest(
            endpoint: endpoint,
            auth: .token("test-token"),
            storedID: "stored-3"
        )

        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.absoluteString, "https://gateway.example.com/hermes/api/sessions/stored-3")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"), "test-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
        XCTAssertNil(request.httpBody)
        XCTAssertFalse(try XCTUnwrap(request.url?.absoluteString).contains("test-token"))
    }
}
