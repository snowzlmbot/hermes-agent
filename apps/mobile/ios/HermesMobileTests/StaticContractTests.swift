import XCTest
@testable import HermesMobile

final class StaticContractTests: XCTestCase {
    func testAllRequiredRPCMethodsAndEventsAreRepresented() {
        XCTAssertTrue(Set(GatewayMethod.all).isSuperset(of: [
            GatewayMethod.sessionList,
            GatewayMethod.sessionCreate,
            GatewayMethod.sessionResume,
            GatewayMethod.sessionTitle,
            GatewayMethod.sessionDelete,
            GatewayMethod.sessionInterrupt,
            GatewayMethod.promptSubmit,
            GatewayMethod.modelOptions,
            GatewayMethod.configSet,
            GatewayMethod.approvalRespond,
            GatewayMethod.clarifyRespond,
            GatewayMethod.secretRespond,
            GatewayMethod.sudoRespond,
            GatewayMethod.imageAttachBytes,
            GatewayMethod.pdfAttach,
            GatewayMethod.fileAttach
        ]))
        XCTAssertTrue(Set(GatewayEventType.all).isSuperset(of: [
            .gatewayReady, .sessionInfo, .messageStart, .messageDelta,
            .messageInterim, .messageComplete, .reasoningDelta, .toolStart,
            .toolProgress, .toolComplete, .approvalRequest, .clarifyRequest,
            .clarifyExpire, .secretRequest, .secretExpire, .sudoRequest,
            .sudoExpire, .error, .sessionsChanged
        ]))
    }

    func testContractFixtureIsBundledForRuntimeCapabilityChecks() throws {
        let contract = try MobileContract.loadBundled()

        XCTAssertGreaterThanOrEqual(contract.schemaVersion, 1)
        XCTAssertTrue(contract.rpcMethods.contains(GatewayMethod.sessionCreate))
        XCTAssertTrue(contract.eventTypes.contains(.messageDelta))
        XCTAssertEqual(contract.requestExample["jsonrpc"], .string("2.0"))
        XCTAssertEqual(contract.eventExample["method"], .string("event"))
        XCTAssertEqual(contract.notificationRouting.identityField, "stored_session_id")
        XCTAssertEqual(Set(contract.notificationRouting.allowedFields), ["stored_session_id", "profile_scope"])
        XCTAssertEqual(contract.notificationRouting.profileScopeEncoding, "sha256_hex_lowercase")
        XCTAssertEqual(contract.notificationRouting.profileScopeInput, "session_selection_scope")
        XCTAssertEqual(contract.notificationRouting.scopeMismatchAction, "discard")
        XCTAssertTrue(contract.notificationRouting.singleConsume)
        XCTAssertTrue(contract.notificationRouting.forbiddenFields.contains("session_id"))
    }
}
