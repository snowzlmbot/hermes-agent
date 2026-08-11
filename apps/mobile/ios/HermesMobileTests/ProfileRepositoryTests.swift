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
        let selections = InMemoryStoredSessionSelectionStore()
        let repository = GatewayProfileRepository(
            profileStore: profiles,
            credentialStore: credentials,
            sessionSelectionStore: selections
        )
        let profile = GatewayProfile(id: "default", name: "Primary", endpoint: "https://gateway.example.com", authMode: .token)

        try await repository.save(profile: profile, credentials: GatewayCredentials(token: "secret"))
        await repository.saveStoredSessionID("stored-session", profileID: profile.id)
        try await repository.clear()

        let connection = try await repository.load()
        let storedProfiles = try await profiles.load()
        let storedCredentials = try await credentials.load()
        let storedSessionID = await repository.loadStoredSessionID(profileID: profile.id)
        XCTAssertNil(connection)
        XCTAssertTrue(storedProfiles.isEmpty)
        XCTAssertNil(storedCredentials)
        XCTAssertNil(storedSessionID)
    }

    func testSessionSelectionRepositorySeparatesEndpointsWithTheSameProfileID() async {
        let selections = InMemoryStoredSessionSelectionStore()
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: InMemoryCredentialStore(),
            sessionSelectionStore: selections
        )
        let first = GatewayProfile(
            id: "default",
            endpoint: "https://first.example.com",
            authMode: .token
        )
        let second = GatewayProfile(
            id: "default",
            endpoint: "https://second.example.com",
            authMode: .token
        )

        await repository.saveStoredSessionID("stored-first", for: first)
        await repository.saveStoredSessionID("stored-second", for: second)

        let firstSelection = await repository.loadStoredSessionID(for: first)
        let secondSelection = await repository.loadStoredSessionID(for: second)
        XCTAssertEqual(firstSelection, "stored-first")
        XCTAssertEqual(secondSelection, "stored-second")
    }

    func testSessionSelectionStorePersistsOnlyStoredIdentifierByProfile() async {
        let suiteName = "ProfileRepositoryTests.\(UUID().uuidString)"
        let storageKey = "session-selection"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = UserDefaultsStoredSessionSelectionStore(
            suiteName: suiteName,
            storageKey: storageKey
        )

        await store.save("stored-only", profileID: "profile-a")
        let profileASelection = await store.load(profileID: "profile-a")
        let profileBSelection = await store.load(profileID: "profile-b")

        XCTAssertEqual(profileASelection, "stored-only")
        XCTAssertNil(profileBSelection)
        XCTAssertEqual(
            defaults.dictionary(forKey: storageKey) as? [String: String],
            ["profile-a": "stored-only"]
        )
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
    func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async {}
    func scheduleApproval(route: NotificationRoute) async {}
    func scheduleInput(route: NotificationRoute) async {}
}
