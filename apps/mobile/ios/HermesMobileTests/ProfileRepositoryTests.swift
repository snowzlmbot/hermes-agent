import XCTest
@testable import HermesMobile

final class ProfileRepositoryTests: XCTestCase {
    func testProfileMetadataAndCredentialRoundTripUseSeparateStores() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let credentials = InMemoryCredentialStore()
        let repository = GatewayProfileRepository(profileStore: profiles, credentialStore: credentials)
        let profile = GatewayProfile(id: "default", name: "Primary", endpoint: "https://gateway.example.com", authMode: .token)
        let secret = try GatewayCredentials(endpoint: profile.endpoint, token: "secret")

        try await repository.save(profile: profile, credentials: secret)

        let connection = try await repository.load()
        let storedProfiles = try await profiles.load()
        let storedCredentials = try await credentials.load()
        XCTAssertEqual(connection?.profile, profile)
        XCTAssertEqual(connection?.credentials, secret)
        XCTAssertEqual(storedProfiles, [profile])
        XCTAssertEqual(storedCredentials, secret)
    }

    func testClearingRepositoryRemovesMetadataAndCredential() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let credentials = InMemoryCredentialStore()
        let repository = GatewayProfileRepository(profileStore: profiles, credentialStore: credentials)
        let profile = GatewayProfile(id: "default", name: "Primary", endpoint: "https://gateway.example.com", authMode: .token)

        try await repository.save(profile: profile, credentials: GatewayCredentials(token: "secret"))
        try await repository.clear()

        let connection = try await repository.load()
        let storedProfiles = try await profiles.load()
        let storedCredentials = try await credentials.load()
        XCTAssertNil(connection)
        XCTAssertTrue(storedProfiles.isEmpty)
        XCTAssertNil(storedCredentials)
    }

    @MainActor
    func testFailedCredentialClearDoesNotReportSuccessfulForget() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let credentials = FailingDeleteCredentialStore()
        let repository = GatewayProfileRepository(profileStore: profiles, credentialStore: credentials)
        let profile = GatewayProfile(endpoint: "https://gateway.example.com", authMode: .token)
        try await repository.save(profile: profile, credentials: GatewayCredentials(token: "secret"))
        let model = AppModel(
            dependencies: AppDependencies(
                profileRepository: repository,
                notificationService: NoopNotificationService(),
                attachmentImporter: AttachmentImportService()
            )
        )
        await model.bootstrap(arguments: ["--ui-demo"])

        await model.disconnectAndForget()
        let persistedConnection = try await repository.load()

        XCTAssertEqual(model.phase, .connected)
        XCTAssertNotNil(model.chatModel)
        XCTAssertNotNil(model.errorMessage)
        XCTAssertNotNil(persistedConnection)
    }
}

private actor FailingDeleteCredentialStore: CredentialStore {
    private var value: GatewayCredentials?

    func save(_ credentials: GatewayCredentials) async throws { value = credentials }
    func load() async throws -> GatewayCredentials? { value }
    func delete() async throws { throw CredentialError.keychainFailure(OSStatusCode(-1)) }
}

private actor NoopNotificationService: NotificationScheduling {
    func requestAuthorization() async -> Bool { true }
    func scheduleCompletion(sessionTitle: String, sessionID: String?) async {}
    func scheduleApproval(sessionID: String) async {}
    func scheduleInput(sessionID: String) async {}
}
