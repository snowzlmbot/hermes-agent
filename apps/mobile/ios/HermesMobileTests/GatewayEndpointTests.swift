import XCTest
@testable import HermesMobile

final class GatewayEndpointTests: XCTestCase {
    func testNormalizesGatewayBaseAndBuildsSecureWebSocketURL() throws {
        let endpoint = try GatewayEndpoint(rawValue: "https://example.com/hermes/api/ws")

        XCTAssertEqual(endpoint.baseURL.absoluteString, "https://example.com/hermes")
        XCTAssertEqual(
            endpoint.webSocketURL(auth: .ticket("one time ticket")).absoluteString,
            "wss://example.com/hermes/api/ws?ticket=one%20time%20ticket"
        )
        XCTAssertEqual(endpoint.redactedDescription, "https://example.com/hermes")
    }

    func testRejectsInsecureRemoteEndpointsUnlessExplicitlyAllowed() {
        XCTAssertThrowsError(try GatewayEndpoint(rawValue: "http://gateway.example.com")) { error in
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }

        XCTAssertNoThrow(
            try GatewayEndpoint(rawValue: "http://gateway.example.com", allowInsecureRemote: true)
        )
        XCTAssertNoThrow(try GatewayEndpoint(rawValue: "http://127.0.0.1:8765"))
        XCTAssertNoThrow(try GatewayEndpoint(rawValue: "http://[::1]:8765"))
    }

    func testRejectsCredentialBearingQueryFragmentAndUnsupportedURLs() {
        XCTAssertThrowsError(try GatewayEndpoint(rawValue: "https://user:secret@example.com"))
        XCTAssertThrowsError(try GatewayEndpoint(rawValue: "ftp://example.com"))
        XCTAssertThrowsError(try GatewayEndpoint(rawValue: "https://example.com?token=secret"))
        XCTAssertThrowsError(try GatewayEndpoint(rawValue: "https://example.com/#fragment"))
    }

    func testStatusAuthClassificationDistinguishesStaticTokenAndNativePKCE() {
        XCTAssertEqual(GatewayAuthMode.fromStatus(["auth_required": false]), .token)
        XCTAssertEqual(GatewayAuthMode.fromStatus(["auth_required": true]), .oauth)
        XCTAssertTrue(GatewayAuthMode.nativePKCESupported(status: ["auth_flows": ["cookie", "native_pkce_mobile"]]))
        XCTAssertFalse(GatewayAuthMode.nativePKCESupported(status: ["auth_flows": ["cookie", "native_pkce"]]))
    }

    func testNativeOAuthReportsRegisteredApplicationCallbackSupport() {
        let capability = NativeOAuthCapability(status: ["auth_required": true, "auth_flows": ["native_pkce_mobile"]])

        XCTAssertEqual(capability.state, .available)
        XCTAssertTrue(capability.supportsASWebAuthenticationSessionCallback)
        XCTAssertFalse(capability.explanation.isEmpty)
    }

    func testNativeOAuthCapabilityRejectsLegacyLoopbackOnlyGateways() {
        let capability = NativeOAuthCapability(status: ["auth_required": true, "auth_flows": ["native_pkce"]])

        XCTAssertEqual(capability.state, .unavailable)
        XCTAssertFalse(capability.supportsASWebAuthenticationSessionCallback)
        XCTAssertFalse(capability.explanation.isEmpty)
    }
}
