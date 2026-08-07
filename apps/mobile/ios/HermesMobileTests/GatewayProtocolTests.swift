import XCTest
@testable import HermesMobile

final class GatewayProtocolTests: XCTestCase {
    func testParsesDurableSessionAndRuntimeResumeIdentitiesSeparately() throws {
        let result: JSONValue = .object([
            "session_id": .string("runtime-1"),
            "resumed": .string("stored-1"),
            "messages": .array([
                .object(["id": .number(4), "role": .string("assistant"), "content": .string("Connected")])
            ]),
            "info": .object(["model": .string("fixture-model"), "provider": .string("fixture")])
        ])

        let active = try GatewayProtocol.parseActiveSession(result: result)
        XCTAssertEqual(active.runtimeID, "runtime-1")
        XCTAssertEqual(active.storedID, "stored-1")
        XCTAssertEqual(active.messages.single?.role, .assistant)
        XCTAssertEqual(active.messages.single?.text, "Connected")
    }

    func testUnknownMessageRolesAndPayloadsRemainSafe() throws {
        let messages = GatewayProtocol.parseMessages(
            .array([.object(["role": .string("future"), "text": .string("kept")])])
        )

        XCTAssertEqual(messages.single?.role, .unknown)
        XCTAssertEqual(messages.single?.text, "kept")
    }
}

private extension Collection {
    var single: Element? { count == 1 ? first : nil }
}