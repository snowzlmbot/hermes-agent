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

    func testParsesAuthenticatedModelCatalogAndPerModelCapabilities() throws {
        let result: JSONValue = .object([
            "model": .string("hermes-4"),
            "provider": .string("nous"),
            "providers": .array([
                .object([
                    "slug": .string("nous"),
                    "name": .string("Nous"),
                    "authenticated": .bool(true),
                    "models": .array([.string("hermes-4"), .string("hermes-4-fast")]),
                    "capabilities": .object([
                        "hermes-4": .object(["fast": .bool(false), "reasoning": .bool(true)]),
                        "hermes-4-fast": .object(["fast": .bool(true), "reasoning": .bool(true)])
                    ])
                ]),
                .object([
                    "slug": .string("anthropic"),
                    "name": .string("Anthropic"),
                    "authenticated": .bool(false),
                    "models": .array([.string("claude-sonnet")])
                ])
            ])
        ])

        let catalog = GatewayProtocol.parseModelOptions(result: result)

        XCTAssertEqual(catalog.currentModel, "hermes-4")
        XCTAssertEqual(catalog.currentProvider, "nous")
        XCTAssertEqual(catalog.providers.map(\.id), ["nous"])
        XCTAssertEqual(catalog.providers.single?.models.map(\.id), ["hermes-4", "hermes-4-fast"])
        XCTAssertEqual(catalog.providers.single?.models.first?.supportsReasoning, true)
        XCTAssertEqual(catalog.providers.single?.models.first?.supportsFast, false)
        XCTAssertEqual(catalog.providers.single?.models.last?.supportsFast, true)
    }
}

private extension Collection {
    var single: Element? { count == 1 ? first : nil }
}