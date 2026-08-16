import XCTest
@testable import HermesMobile

final class SessionRecoveryTests: XCTestCase {
    @MainActor
    func testStaticTokenUsesFreshTicketsWithoutEnteringWebSocketURLOnReconnect() async throws {
        let staticToken = "static-token-must-stay-out-of-websocket-urls"
        let profile = GatewayProfile(
            id: "default",
            endpoint: "http://gateway.example.com",
            authMode: .token,
            allowInsecure: true
        )
        let selections = InMemoryStoredSessionSelectionStore(
            selections: [profile.sessionSelectionScope: "stored-target"]
        )
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: InMemoryCredentialStore(),
            sessionSelectionStore: selections,
            allowsInsecureTransport: true
        )
        try await repository.save(
            profile: profile,
            credentials: GatewayCredentials(token: staticToken)
        )

        let session = makeStaticTokenTicketSession()
        defer { session.invalidateAndCancel() }
        let initialSocket = RecoverySocket()
        let foregroundSocket = RecoverySocket()
        let sockets = RecoverySocketQueue([initialSocket, foregroundSocket])
        let model = AppModel(
            dependencies: AppDependencies(
                profileRepository: repository,
                notificationService: RecoveryNotificationService(),
                attachmentImporter: AttachmentImportService(),
                socketFactory: { url in sockets.next(url: url) },
                urlSession: session,
                allowsInsecureTransport: true
            )
        )

        let bootstrap = Task { await model.bootstrap(arguments: []) }
        let initialList = await initialSocket.waitForRequests(count: 1)
        XCTAssertEqual(initialList[0].method, GatewayMethod.sessionList)
        await initialSocket.respond(
            to: initialList[0],
            result: sessionListResult(["stored-other", "stored-target"])
        )
        let initialResume = await initialSocket.waitForRequests(count: 2)
        XCTAssertEqual(initialResume[1].method, GatewayMethod.sessionResume)
        XCTAssertEqual(initialResume[1].params?["session_id"], .string("stored-target"))
        await initialSocket.respond(
            to: initialResume[1],
            result: activeSessionResult(runtimeID: "runtime-initial", storedID: "stored-target")
        )
        await bootstrap.value

        model.setSceneActive(false)
        model.setSceneActive(true)
        model.setSceneActive(true)

        let recoveryList = await foregroundSocket.waitForRequests(count: 1)
        XCTAssertEqual(recoveryList[0].method, GatewayMethod.sessionList)
        XCTAssertEqual(sockets.createdCount, 2)
        await foregroundSocket.respond(
            to: recoveryList[0],
            result: sessionListResult(["stored-other", "stored-target"])
        )
        let recoveryResume = await foregroundSocket.waitForRequests(count: 2)
        XCTAssertEqual(recoveryResume[1].method, GatewayMethod.sessionResume)
        XCTAssertEqual(recoveryResume[1].params?["session_id"], .string("stored-target"))
        await foregroundSocket.respond(
            to: recoveryResume[1],
            result: activeSessionResult(runtimeID: "runtime-foreground", storedID: "stored-target")
        )
        await model.waitForSceneRecovery()

        XCTAssertEqual(model.selectedSessionID, "stored-target")
        XCTAssertEqual(model.chatModel?.state.runtimeSessionID, "runtime-foreground")
        XCTAssertEqual(sockets.createdCount, 2)
        let ticketRequests = StaticTokenTicketURLProtocol.records(for: staticToken)
        let webSocketURLs = sockets.createdURLs
        XCTAssertEqual(ticketRequests.count, 2)
        XCTAssertEqual(ticketRequests.map(\.url.path), ["/api/auth/ws-ticket", "/api/auth/ws-ticket"])
        XCTAssertEqual(ticketRequests.map(\.authorization), Array(repeating: "Bearer \(staticToken)", count: 2))
        XCTAssertEqual(ticketRequests.map(\.sessionToken), Array(repeating: staticToken, count: 2))
        XCTAssertEqual(webSocketURLs.count, 2)
        XCTAssertEqual(webSocketURLs.map(ticketQueryItems), ticketRequests.map { [URLQueryItem(name: "ticket", value: $0.ticket)] })
        XCTAssertEqual(Set(ticketRequests.map(\.ticket)).count, 2)
        XCTAssertTrue(webSocketURLs.allSatisfy { !$0.absoluteString.contains(staticToken) })
    }

    @MainActor
    func testSupersededReconnectListCannotResumeOrOverwriteNewerSession() async throws {
        let originalSocket = RecoverySocket()
        let replacementSocket = RecoverySocket()
        let sockets = RecoverySocketQueue([originalSocket, replacementSocket])
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("test-token"),
            socketFactory: { _ in sockets.next() }
        )
        let stableSummary = SessionSummary(storedID: "stored-stable", title: "Stable")
        let model = ChatModel(transport: transport, sessions: [stableSummary])
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-stable", storedID: "stored-stable")

        let staleRecovery = Task {
            try? await model.reconnectAndRestore(
                storedSessionID: "stored-stale",
                profileID: "default"
            )
        }
        let staleList = await replacementSocket.waitForRequests(count: 1)
        XCTAssertEqual(staleList[0].method, GatewayMethod.sessionList)

        let currentResume = Task {
            try await model.resume(storedSessionID: "stored-current", profileID: "default")
        }
        let requests = await replacementSocket.waitForRequests(count: 2)
        XCTAssertEqual(requests[1].method, GatewayMethod.sessionResume)
        await replacementSocket.respond(
            to: requests[1],
            result: activeSessionResult(runtimeID: "runtime-current", storedID: "stored-current")
        )
        _ = try await currentResume.value

        await replacementSocket.respond(
            to: staleList[0],
            result: sessionListResult(["stored-stale"])
        )
        _ = await staleRecovery.value

        let finalRequests = await replacementSocket.requestsSnapshot()
        XCTAssertEqual(finalRequests.count, 2)
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-current")
        XCTAssertEqual(model.state.storedSessionID, "stored-current")
        XCTAssertEqual(model.sessions, [stableSummary])
    }

    @MainActor
    func testRestoreWithoutStoredSelectionCreatesEvenWhenListHasSessions() async throws {
        let socket = RecoverySocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("test-token"),
            socketFactory: { _ in socket }
        )
        let model = ChatModel(transport: transport)
        try await model.connect()

        let restoration = Task {
            try await model.restoreSession(storedSessionID: nil, profileID: "default")
        }
        let list = await socket.waitForRequests(count: 1)
        XCTAssertEqual(list[0].method, GatewayMethod.sessionList)
        await socket.respond(to: list[0], result: sessionListResult(["stored-other"]))
        let create = await socket.waitForRequests(count: 2)
        XCTAssertEqual(create[1].method, GatewayMethod.sessionCreate)
        await socket.respond(
            to: create[1],
            result: activeSessionResult(runtimeID: "runtime-new", storedID: "stored-new")
        )

        let active = try await restoration.value
        XCTAssertEqual(active.storedID, "stored-new")
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-new")

        var invalidSelectionCleared = false
        model.invalidStoredSessionHandler = {
            invalidSelectionCleared = true
        }
        let missing = Task {
            try await model.restoreSession(storedSessionID: "stored-missing")
        }
        let secondList = await socket.waitForRequests(count: 3)
        await socket.respond(to: secondList[2], result: sessionListResult(["stored-other"]))
        let secondResume = await socket.waitForRequests(count: 4)
        XCTAssertEqual(secondResume[3].method, GatewayMethod.sessionResume)
        await socket.respondError(to: secondResume[3], error: JSONRPCError(code: 4007, message: "session not found"))
        let secondCreate = await socket.waitForRequests(count: 5)
        XCTAssertTrue(invalidSelectionCleared)
        XCTAssertEqual(secondCreate[4].method, GatewayMethod.sessionCreate)
        await socket.respond(
            to: secondCreate[4],
            result: activeSessionResult(runtimeID: "runtime-replacement", storedID: "stored-replacement")
        )
        let replacement = try await missing.value
        XCTAssertEqual(replacement.storedID, "stored-replacement")
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-replacement")
    }

    @MainActor
    func testSessionInfoStoredIdentityEmitsSelectionCorrection() async throws {
        let socket = RecoverySocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("test-token"),
            socketFactory: { _ in socket }
        )
        let model = ChatModel(transport: transport)
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-current", storedID: "stored-old")
        let corrected = expectation(description: "stored identity corrected")
        model.signalHandler = { signal in
            guard signal == .sessionSelectionChanged(storedID: "stored-canonical") else { return }
            corrected.fulfill()
        }

        await socket.push(
            GatewayEvent(
                type: .sessionInfo,
                sessionID: "runtime-current",
                payload: .object(["stored_session_id": .string("stored-canonical")])
            )
        )

        await fulfillment(of: [corrected], timeout: 1)
        XCTAssertEqual(model.state.storedSessionID, "stored-canonical")
    }

    @MainActor
    func testColdNotificationRouteWaitsForConnectionThenSelectsStoredSessionOnce() async throws {
        let profile = GatewayProfile(
            id: "default",
            endpoint: "https://gateway.example.com",
            authMode: .token
        )
        let selections = InMemoryStoredSessionSelectionStore(
            selections: [profile.sessionSelectionScope: "stored-initial"]
        )
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: InMemoryCredentialStore(),
            sessionSelectionStore: selections
        )
        try await repository.save(
            profile: profile,
            credentials: GatewayCredentials(token: "secret")
        )
        let session = makeStaticTokenTicketSession()
        defer { session.invalidateAndCancel() }
        let socket = RecoverySocket()
        let notifications = RecoveryNotificationService()
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: repository,
            notificationService: notifications,
            attachmentImporter: AttachmentImportService(),
            socketFactory: { _ in socket },
            urlSession: session
        ))

        await model.handleNotificationRoute(NotificationRoute(
            storedSessionID: "stored-notification",
            profileScope: NotificationRouteMetadata.profileScope(for: profile.sessionSelectionScope)
        ))
        XCTAssertEqual(model.pendingNotificationRouteCount, 1)

        let bootstrap = Task { await model.bootstrap(arguments: []) }
        let list = await socket.waitForRequests(count: 1)
        await socket.respond(
            to: list[0],
            result: sessionListResult(["stored-initial", "stored-notification"])
        )
        let initialResume = await socket.waitForRequests(count: 2)
        XCTAssertEqual(initialResume[1].params?["session_id"], .string("stored-initial"))
        await socket.respond(
            to: initialResume[1],
            result: activeSessionResult(runtimeID: "runtime-initial", storedID: "stored-initial")
        )
        let notificationResume = await socket.waitForRequests(count: 3)
        XCTAssertEqual(notificationResume[2].method, GatewayMethod.sessionResume)
        XCTAssertEqual(notificationResume[2].params?["session_id"], .string("stored-notification"))
        await socket.respond(
            to: notificationResume[2],
            result: activeSessionResult(runtimeID: "runtime-notification", storedID: "stored-notification")
        )
        await bootstrap.value

        XCTAssertEqual(model.selectedSessionID, "stored-notification")
        XCTAssertEqual(model.chatModel?.state.storedSessionID, "stored-notification")
        XCTAssertEqual(model.pendingNotificationRouteCount, 0)
        let routedRequestCount = await socket.requestsSnapshot().count
        XCTAssertEqual(routedRequestCount, 3)

        await model.handleNotificationRoute(NotificationRoute(
            storedSessionID: "stored-wrong-gateway",
            profileScope: String(repeating: "b", count: 64)
        ))
        XCTAssertEqual(model.selectedSessionID, "stored-notification")
        let finalRequestCount = await socket.requestsSnapshot().count
        XCTAssertEqual(finalRequestCount, 3)
    }

    @MainActor
    func testHotNotificationRouteCannotOverwriteNewerSessionSelectionWhenLate() async throws {
        let profile = GatewayProfile(
            id: "default",
            endpoint: "https://gateway.example.com",
            authMode: .token
        )
        let selections = InMemoryStoredSessionSelectionStore(
            selections: [profile.sessionSelectionScope: "stored-initial"]
        )
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: InMemoryCredentialStore(),
            sessionSelectionStore: selections
        )
        try await repository.save(
            profile: profile,
            credentials: GatewayCredentials(token: "secret")
        )
        let session = makeStaticTokenTicketSession()
        defer { session.invalidateAndCancel() }
        let socket = RecoverySocket()
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: repository,
            notificationService: RecoveryNotificationService(),
            attachmentImporter: AttachmentImportService(),
            socketFactory: { _ in socket },
            urlSession: session
        ))

        let bootstrap = Task { await model.bootstrap(arguments: []) }
        let list = await socket.waitForRequests(count: 1)
        await socket.respond(
            to: list[0],
            result: sessionListResult(["stored-initial", "stored-notification", "stored-newer"])
        )
        let initialResume = await socket.waitForRequests(count: 2)
        await socket.respond(
            to: initialResume[1],
            result: activeSessionResult(runtimeID: "runtime-initial", storedID: "stored-initial")
        )
        await bootstrap.value

        let notificationRoute = Task {
            await model.handleNotificationRoute(NotificationRoute(
                storedSessionID: "stored-notification",
                profileScope: NotificationRouteMetadata.profileScope(for: profile.sessionSelectionScope)
            ))
        }
        let notificationResume = await socket.waitForRequests(count: 3)
        XCTAssertEqual(notificationResume[2].params?["session_id"], .string("stored-notification"))

        let newerSelection = Task { await model.selectSession("stored-newer") }
        let newerResume = await socket.waitForRequests(count: 4)
        XCTAssertEqual(newerResume[3].params?["session_id"], .string("stored-newer"))
        await socket.respond(
            to: newerResume[3],
            result: activeSessionResult(runtimeID: "runtime-newer", storedID: "stored-newer")
        )
        await newerSelection.value

        await socket.respond(
            to: notificationResume[2],
            result: activeSessionResult(runtimeID: "runtime-late", storedID: "stored-notification")
        )
        await notificationRoute.value

        XCTAssertEqual(model.selectedSessionID, "stored-newer")
        XCTAssertEqual(model.chatModel?.state.storedSessionID, "stored-newer")
        XCTAssertNil(model.errorMessage)
    }

    @MainActor
    func testForgetClearsPendingNotificationRoute() async {
        let repository = GatewayProfileRepository(
            profileStore: InMemoryGatewayProfileStore(),
            credentialStore: InMemoryCredentialStore(),
            sessionSelectionStore: InMemoryStoredSessionSelectionStore()
        )
        let model = AppModel(dependencies: AppDependencies(
            profileRepository: repository,
            notificationService: RecoveryNotificationService(),
            attachmentImporter: AttachmentImportService()
        ))

        await model.handleNotificationRoute(NotificationRoute(
            storedSessionID: "stored-pending",
            profileScope: String(repeating: "a", count: 64)
        ))
        XCTAssertEqual(model.pendingNotificationRouteCount, 1)

        await model.disconnectAndForget()

        XCTAssertEqual(model.pendingNotificationRouteCount, 0)
    }
}

private actor RecoveryNotificationService: NotificationScheduling {
    func requestAuthorization() async -> Bool { true }
    func scheduleCompletion(sessionTitle: String, route: NotificationRoute) async {}
    func scheduleApproval(route: NotificationRoute) async {}
    func scheduleInput(route: NotificationRoute) async {}
}

private struct StaticTokenTicketRequest: Sendable {
    let url: URL
    let authorization: String?
    let sessionToken: String?
    let ticket: String
}

private final class StaticTokenTicketURLProtocol: URLProtocol, @unchecked Sendable {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var captured: [StaticTokenTicketRequest] = []
    }

    private static let state = State()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    static func records(for sessionToken: String) -> [StaticTokenTicketRequest] {
        state.lock.withLock { state.captured.filter { $0.sessionToken == sessionToken } }
    }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: GatewayRESTError.invalidResponse)
            return
        }
        let ticket = "single-use-ticket-\(UUID().uuidString)"
        Self.state.lock.withLock {
            Self.state.captured.append(StaticTokenTicketRequest(
                url: url,
                authorization: request.value(forHTTPHeaderField: "Authorization"),
                sessionToken: request.value(forHTTPHeaderField: "X-Hermes-Session-Token"),
                ticket: ticket
            ))
        }
        guard let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        ) else {
            client?.urlProtocol(self, didFailWithError: GatewayRESTError.invalidResponse)
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{\"ticket\":\"\(ticket)\"}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private func makeStaticTokenTicketSession() -> URLSession {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StaticTokenTicketURLProtocol.self]
    return URLSession(configuration: configuration)
}

private func ticketQueryItems(_ url: URL) -> [URLQueryItem] {
    URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
}

private final class RecoverySocketQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [any GatewaySocket]
    private var count = 0
    private var urls: [URL] = []

    init(_ sockets: [any GatewaySocket]) {
        self.sockets = sockets
    }

    var createdCount: Int {
        lock.withLock { count }
    }

    var createdURLs: [URL] {
        lock.withLock { urls }
    }

    func next(url: URL? = nil) -> any GatewaySocket {
        lock.withLock {
            count += 1
            if let url { urls.append(url) }
            return sockets.removeFirst()
        }
    }
}

private actor RecoverySocket: GatewaySocket {
    private var requests: [JSONRPCRequest] = []
    private var requestWaiters: [(Int, CheckedContinuation<[JSONRPCRequest], Never>)] = []
    private var incoming: [Data] = []
    private var incomingWaiters: [CheckedContinuation<Data, Error>] = []
    private var cancelled = false

    func send(_ data: Data) async throws {
        let request = try JSONDecoder().decode(JSONRPCRequest.self, from: data)
        requests.append(request)
        let ready = requestWaiters.filter { requests.count >= $0.0 }
        requestWaiters.removeAll { requests.count >= $0.0 }
        for (_, continuation) in ready {
            continuation.resume(returning: requests)
        }
    }

    func receive() async throws -> Data {
        if let data = incoming.first {
            incoming.removeFirst()
            return data
        }
        if cancelled { throw GatewayTransportError.connectionLost }
        return try await withCheckedThrowingContinuation { continuation in
            incomingWaiters.append(continuation)
        }
    }

    func cancel() async {
        cancelled = true
        let waiters = incomingWaiters
        incomingWaiters.removeAll()
        for continuation in waiters {
            continuation.resume(throwing: GatewayTransportError.connectionLost)
        }
    }

    func waitForRequests(count: Int) async -> [JSONRPCRequest] {
        if requests.count >= count { return Array(requests.prefix(count)) }
        return await withCheckedContinuation { continuation in
            requestWaiters.append((count, continuation))
        }
    }

    func requestsSnapshot() -> [JSONRPCRequest] {
        requests
    }

    func respond(to request: JSONRPCRequest, result: JSONValue) {
        let data = try! JSONEncoder().encode(JSONRPCResponse(id: request.id, result: result))
        if let continuation = incomingWaiters.first {
            incomingWaiters.removeFirst()
            continuation.resume(returning: data)
        } else {
            incoming.append(data)
        }
    }

    func respondError(to request: JSONRPCRequest, error: JSONRPCError) {
        let data = try! JSONEncoder().encode(JSONRPCResponse(id: request.id, error: error))
        if let continuation = incomingWaiters.first {
            incomingWaiters.removeFirst()
            continuation.resume(returning: data)
        } else {
            incoming.append(data)
        }
    }

    func push(_ event: GatewayEvent) {
        let data = try! JSONEncoder().encode(JSONRPCEventFrame(event: event))
        if let continuation = incomingWaiters.first {
            incomingWaiters.removeFirst()
            continuation.resume(returning: data)
        } else {
            incoming.append(data)
        }
    }
}

private func sessionListResult(_ storedIDs: [String]) -> JSONValue {
    .object([
        "sessions": .array(storedIDs.enumerated().map { index, storedID in
            .object([
                "id": .string(storedID),
                "title": .string("Session \(index)"),
                "started_at": .number(Double(index + 1))
            ])
        })
    ])
}

private func activeSessionResult(runtimeID: String, storedID: String) -> JSONValue {
    .object([
        "session_id": .string(runtimeID),
        "stored_session_id": .string(storedID),
        "messages": .array([]),
        "model": .string("test-model"),
        "provider": .string("test-provider"),
        "reasoning_effort": .string("medium")
    ])
}
