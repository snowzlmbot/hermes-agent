import XCTest
@testable import HermesMobile

@MainActor
final class ChatModelTests: XCTestCase {
    func testSendAndStopUseRuntimeSessionIdentity() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.send(text: "hello")
        try await model.stop()

        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.promptSubmit, GatewayMethod.sessionInterrupt])
        XCTAssertEqual(requests[0].params?["session_id"], .string("runtime-1"))
        XCTAssertEqual(requests[1].params?["session_id"], .string("runtime-1"))
    }

    func testCurrentSessionTitleUsesRuntimeIdentity() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.renameCurrentSession("Roadmap")

        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.sessionTitle])
        XCTAssertEqual(requests[0].params?["session_id"], .string("runtime-1"))
    }

    func testInjectedArchiveStoreCanHideSessionWithoutSocketTraffic() async throws {
        let socket = RecordingSocket()
        let archive = InMemorySessionArchiveStore()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport, archiveStore: archive)

        try await model.connect()
        try await model.setArchived(true, storedSessionID: "stored-1")

        let archived = await archive.isArchived("stored-1")
        let requests = await socket.requests
        XCTAssertTrue(archived)
        XCTAssertTrue(requests.isEmpty)
    }

    func testRemoteArchiveUsesDurableSessionMutationClient() async throws {
        let socket = RecordingSocket()
        let archive = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport, sessionMutationClient: archive)

        try await model.connect()
        try await model.setArchived(true, storedSessionID: "stored-1")

        let mutations = await archive.mutations
        let requests = await socket.requests
        XCTAssertEqual(mutations, [.init(storedID: "stored-1", title: nil, archived: true, pinned: nil)])
        XCTAssertTrue(requests.isEmpty)
    }

    func testRemoteDeleteUsesDurableSessionMutationClient() async throws {
        let socket = RecordingSocket()
        let archive = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport, sessionMutationClient: archive)

        try await model.connect()
        try await model.delete(storedSessionID: "stored-2")

        let deletions = await archive.deletedIDs
        let requests = await socket.requests
        XCTAssertEqual(deletions, ["stored-2"])
        XCTAssertTrue(requests.isEmpty)
    }

    func testPromptResponsesUseExactGatewayParameterShapes() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")

        try await model.respondToApproval(.once)
        try await model.respondToClarify(requestID: "clarify-1", answer: "A")
        try await model.respondToSecret(requestID: "secret-1", value: "hidden")
        try await model.respondToSudo(requestID: "sudo-1", password: "hidden")

        let requests = await socket.requests
        XCTAssertEqual(requests[0].params, ["session_id": .string("runtime-1"), "choice": .string("once")])
        XCTAssertEqual(requests[1].params, ["request_id": .string("clarify-1"), "answer": .string("A")])
        XCTAssertEqual(requests[2].params, ["request_id": .string("secret-1"), "value": .string("hidden")])
        XCTAssertEqual(requests[3].params, ["request_id": .string("sudo-1"), "password": .string("hidden")])
    }
}

private actor RecordingSocket: GatewaySocket {
    var requests: [JSONRPCRequest] = []
    private var responses: [Data] = []
    private var waiters: [CheckedContinuation<Data, Error>] = []

    func send(_ data: Data) async throws {
        let request = try JSONDecoder().decode(JSONRPCRequest.self, from: data)
        requests.append(request)
        let response = try JSONEncoder().encode(
            JSONRPCResponse(id: request.id, result: .object(["status": .string("ok")]))
        )
        if let waiter = waiters.first {
            waiters.removeFirst()
            waiter.resume(returning: response)
        } else {
            responses.append(response)
        }
    }

    func receive() async throws -> Data {
        if let response = responses.first {
            responses.removeFirst()
            return response
        }
        return try await withCheckedThrowingContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func cancel() async {
        for waiter in waiters {
            waiter.resume(throwing: GatewayTransportError.connectionLost)
        }
        waiters.removeAll()
    }
}

private actor RecordingSessionMutationClient: SessionMutationClient {
    var mutations: [SessionMutation] = []
    var deletedIDs: [String] = []

    func patchSession(_ mutation: SessionMutation) async throws {
        mutations.append(mutation)
    }

    func deleteSession(_ storedID: String) async throws {
        deletedIDs.append(storedID)
    }
}
