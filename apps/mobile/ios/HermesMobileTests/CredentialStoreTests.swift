import XCTest
@testable import HermesMobile

final class CredentialStoreTests: XCTestCase {
    func testInMemoryCredentialStoreRoundTripsAndDeletesCredentials() async throws {
        let store = InMemoryCredentialStore()
        let credentials = try GatewayCredentials(token: "token")

        try await store.save(credentials)
        let loaded = try await store.load()
        XCTAssertEqual(loaded, credentials)

        try await store.delete()
        let deleted = try await store.load()
        XCTAssertNil(deleted)
    }

    func testCredentialsRejectEmptyEndpointAndToken() {
        XCTAssertThrowsError(try GatewayCredentials(endpoint: "", token: "x"))
        XCTAssertThrowsError(try GatewayCredentials(endpoint: "https://example.com", token: ""))
    }

    func testKeychainItemIdentifierIsStableAndDoesNotContainSecret() {
        let identifier = KeychainCredentialStore.itemIdentifier(profileID: "default")

        XCTAssertEqual(identifier, "com.snowzlmbot.hermes.mobile.credentials.default")
        XCTAssertFalse(identifier.contains("token"))
    }

    func testOAuthTokenSetCanBeStoredSeparatelyFromProfileMetadata() async throws {
        let store = InMemoryCredentialStore()
        let tokens = NativeTokenSet(accessToken: "access", refreshToken: "refresh", expiresAt: 1_900_000_000, provider: "nous")

        try await store.save(.oauth(tokens))
        let loaded = try await store.load()
        XCTAssertEqual(loaded, .oauth(tokens))
    }
}
