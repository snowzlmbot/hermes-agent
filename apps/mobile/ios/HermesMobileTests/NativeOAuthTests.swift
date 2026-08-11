import Foundation
import XCTest
@testable import HermesMobile

final class NativeOAuthTests: XCTestCase {
    func testAuthorizationURLUsesRegisteredCallbackAndPKCE() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://agent.example/hermes/")
        let request = try NativeAuthorizationRequest(
            endpoint: endpoint,
            verifier: String(repeating: "v", count: 43),
            challenge: "challenge value",
            state: "state-1"
        )
        let components = try XCTUnwrap(URLComponents(url: request.authorizationURL, resolvingAgainstBaseURL: false))
        let query = Dictionary(uniqueKeysWithValues: try XCTUnwrap(components.queryItems).map { ($0.name, $0.value) })

        XCTAssertEqual(components.path, "/hermes/auth/native/authorize")
        XCTAssertEqual(query["code_challenge"]!, "challenge value")
        XCTAssertEqual(query["code_challenge_method"]!, "S256")
        XCTAssertEqual(query["redirect_uri"]!, NativeAuthorizationRequest.redirectURI.absoluteString)
        XCTAssertEqual(query["state"]!, "state-1")
        XCTAssertFalse(request.authorizationURL.absoluteString.contains(request.verifier))
    }

    func testCallbackAcceptsOnlyRegisteredSchemePathAndState() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://agent.example")
        let request = try NativeAuthorizationRequest(
            endpoint: endpoint,
            verifier: String(repeating: "v", count: 43),
            challenge: "challenge",
            state: "state-1"
        )

        XCTAssertEqual(
            try request.authorizationCode(
                from: try XCTUnwrap(URL(string: "com.snowzlmbot.hermes.mobile:/oauth/callback?code=code-1&state=state-1"))
            ),
            "code-1"
        )
        XCTAssertThrowsError(
            try request.authorizationCode(
                from: try XCTUnwrap(URL(string: "com.attacker.app:/oauth/callback?code=code-1&state=state-1"))
            )
        )
        XCTAssertThrowsError(
            try request.authorizationCode(
                from: try XCTUnwrap(URL(string: "com.snowzlmbot.hermes.mobile:/oauth/callback?code=code-1&state=wrong"))
            )
        )
    }

    func testNativeTokenExchangeRequestHasNoCredentialHeaders() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://agent.example/hermes")
        let request = try GatewayRESTRequestBuilder.nativeTokenRequest(
            endpoint: endpoint,
            code: "code-1",
            verifier: String(repeating: "v", count: 43)
        )
        let body = try XCTUnwrap(request.httpBody)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])

        XCTAssertEqual(request.url?.absoluteString, "https://agent.example/hermes/auth/native/token")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(object["code"], "code-1")
        XCTAssertEqual(object["code_verifier"], String(repeating: "v", count: 43))
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
    }
}
