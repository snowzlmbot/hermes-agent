import CryptoKit
import Foundation
import XCTest
@testable import HermesMobile

final class NativeOAuthTests: XCTestCase {
    func testRejectsCleartextOAuthIncludingEveryLoopbackForm() throws {
        let endpoints = [
            try GatewayEndpoint(rawValue: "http://agent.example", allowInsecureRemote: true),
            try GatewayEndpoint(rawValue: "http://127.0.0.1:8765"),
            try GatewayEndpoint(rawValue: "http://localhost:8765"),
            try GatewayEndpoint(rawValue: "http://[::1]:8765")
        ]

        for endpoint in endpoints {
            XCTAssertThrowsError(try NativeAuthorizationRequest(endpoint: endpoint)) { error in
                XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
            }
        }
    }

    func testNativeOAuthRESTPathsRejectCleartextLoopback() async throws {
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let session = makeFailingSession()
        let tokens = NativeTokenSet(
            accessToken: "access",
            refreshToken: "refresh",
            expiresAt: 4_102_444_800,
            provider: "nous"
        )

        XCTAssertThrowsError(
            try GatewayRESTRequestBuilder.nativeTokenRequest(
                endpoint: endpoint,
                code: "code",
                verifier: String(repeating: "v", count: 43)
            )
        ) { error in
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }

        let oauth = StoredGatewayAuth.oauth(tokens)
        XCTAssertThrowsError(
            try GatewayRESTRequestBuilder.patchSessionRequest(
                endpoint: endpoint,
                auth: oauth,
                storedID: "stored-1",
                title: "Title",
                archived: nil
            )
        ) { error in
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }
        XCTAssertThrowsError(
            try AudioRequestBuilder.speechRequest(
                endpoint: endpoint,
                auth: oauth,
                text: "Read this"
            )
        ) { error in
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }

        do {
            _ = try await GatewayRESTClient.nativeOAuthProviders(endpoint: endpoint, session: session)
            XCTFail("Expected provider discovery to reject cleartext OAuth")
        } catch {
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }

        do {
            _ = try await GatewayRESTClient.exchangeNativeCode(
                endpoint: endpoint,
                code: "code",
                verifier: String(repeating: "v", count: 43),
                session: session
            )
            XCTFail("Expected token exchange to reject cleartext OAuth")
        } catch {
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }

        let client = GatewayRESTClient(
            endpoint: endpoint,
            credentials: .oauth(tokens, endpoint: endpoint.baseURL.absoluteString),
            session: session
        )
        do {
            _ = try await client.freshWebSocketTicket()
            XCTFail("Expected OAuth ticket minting to reject cleartext HTTP")
        } catch {
            XCTAssertEqual(error as? NativeOAuthError, .insecureTransport)
        }
    }

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

    func testGeneratedRequestUsesURLSafePKCEWithoutLeakingVerifier() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://agent.example")
        let request = try NativeAuthorizationRequest(endpoint: endpoint, provider: "nous")
        let components = try XCTUnwrap(URLComponents(url: request.authorizationURL, resolvingAgainstBaseURL: false))
        let query = Dictionary(uniqueKeysWithValues: try XCTUnwrap(components.queryItems).map { ($0.name, $0.value) })
        let expectedChallenge = Data(SHA256.hash(data: Data(request.verifier.utf8)))
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")

        XCTAssertGreaterThanOrEqual(request.verifier.count, 43)
        XCTAssertLessThanOrEqual(request.verifier.count, 128)
        XCTAssertEqual(query["code_challenge"]!, expectedChallenge)
        XCTAssertEqual(query["provider"]!, "nous")
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
        XCTAssertThrowsError(
            try request.authorizationCode(
                from: try XCTUnwrap(URL(string: "com.snowzlmbot.hermes.mobile:/oauth/callback?error=access_denied&state=state-1"))
            )
        ) { error in
            XCTAssertEqual(error as? NativeOAuthError, .providerRejected)
        }
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

    func testNativeProviderParsingExcludesPasswordProviders() {
        let providers = NativeOAuthProvider.parseList(["providers": [
            ["name": "password", "supports_password": true],
            ["name": "nous", "display_name": "Nous Research", "supports_password": false]
        ]])

        XCTAssertEqual(providers, [NativeOAuthProvider(name: "nous", displayName: "Nous Research")])
    }

    private func makeFailingSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [NativeOAuthFailingURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class NativeOAuthFailingURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 500,
            httpVersion: nil,
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
