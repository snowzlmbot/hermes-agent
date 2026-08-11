import XCTest
@testable import HermesMobile

final class GatewayTransportTests: XCTestCase {
    func testRequestCorrelationResolvesOutOfOrderResponsesAndPublishesEvents() async throws {
        let socket = TestSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("test-token"),
            socketFactory: { _ in socket }
        )

        try await transport.connect()
        let first = Task { try await transport.request(GatewayMethod.sessionList, params: [:]) }
        let second = Task { try await transport.request(GatewayMethod.modelOptions, params: [:]) }
        let requests = await socket.waitForRequests(count: 2)
        XCTAssertEqual(Set(requests.map(\.method)), Set([GatewayMethod.sessionList, GatewayMethod.modelOptions]))

        for request in requests.reversed() {
            await socket.push(.response(id: request.id, result: .object(["ok": .bool(true)])))
        }
        _ = try await first.value
        _ = try await second.value

        await socket.push(.event(GatewayEvent(type: .sessionsChanged, sessionID: nil, payload: nil)))
        let event = await transport.nextEvent()
        XCTAssertEqual(event?.type, .sessionsChanged)
    }

    func testReconnectHookIsCalledAfterSocketFailure() async throws {
        let firstSocket = TestSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let hook = HookRecorder()
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("test-token"),
            socketFactory: { _ in firstSocket },
            hooks: GatewayReconnectHooks(onReconnectAttempt: { attempt in
                await hook.record(attempt: attempt)
            })
        )

        try await transport.connect()
        await firstSocket.fail()
        try await transport.reconnect(maxAttempts: 1)
        let attempts = await hook.attempts
        XCTAssertEqual(attempts, [1])
    }

    func testConcurrentConnectsShareOneTicket() async throws {
        let socket = TestSocket()
        let source = TicketRecorder()
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .ticketProvider { try await source.nextSlow() },
            socketFactory: { _ in socket }
        )

        async let first: Void = transport.connect()
        async let second: Void = transport.connect()
        _ = try await (first, second)
        let ticketCount = await source.count
        XCTAssertEqual(ticketCount, 1)
    }

    func testTicketProviderMintsFreshTicketForEveryConnection() async throws {
        let socket = TestSocket()
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com")
        let ticketSource = TicketRecorder()
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .ticketProvider { await ticketSource.next() },
            socketFactory: { url in
                XCTAssertTrue(url.absoluteString.contains("ticket="))
                return socket
            }
        )

        try await transport.connect()
        await transport.disconnect()
        try await transport.connect()

        let count = await ticketSource.count
        XCTAssertEqual(count, 2)
    }

    func testReconnectInvalidatesStaleEventsBeforeFreshTicketArrives() async throws {
        let first = DelayedCancelSocket()
        let second = TestSocket()
        let sockets = SocketQueue([first, second])
        let tickets = ReconnectTicketGate()
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example.com")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .ticketProvider { try await tickets.next() },
            socketFactory: { _ in sockets.next() }
        )

        try await transport.connect()
        await first.waitUntilReceiving()
        let reconnect = Task { try await transport.reconnect(maxAttempts: 1) }
        await tickets.waitUntilReconnectRequested()

        let staleData = try JSONEncoder().encode(
            JSONRPCEventFrame(
                event: GatewayEvent(type: .sessionsChanged, sessionID: nil, payload: nil)
            )
        )
        await first.release(staleData)
        await tickets.releaseReconnect()
        try await reconnect.value

        await second.push(
            .event(GatewayEvent(type: .gatewayReady, sessionID: nil, payload: nil))
        )
        let event = await transport.nextEvent()
        XCTAssertEqual(event?.type, .gatewayReady)
    }
}

private actor ReconnectTicketGate {
    private var count = 0
    private var reconnectContinuation: CheckedContinuation<String, Error>?

    func next() async throws -> String {
        count += 1
        if count == 1 { return "ticket-initial" }
        return try await withCheckedThrowingContinuation { continuation in
            reconnectContinuation = continuation
        }
    }

    func waitUntilReconnectRequested() async {
        while reconnectContinuation == nil { await Task.yield() }
    }

    func releaseReconnect() {
        reconnectContinuation?.resume(returning: "ticket-reconnect")
        reconnectContinuation = nil
    }
}

private actor HookRecorder {
    var attempts: [Int] = []

    func record(attempt: Int) {
        attempts.append(attempt)
    }
}

private actor TicketRecorder {
    var count = 0

    func nextSlow() async throws -> String {
        try await Task.sleep(for: .milliseconds(100))
        return next()
    }

    func next() -> String {
        count += 1
        return "ticket-\(count)"
    }
}

private final class SocketQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [any GatewaySocket]

    init(_ sockets: [any GatewaySocket]) {
        self.sockets = sockets
    }

    func next() -> any GatewaySocket {
        lock.lock()
        defer { lock.unlock() }
        return sockets.removeFirst()
    }
}

private actor DelayedCancelSocket: GatewaySocket {
    private var continuation: CheckedContinuation<Data, Error>?

    func send(_ data: Data) async throws {}

    func receive() async throws -> Data {
        try await withCheckedThrowingContinuation { continuation = $0 }
    }

    func waitUntilReceiving() async {
        while continuation == nil { await Task.yield() }
    }

    func cancel() async {}

    func release(_ data: Data) {
        continuation?.resume(returning: data)
        continuation = nil
    }

    func releaseFailure() {
        continuation?.resume(throwing: GatewayTransportError.connectionLost)
        continuation = nil
    }
}

private actor TestSocket: GatewaySocket {
    private var requests: [JSONRPCRequest] = []
    private var waiters: [(Int, CheckedContinuation<[JSONRPCRequest], Never>)] = []
    private var incoming: [Data] = []
    private var incomingWaiters: [CheckedContinuation<Data, Error>] = []
    private var failed = false

    func send(_ data: Data) async throws {
        let request = try JSONDecoder().decode(JSONRPCRequest.self, from: data)
        requests.append(request)
        fulfillRequestWaiters()
    }

    func receive() async throws -> Data {
        if let data = incoming.first {
            incoming.removeFirst()
            return data
        }
        if failed {
            throw GatewayTransportError.connectionLost
        }
        return try await withCheckedThrowingContinuation { continuation in
            incomingWaiters.append(continuation)
        }
    }

    func cancel() async {
        failed = true
        for continuation in incomingWaiters {
            continuation.resume(throwing: GatewayTransportError.connectionLost)
        }
        incomingWaiters.removeAll()
    }

    func waitForRequests(count: Int) async -> [JSONRPCRequest] {
        if requests.count >= count {
            return Array(requests.prefix(count))
        }
        return await withCheckedContinuation { continuation in
            waiters.append((count, continuation))
        }
    }

    func push(_ frame: TestIncomingFrame) {
        let data: Data
        switch frame {
        case .response(let id, let result):
            data = try! JSONEncoder().encode(JSONRPCResponse(id: id, result: result))
        case .event(let event):
            data = try! JSONEncoder().encode(JSONRPCEventFrame(event: event))
        }
        if let continuation = incomingWaiters.first {
            incomingWaiters.removeFirst()
            continuation.resume(returning: data)
        } else {
            incoming.append(data)
        }
    }

    func fail() async {
        await cancel()
    }

    private func fulfillRequestWaiters() {
        let ready = waiters.filter { requests.count >= $0.0 }
        waiters.removeAll { requests.count >= $0.0 }
        for (_, continuation) in ready {
            continuation.resume(returning: requests)
        }
    }
}

private enum TestIncomingFrame {
    case response(id: JSONRPCID, result: JSONValue)
    case event(GatewayEvent)
}
