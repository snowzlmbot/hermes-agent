import Foundation
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
        XCTAssertNil(storedCredentials?.profileID)
    }

    func testSecureOnlyPolicyRejectsSavingCleartextProfile() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let credentials = InMemoryCredentialStore()
        let repository = GatewayProfileRepository(
            profileStore: profiles,
            credentialStore: credentials,
            allowsInsecureTransport: false
        )
        let profile = GatewayProfile(endpoint: "http://127.0.0.1:8765", authMode: .token, allowInsecure: true)
        do {
            try await repository.save(profile: profile, credentials: GatewayCredentials(token: "token"))
            XCTFail("Expected cleartext rejection")
        } catch {
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }
        let legacy = GatewayProfile(endpoint: "https://gateway.example", authMode: .token, allowInsecure: true)
        do {
            try await repository.save(profile: legacy, credentials: GatewayCredentials(token: "token"))
            XCTFail("Expected legacy capability rejection")
        } catch {
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }
        let storedProfiles = try await profiles.load()
        let storedCredentials = try await credentials.load()
        XCTAssertTrue(storedProfiles.isEmpty)
        XCTAssertNil(storedCredentials)
    }

    func testSecureOnlyPolicyRejectsLoadedCleartextProfile() async throws {
        let profile = GatewayProfile(endpoint: "http://gateway.local", authMode: .token, allowInsecure: true)
        let credentials = InMemoryCredentialStore()
        try await credentials.save(GatewayCredentials(token: "token"))
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [profile]),
            credentialStore: credentials,
            allowsInsecureTransport: false
        )
        do {
            _ = try await repository.load()
            XCTFail("Expected cleartext rejection")
        } catch {
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }
    }

    func testDebugPolicyAllowsExplicitCleartextProfile() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let repository = GatewayProfileRepository(
            profileStore: profiles,
            credentialStore: InMemoryCredentialStore(),
            allowsInsecureTransport: true
        )
        let denied = GatewayProfile(endpoint: "http://127.0.0.1", authMode: .token)
        do {
            try await repository.save(profile: denied, credentials: GatewayCredentials(token: "token"))
            XCTFail("Expected explicit consent")
        } catch {
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }
        let profile = GatewayProfile(endpoint: "http://gateway.local", authMode: .token, allowInsecure: true)
        try await repository.save(profile: profile, credentials: GatewayCredentials(token: "token"))
        let stored = try await profiles.load()
        XCTAssertEqual(stored, [profile])
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

    func testOAuthProfileGuardsRunBeforeCredentialAccess() async throws {
        let oauthProfile = GatewayProfile(
            endpoint: "http://127.0.0.1",
            authMode: .oauth,
            allowInsecure: true
        )
        let credentials = CountingCredentialStore()
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [oauthProfile]),
            credentialStore: credentials,
            allowsInsecureTransport: true
        )
        do {
            _ = try await repository.load()
            XCTFail("Expected OAuth cleartext rejection")
        } catch {
            XCTAssertEqual(error as? GatewayEndpointError, .insecureRemoteEndpoint)
        }
        let loadCount = await credentials.loadCount
        XCTAssertEqual(loadCount, 0)
        let tokens = NativeTokenSet(
            accessToken: "access",
            refreshToken: "refresh",
            expiresAt: 4_102_444_800,
            provider: "provider"
        )
        let tokenProfile = GatewayProfile(endpoint: "https://gateway.example", authMode: .token)
        do {
            try await repository.save(
                profile: tokenProfile,
                credentials: .oauth(tokens, endpoint: tokenProfile.endpoint)
            )
            XCTFail("Expected auth mode mismatch")
        } catch {
            XCTAssertEqual(error as? CredentialError, .authModeMismatch)
        }
        let saveCount = await credentials.saveCount
        XCTAssertEqual(saveCount, 0)
        let mismatchedCredentials = InMemoryCredentialStore()
        try await mismatchedCredentials.save(.oauth(tokens, endpoint: tokenProfile.endpoint))
        let mismatchRepository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [tokenProfile]),
            credentialStore: mismatchedCredentials
        )
        do {
            _ = try await mismatchRepository.load()
            XCTFail("Expected stored auth mode mismatch")
        } catch {
            XCTAssertEqual(error as? CredentialError, .authModeMismatch)
        }
        let validCredentials = InMemoryCredentialStore()
        let validRepository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: validCredentials
        )
        let secureOAuth = GatewayProfile(endpoint: "https://gateway.example", authMode: .oauth)
        let secureCredentials = GatewayCredentials.oauth(
            tokens,
            endpoint: secureOAuth.endpoint,
            profileID: secureOAuth.id
        )
        try await validRepository.save(profile: secureOAuth, credentials: secureCredentials)
        let restored = try await validRepository.load()
        XCTAssertEqual(restored?.profile, secureOAuth)
        XCTAssertEqual(restored?.credentials, secureCredentials)
    }

    func testLegacyOAuthCredentialsMigrateProfileBindingOnlyWhenEndpointMatches() async throws {
        let profile = GatewayProfile(
            id: "profile-a",
            endpoint: "https://gateway.example:443/",
            authMode: .oauth
        )
        let credentials = InMemoryCredentialStore()
        let legacy = GatewayCredentials.oauth(
            oauthTokens(),
            endpoint: "https://GATEWAY.example"
        )
        try await credentials.save(legacy)
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [profile]),
            credentialStore: credentials
        )

        let restored = try await repository.load()
        let migrated = try await credentials.load()

        XCTAssertEqual(restored?.credentials.profileID, profile.id)
        XCTAssertEqual(migrated?.profileID, profile.id)
        XCTAssertEqual(migrated?.endpoint, legacy.endpoint)
        XCTAssertEqual(migrated?.auth, legacy.auth)
    }

    func testLegacyOAuthCredentialsFailClosedWithoutMigrationOnEndpointMismatch() async throws {
        let profile = GatewayProfile(
            id: "profile-a",
            endpoint: "https://gateway.example",
            authMode: .oauth
        )
        let credentials = InMemoryCredentialStore()
        let legacy = GatewayCredentials.oauth(
            oauthTokens(),
            endpoint: "https://other.example"
        )
        try await credentials.save(legacy)
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [profile]),
            credentialStore: credentials
        )

        do {
            _ = try await repository.load()
            XCTFail("Expected OAuth endpoint mismatch")
        } catch {
            XCTAssertEqual(error as? CredentialError, .endpointMismatch)
        }

        let unchanged = try await credentials.load()
        XCTAssertEqual(unchanged, legacy)
    }

    func testBoundOAuthCredentialsFailClosedOnProfileMismatch() async throws {
        let profile = GatewayProfile(
            id: "profile-a",
            endpoint: "https://gateway.example",
            authMode: .oauth
        )
        let credentials = InMemoryCredentialStore()
        let boundToOtherProfile = GatewayCredentials.oauth(
            oauthTokens(),
            endpoint: profile.endpoint,
            profileID: "profile-b"
        )
        try await credentials.save(boundToOtherProfile)
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(profiles: [profile]),
            credentialStore: credentials
        )

        do {
            _ = try await repository.load()
            XCTFail("Expected OAuth profile mismatch")
        } catch {
            XCTAssertEqual(error as? CredentialError, .profileMismatch)
        }

        let unchanged = try await credentials.load()
        XCTAssertEqual(unchanged, boundToOtherProfile)
    }

    func testOAuthBindingWriteRollsBackWhenProfileSaveFails() async throws {
        let profile = GatewayProfile(
            id: "profile-a",
            endpoint: "https://gateway.example",
            authMode: .oauth
        )
        let previous = try GatewayCredentials(endpoint: profile.endpoint, token: "previous-token")
        let credentials = RecordingCredentialStore(value: previous)
        let repository = GatewayProfileRepository(
            profileStore: FailingSaveGatewayProfileStore(),
            credentialStore: credentials
        )
        let legacy = GatewayCredentials.oauth(oauthTokens(), endpoint: profile.endpoint)

        do {
            try await repository.save(profile: profile, credentials: legacy)
            XCTFail("Expected profile persistence failure")
        } catch {
            XCTAssertEqual(error as? ProfileStoreTestError, .saveFailed)
        }

        let writes = await credentials.savedValues
        XCTAssertEqual(writes.count, 2)
        XCTAssertEqual(writes.first?.profileID, profile.id)
        XCTAssertEqual(writes.last, previous)
        let rolledBack = try await credentials.load()
        XCTAssertEqual(rolledBack, previous)
    }

    @MainActor
    func testReleaseModelRejectsInsecureCapabilityBeforePersistence() async throws {
        let profiles = InMemoryGatewayProfileStore()
        let credentials = InMemoryCredentialStore()
        let repository = GatewayProfileRepository(
            profileStore: profiles,
            credentialStore: credentials
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UnexpectedRequestURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: repository,
            notificationService: NoopNotificationService(),
            attachmentImporter: AttachmentImportService(),
            urlSession: session,
            allowsInsecureTransport: false
        ))
        await model.connect(
            address: "https://gateway.example",
            token: "token",
            allowInsecure: true
        )
        let storedProfiles = try await profiles.load()
        let storedCredentials = try await credentials.load()
        XCTAssertNotNil(model.errorMessage)
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

private func oauthTokens() -> NativeTokenSet {
    NativeTokenSet(
        accessToken: "access",
        refreshToken: "refresh",
        expiresAt: 4_102_444_800,
        provider: "provider"
    )
}

private actor CountingCredentialStore: CredentialStore {
    private(set) var loadCount = 0
    private(set) var saveCount = 0

    func save(_ credentials: GatewayCredentials) async throws { saveCount += 1 }
    func load() async throws -> GatewayCredentials? {
        loadCount += 1
        return nil
    }
    func delete() async throws {}
}

private actor FailingDeleteCredentialStore: CredentialStore {
    private var value: GatewayCredentials?

    func save(_ credentials: GatewayCredentials) async throws { value = credentials }
    func load() async throws -> GatewayCredentials? { value }
    func delete() async throws { throw CredentialError.keychainFailure(OSStatusCode(-1)) }
}

private enum ProfileStoreTestError: Error, Equatable {
    case saveFailed
}

private actor FailingSaveGatewayProfileStore: GatewayProfileStore {
    func load() async throws -> [GatewayProfile] { [] }
    func save(_ profile: GatewayProfile) async throws { throw ProfileStoreTestError.saveFailed }
    func deleteAll() async throws {}
}

private actor RecordingCredentialStore: CredentialStore {
    private var value: GatewayCredentials?
    private(set) var savedValues: [GatewayCredentials] = []

    init(value: GatewayCredentials?) {
        self.value = value
    }

    func save(_ credentials: GatewayCredentials) async throws {
        savedValues.append(credentials)
        value = credentials
    }

    func load() async throws -> GatewayCredentials? { value }
    func delete() async throws { value = nil }
}

private final class UnexpectedRequestURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        XCTFail("Transport policy must reject before network access")
        client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
    }

    override func stopLoading() {}
}

private actor NoopNotificationService: NotificationScheduling {
    func requestAuthorization() async -> Bool { true }
    func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async {}
    func scheduleApproval(route: NotificationRoute) async {}
    func scheduleInput(route: NotificationRoute) async {}
}
