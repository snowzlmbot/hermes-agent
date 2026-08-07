import XCTest
@testable import HermesMobile

final class JSONRPCModelsTests: XCTestCase {
    func testDecodesStringAndNumericResponseIdentifiers() throws {
        let decoder = JSONDecoder()
        let stringResponse = try decoder.decode(
            JSONRPCResponse.self,
            from: Data(#"{"jsonrpc":"2.0","id":"mobile-1","result":{"ok":true}}"#.utf8)
        )
        let numericResponse = try decoder.decode(
            JSONRPCResponse.self,
            from: Data(#"{"jsonrpc":"2.0","id":7,"result":{"ok":true}}"#.utf8)
        )

        XCTAssertEqual(stringResponse.id, .string("mobile-1"))
        XCTAssertEqual(numericResponse.id, .number(7))
        XCTAssertEqual(stringResponse.result?.object?["ok"], .bool(true))
    }

    func testEncodesRequestWithJSONRPCVersionAndParameters() throws {
        let request = JSONRPCRequest(
            id: .string("mobile-2"),
            method: GatewayMethod.sessionCreate,
            params: ["source": .string("mobile"), "cols": .number(80)]
        )
        let data = try JSONEncoder().encode(request)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["jsonrpc"] as? String, "2.0")
        XCTAssertEqual(object["method"] as? String, "session.create")
        XCTAssertEqual((object["params"] as? [String: Any])?["source"] as? String, "mobile")
    }

    func testDecodesUnknownEventWithoutDroppingIt() throws {
        let frame = try JSONDecoder().decode(
            JSONRPCInboundFrame.self,
            from: Data(#"{"jsonrpc":"2.0","method":"event","params":{"type":"future.event","session_id":"runtime-1","payload":{"text":"hi"}}}"#.utf8)
        )

        guard case .event(let event) = frame else {
            return XCTFail("expected event frame")
        }
        XCTAssertEqual(event.type, .unknown("future.event"))
        XCTAssertEqual(event.sessionID, "runtime-1")
        XCTAssertEqual(event.payload?.object?["text"], .string("hi"))
    }

    func testDecodesStructuredRPCError() throws {
        let frame = try JSONDecoder().decode(
            JSONRPCInboundFrame.self,
            from: Data(#"{"jsonrpc":"2.0","id":"mobile-9","error":{"code":4007,"message":"session not found"}}"#.utf8)
        )

        guard case .response(let response) = frame else {
            return XCTFail("expected response frame")
        }
        XCTAssertEqual(response.error, JSONRPCError(code: 4007, message: "session not found"))
    }
}
