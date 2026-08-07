import XCTest
@testable import HermesMobile

final class NotificationContentTests: XCTestCase {
    func testCompletionNotificationUsesSessionTitleWithoutResponseBody() {
        let content = NotificationContentFactory.completion(sessionTitle: "Release checklist")

        XCTAssertEqual(content.title, "Hermes")
        XCTAssertEqual(content.body, "Release checklist is ready")
        XCTAssertNil(content.userInfo["response"])
    }

    func testApprovalNotificationContainsOnlyRoutingMetadata() {
        let content = NotificationContentFactory.approval(sessionID: "runtime-1")

        XCTAssertEqual(content.categoryIdentifier, NotificationCategory.approval)
        XCTAssertEqual(content.userInfo["session_id"] as? String, "runtime-1")
        XCTAssertNil(content.userInfo["command"])
    }

    func testInputNotificationDoesNotEmbedSecretOrPromptText() {
        let content = NotificationContentFactory.input(sessionID: "runtime-1")

        XCTAssertEqual(content.categoryIdentifier, NotificationCategory.input)
        XCTAssertEqual(content.userInfo["session_id"] as? String, "runtime-1")
        XCTAssertNil(content.userInfo["value"])
        XCTAssertNil(content.userInfo["prompt"])
    }
}
