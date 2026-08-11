import Foundation

public enum GatewayTransportError: Error, Equatable, Sendable {
    case notConnected
    case connectionLost
    case invalidFrame
    case rpc(JSONRPCError)
    case authenticationUnavailable
    case reconnectExhausted
}

public protocol GatewaySocket: Sendable {
    func send(_ data: Data) async throws
    func receive() async throws -> Data
    func cancel() async
}

public actor URLSessionGatewaySocket: GatewaySocket {
    private let task: URLSessionWebSocketTask

    public init(url: URL, session: URLSession = .shared) {
        self.task = session.webSocketTask(with: url)
        self.task.resume()
    }

    public func send(_ data: Data) async throws {
        try await task.send(.data(data))
    }

    public func receive() async throws -> Data {
        switch try await task.receive() {
        case .data(let data): return data
        case .string(let string): return Data(string.utf8)
        @unknown default: throw GatewayTransportError.invalidFrame
        }
    }

    public func cancel() async {
        task.cancel(with: .goingAway, reason: nil)
    }
}

public struct GatewayReconnectHooks: Sendable {
    public var onReconnectAttempt: @Sendable (Int) async -> Void
    public var onConnected: @Sendable () async -> Void
    public var onDisconnected: @Sendable (String) async -> Void

    public init(
        onReconnectAttempt: @escaping @Sendable (Int) async -> Void = { _ in },
        onConnected: @escaping @Sendable () async -> Void = {},
        onDisconnected: @escaping @Sendable (String) async -> Void = { _ in }
    ) {
        self.onReconnectAttempt = onReconnectAttempt
        self.onConnected = onConnected
        self.onDisconnected = onDisconnected
    }
}

public actor HermesGatewayTransport {
    public typealias SocketFactory = @Sendable (URL) -> any GatewaySocket

    private let endpoint: GatewayEndpoint
    private let auth: GatewayAuth
    private let socketFactory: SocketFactory
    private let hooks: GatewayReconnectHooks
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private struct ConnectionAttempt {
        let id: Int
        let task: Task<GatewayAuth, Error>
    }

    private var socket: (any GatewaySocket)?
    private var receiver: Task<Void, Never>?
    private var connectionAttempt: ConnectionAttempt?
    private var connectionAttemptSequence = 0
    private var connectionGeneration = 0
    private var requestSequence = 0
    private var pending: [JSONRPCID: CheckedContinuation<JSONValue, Error>] = [:]
    private var events: [GatewayEvent] = []
    private var eventWaiters: [CheckedContinuation<GatewayEvent?, Never>] = []
    private var intentionallyDisconnected = false

    public init(
        endpoint: GatewayEndpoint,
        auth: GatewayAuth,
        socketFactory: @escaping SocketFactory = { URLSessionGatewaySocket(url: $0) },
        hooks: GatewayReconnectHooks = .init()
    ) {
        self.endpoint = endpoint
        self.auth = auth
        self.socketFactory = socketFactory
        self.hooks = hooks
    }

    public func connect() async throws {
        intentionallyDisconnected = false
        guard socket == nil else { return }

        let attempt: ConnectionAttempt
        if let current = connectionAttempt {
            attempt = current
        } else {
            connectionAttemptSequence += 1
            let id = connectionAttemptSequence
            let auth = self.auth
            let task = Task<GatewayAuth, Error> {
                try await Self.resolveDialAuth(auth)
            }
            attempt = ConnectionAttempt(id: id, task: task)
            connectionAttempt = attempt
        }

        do {
            let dialAuth = try await attempt.task.value
            if socket != nil { return }
            guard connectionAttempt?.id == attempt.id, !intentionallyDisconnected else {
                throw CancellationError()
            }
            connectionAttempt = nil
            let nextSocket = socketFactory(endpoint.webSocketURL(auth: dialAuth))
            connectionGeneration += 1
            let generation = connectionGeneration
            socket = nextSocket
            receiver = Task { [weak self] in
                await self?.receiveLoop(socket: nextSocket, generation: generation)
            }
            await hooks.onConnected()
        } catch {
            if connectionAttempt?.id == attempt.id {
                connectionAttempt = nil
            }
            throw error
        }
    }

    public func disconnect() async {
        intentionallyDisconnected = true
        connectionGeneration += 1
        connectionAttempt?.task.cancel()
        connectionAttempt = nil
        receiver?.cancel()
        receiver = nil
        let current = socket
        socket = nil
        await current?.cancel()
        failPending(with: GatewayTransportError.connectionLost)
        finishEventWaiters()
    }

    public func request(_ method: String, params: [String: JSONValue]) async throws -> JSONValue {
        guard let socket else { throw GatewayTransportError.notConnected }
        requestSequence += 1
        let id = JSONRPCID.string("ios-\(requestSequence)")
        let data = try encoder.encode(JSONRPCRequest(id: id, method: method, params: params))

        return try await withCheckedThrowingContinuation { continuation in
            pending[id] = continuation
            Task { [weak self] in
                do {
                    try await socket.send(data)
                } catch {
                    await self?.rejectRequest(id: id, error: error)
                }
            }
        }
    }

    public func nextEvent() async -> GatewayEvent? {
        if !events.isEmpty { return events.removeFirst() }
        if socket == nil { return nil }
        return await withCheckedContinuation { continuation in
            eventWaiters.append(continuation)
        }
    }

    public func reconnect(maxAttempts: Int = 5) async throws {
        let attempts = max(1, maxAttempts)
        connectionAttempt?.task.cancel()
        connectionAttempt = nil
        receiver?.cancel()
        receiver = nil
        let oldSocket = socket
        socket = nil
        await oldSocket?.cancel()
        failPending(with: GatewayTransportError.connectionLost)

        for attempt in 1...attempts {
            await hooks.onReconnectAttempt(attempt)
            if attempt > 1 {
                let delay = min(pow(2.0, Double(attempt - 2)), 8.0)
                try await Task.sleep(for: .seconds(delay))
            }
            do {
                try await connect()
                return
            } catch where attempt < attempts {
                continue
            } catch {
                throw error
            }
        }
        throw GatewayTransportError.reconnectExhausted
    }

    private static func resolveDialAuth(_ auth: GatewayAuth) async throws -> GatewayAuth {
        switch auth {
        case .token, .ticket: return auth
        case .ticketProvider(let provider): return .ticket(try await provider())
        case .oauth: throw GatewayTransportError.authenticationUnavailable
        }
    }

    private func receiveLoop(socket: any GatewaySocket, generation: Int) async {
        do {
            while !Task.isCancelled {
                let data = try await socket.receive()
                guard generation == connectionGeneration else { return }
                let frame = try decoder.decode(JSONRPCInboundFrame.self, from: data)
                handle(frame)
            }
        } catch {
            if generation == connectionGeneration, !intentionallyDisconnected {
                self.socket = nil
                receiver = nil
                failPending(with: GatewayTransportError.connectionLost)
                await hooks.onDisconnected(String(describing: error))
            }
        }
    }

    private func handle(_ frame: JSONRPCInboundFrame) {
        switch frame {
        case .response(let response):
            guard let continuation = pending.removeValue(forKey: response.id) else { return }
            if let error = response.error {
                continuation.resume(throwing: GatewayTransportError.rpc(error))
            } else {
                continuation.resume(returning: response.result ?? .null)
            }
        case .event(let event):
            if let waiter = eventWaiters.first {
                eventWaiters.removeFirst()
                waiter.resume(returning: event)
            } else {
                events.append(event)
            }
        }
    }

    private func rejectRequest(id: JSONRPCID, error: Error) {
        pending.removeValue(forKey: id)?.resume(throwing: error)
    }

    private func failPending(with error: Error) {
        let current = pending.values
        pending.removeAll()
        for continuation in current { continuation.resume(throwing: error) }
    }

    private func finishEventWaiters() {
        let waiters = eventWaiters
        eventWaiters.removeAll()
        for waiter in waiters { waiter.resume(returning: nil) }
    }
}
