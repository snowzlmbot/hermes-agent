import XCTest
@testable import HermesMobile

final class SessionRecoveryTests: XCTestCase {
    @MainActor
    func testRepeatedActiveSceneEventsShareOneDirectedRecovery() async throws {
        let profile = GatewayProfile(
            id: "default",
            endpoint: "https://gateway.example.com",
            authMode: .token
        )
        let selections = InMemoryStoredSessionSelectionStore(
            selections: [profile.id: "stored-target"]
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

        let initialSocket = RecoverySocket()
        let foregroundSocket = RecoverySocket()
        let sockets = RecoverySocketQueue([initialSocket, foregroundSocket])
        let model = AppModel(
            dependencies: AppDependencies(
                profileRepository: repository,
                notificationService: RecoveryNotificationService(),
                attachmentImporter: AttachmentImportService(),
                socketFactory: { _ in sockets.next() }
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
        XCTAssertEqual(initialResume[1].params["session_id"], .string("stored-target"))
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
        XCTAssertEqual(recoveryResume[1].params["session_id"], .string("stored-target"))
        await foregroundSocket.respond(
            to: recoveryResume[1],
            result: activeSessionResult(runtimeID: "runtime-foreground", storedID: "stored-target")
        )
        await model.waitForSceneRecovery()

        XCTAssertEqual(model.selectedSessionID, "stored-target")
        XCTAssertEqual(model.chatModel?.state.runtimeSessionID, "runtime-foreground")
        XCTAssertEqual(sockets.createdCount, 2)
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
                profileID: "default",
                maxAttempts: 1
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

        let missing = Task {
            try await model.restoreSession(storedSessionID: "stored-missing")
        }
        let secondList = await socket.waitForRequests(count: 3)
        await socket.respond(to: secondList[2], result: sessionListResult(["stored-other"]))
        let secondCreate = await socket.waitForRequests(count: 4)
        XCTAssertEqual(secondCreate[3].method, GatewayMethod.sessionCreate)
        await socket.respond(
            to: secondCreate[3],
            result: activeSessionResult(runtimeID: "runtime-replacement", storedID: "stored-replacement")
        )
        let replacement = try await missing.value
        XCTAssertEqual(replacement.storedID, "stored-replacement")
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-replacement")
    }
}

private actor RecoveryNotificationService: NotificationScheduling {
    func requestAuthorization() async -> Bool { true }
    func scheduleCompletion(sessionTitle: String, sessionID: String?) async {}
    func scheduleApproval(sessionID: String) async {}
    func scheduleInput(sessionID: String) async {}
}

private final class RecoverySocketQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [any GatewaySocket]
    private var count = 0

    init(_ sockets: [any GatewaySocket]) {
        self.sockets = sockets
    }

    var createdCount: Int {
        lock.withLock { count }
    }

    func next() -> any GatewaySocket {
        lock.withLock {
            count += 1
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
