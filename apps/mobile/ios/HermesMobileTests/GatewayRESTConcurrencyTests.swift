import Foundation
import XCTest
@testable import HermesMobile

final class GatewayRESTConcurrencyTests: XCTestCase {
    func testConcurrentExpiredTokenCallsShareRefreshAndUseNewTokenForTickets() async throws {
        let fixture = OAuthURLProtocolFixture(ticketPolicy: .acceptRefreshedToken)
        let session = makeSession(fixture: fixture)
        defer {
            session.invalidateAndCancel()
            OAuthTestURLProtocol.fixture = nil
        }
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example")
        let tokens = NativeTokenSet(
            accessToken: "old-access",
            refreshToken: "old-refresh",
            expiresAt: 1,
            provider: "nous"
        )
        let persistence = PersistenceRecorder()
        let client = GatewayRESTClient(
            endpoint: endpoint,
            credentials: .oauth(tokens, endpoint: endpoint.baseURL.absoluteString),
            session: session,
            persistCredentials: { value in await persistence.record(value) }
        )

        async let first: String = client.freshWebSocketTicket()
        async let second: String = client.freshWebSocketTicket()
        let tickets = try await (first, second)

        XCTAssertEqual(tickets.0, "fresh-ticket")
        XCTAssertEqual(tickets.1, "fresh-ticket")
        let snapshot = fixture.snapshot
        XCTAssertEqual(snapshot.refreshCount, 1)
        XCTAssertEqual(snapshot.ticketCount, 2)
        XCTAssertEqual(snapshot.ticketAuthorizationHeaders, ["Bearer new-access", "Bearer new-access"])
        let persisted = await persistence.values
        XCTAssertEqual(persisted.count, 1)
        XCTAssertEqual(persisted.first?.endpoint, endpoint.baseURL.absoluteString)
        XCTAssertEqual(persisted.first?.nativeTokens?.accessToken, "new-access")
        XCTAssertEqual(persisted.first?.nativeTokens?.refreshToken, "new-refresh")
    }

    func testConcurrentUnauthorizedCallsShareRefreshAndRetryWithNewToken() async throws {
        let fixture = OAuthURLProtocolFixture(ticketPolicy: .rejectOldToken)
        let session = makeSession(fixture: fixture)
        defer {
            session.invalidateAndCancel()
            OAuthTestURLProtocol.fixture = nil
        }
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example")
        let tokens = NativeTokenSet(
            accessToken: "old-access",
            refreshToken: "old-refresh",
            expiresAt: Date().timeIntervalSince1970 + 3_600,
            provider: "nous"
        )
        let persistence = PersistenceRecorder()
        let client = GatewayRESTClient(
            endpoint: endpoint,
            credentials: .oauth(tokens, endpoint: endpoint.baseURL.absoluteString),
            session: session,
            persistCredentials: { value in await persistence.record(value) }
        )

        async let first: String = client.freshWebSocketTicket()
        async let second: String = client.freshWebSocketTicket()
        _ = try await (first, second)

        let snapshot = fixture.snapshot
        XCTAssertEqual(snapshot.refreshCount, 1)
        XCTAssertEqual(snapshot.ticketCount, 4)
        XCTAssertEqual(snapshot.ticketAuthorizationHeaders.filter { $0 == "Bearer old-access" }.count, 2)
        XCTAssertEqual(snapshot.ticketAuthorizationHeaders.filter { $0 == "Bearer new-access" }.count, 2)
        let persisted = await persistence.values
        XCTAssertEqual(persisted.count, 1)
    }

    func testSecondUnauthorizedResponseDoesNotTriggerAnotherRefreshOrRetry() async throws {
        let fixture = OAuthURLProtocolFixture(ticketPolicy: .alwaysUnauthorized)
        let session = makeSession(fixture: fixture)
        defer {
            session.invalidateAndCancel()
            OAuthTestURLProtocol.fixture = nil
        }
        let endpoint = try GatewayEndpoint(rawValue: "https://gateway.example")
        let tokens = NativeTokenSet(
            accessToken: "old-access",
            refreshToken: "old-refresh",
            expiresAt: Date().timeIntervalSince1970 + 3_600,
            provider: "nous"
        )
        let persistence = PersistenceRecorder()
        let client = GatewayRESTClient(
            endpoint: endpoint,
            credentials: .oauth(tokens, endpoint: endpoint.baseURL.absoluteString),
            session: session,
            persistCredentials: { value in await persistence.record(value) }
        )

        do {
            _ = try await client.freshWebSocketTicket()
            XCTFail("Expected the retried request to surface an expired session")
        } catch {
            XCTAssertEqual(error as? GatewayRESTError, .expiredSession)
        }

        let snapshot = fixture.snapshot
        XCTAssertEqual(snapshot.refreshCount, 1)
        XCTAssertEqual(snapshot.ticketCount, 2)
        XCTAssertEqual(snapshot.ticketAuthorizationHeaders, ["Bearer old-access", "Bearer new-access"])
        let persisted = await persistence.values
        XCTAssertEqual(persisted.count, 1)
    }

    private func makeSession(fixture: OAuthURLProtocolFixture) -> URLSession {
        OAuthTestURLProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OAuthTestURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private actor PersistenceRecorder {
    private(set) var values: [GatewayCredentials] = []

    func record(_ credentials: GatewayCredentials) {
        values.append(credentials)
    }
}

private enum TicketResponsePolicy: Sendable {
    case acceptRefreshedToken
    case rejectOldToken
    case alwaysUnauthorized
}

private struct OAuthFixtureSnapshot: Sendable {
    let refreshCount: Int
    let ticketCount: Int
    let ticketAuthorizationHeaders: [String?]
}

private final class OAuthURLProtocolFixture: @unchecked Sendable {
    private let lock = NSLock()
    private let ticketPolicy: TicketResponsePolicy
    private var refreshCount = 0
    private var ticketCount = 0
    private var ticketAuthorizationHeaders: [String?] = []

    init(ticketPolicy: TicketResponsePolicy) {
        self.ticketPolicy = ticketPolicy
    }

    var snapshot: OAuthFixtureSnapshot {
        lock.withLock {
            OAuthFixtureSnapshot(
                refreshCount: refreshCount,
                ticketCount: ticketCount,
                ticketAuthorizationHeaders: ticketAuthorizationHeaders
            )
        }
    }

    func response(for request: URLRequest) -> (statusCode: Int, data: Data) {
        if request.url?.path == "/auth/native/refresh" {
            lock.withLock { refreshCount += 1 }
            Thread.sleep(forTimeInterval: 0.1)
            return (
                200,
                Data(
                    "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_at\":4000000000,\"provider\":\"nous\"}".utf8
                )
            )
        }

        let authorization = request.value(forHTTPHeaderField: "Authorization")
        lock.withLock {
            ticketCount += 1
            ticketAuthorizationHeaders.append(authorization)
        }
        let statusCode: Int
        switch ticketPolicy {
        case .acceptRefreshedToken:
            statusCode = authorization == "Bearer new-access" ? 200 : 401
        case .rejectOldToken:
            statusCode = authorization == "Bearer new-access" ? 200 : 401
            if authorization == "Bearer old-access" {
                Thread.sleep(forTimeInterval: 0.05)
            }
        case .alwaysUnauthorized:
            statusCode = 401
        }
        if statusCode == 401 {
            return (statusCode, Data("{\"detail\":\"expired\"}".utf8))
        }
        return (statusCode, Data("{\"ticket\":\"fresh-ticket\"}".utf8))
    }
}

private final class OAuthTestURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var fixture: OAuthURLProtocolFixture?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let fixture = Self.fixture, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: GatewayRESTError.invalidResponse)
            return
        }
        let result = fixture.response(for: request)
        guard let response = HTTPURLResponse(
            url: url,
            statusCode: result.statusCode,
            httpVersion: nil,
            headerFields: nil
        ) else {
            client?.urlProtocol(self, didFailWithError: GatewayRESTError.invalidResponse)
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: result.data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
