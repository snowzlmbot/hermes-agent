import XCTest
@testable import HermesMobile

final class NotificationContentTests: XCTestCase {
    private let scope = String(repeating: "a", count: 64)

    private func route(_ id: String = "stored-1") -> NotificationRoute {
        NotificationRoute(storedSessionID: id, profileScope: scope)
    }

    func testProfileScopeHashIsDeterministicLowercaseHex() {
        let first = NotificationRouteMetadata.profileScope(for: "default|https://gateway.example/")
        let second = NotificationRouteMetadata.profileScope(for: "default|https://gateway.example/")
        let other = NotificationRouteMetadata.profileScope(for: "default|https://other.example/")

        XCTAssertEqual(first, second)
        XCTAssertNotEqual(first, other)
        XCTAssertEqual(first.count, 64)
        XCTAssertEqual(first, first.lowercased())
        XCTAssertNotNil(NotificationRouteMetadata.normalizedProfileScope(first))
    }

    func testCompletionContainsOnlyStoredIDAndHashedScope() {
        let content = NotificationContentFactory.completion(
            sessionTitle: "Release checklist",
            route: route()
        )

        XCTAssertEqual(content.title, "Hermes")
        XCTAssertEqual(content.body, "Release checklist is ready")
        XCTAssertEqual(content.categoryIdentifier, NotificationCategory.completion)
        XCTAssertEqual(content.userInfo as? [String: String], [
            NotificationRouteMetadata.storedSessionIDKey: "stored-1",
            NotificationRouteMetadata.profileScopeKey: scope
        ])
        XCTAssertNil(content.userInfo["session_id"])
        XCTAssertNil(content.userInfo["response"])
        XCTAssertNil(content.userInfo["token"])
    }

    func testApprovalAndInputContainNoSensitivePayload() {
        let approval = NotificationContentFactory.approval(route: route())
        let input = NotificationContentFactory.input(route: route())

        XCTAssertEqual(approval.userInfo.count, 2)
        XCTAssertEqual(input.userInfo.count, 2)
        for content in [approval, input] {
            XCTAssertEqual(content.userInfo[NotificationRouteMetadata.storedSessionIDKey] as? String, "stored-1")
            XCTAssertEqual(content.userInfo[NotificationRouteMetadata.profileScopeKey] as? String, scope)
            XCTAssertNil(content.userInfo["command"])
            XCTAssertNil(content.userInfo["prompt"])
            XCTAssertNil(content.userInfo["value"])
            XCTAssertNil(content.userInfo["ticket"])
        }
    }

    func testDeliveryPolicyRequiresBackgroundAuthorizationAndCompleteRoute() {
        XCTAssertTrue(NotificationDeliveryPolicy.shouldSchedule(
            isSceneActive: false,
            isAuthorized: true,
            route: route()
        ))
        XCTAssertFalse(NotificationDeliveryPolicy.shouldSchedule(
            isSceneActive: true,
            isAuthorized: true,
            route: route()
        ))
        XCTAssertFalse(NotificationDeliveryPolicy.shouldSchedule(
            isSceneActive: false,
            isAuthorized: false,
            route: route()
        ))
        XCTAssertFalse(NotificationDeliveryPolicy.shouldSchedule(
            isSceneActive: false,
            isAuthorized: true,
            route: nil
        ))
    }

    func testParserRequiresExactlyStoredIDAndScope() {
        var metadata: [AnyHashable: Any] = [
            NotificationRouteMetadata.storedSessionIDKey: "stored-1",
            NotificationRouteMetadata.profileScopeKey: scope
        ]
        XCTAssertEqual(
            NotificationRouteParser.route(
                categoryIdentifier: NotificationCategory.completion,
                userInfo: metadata
            ),
            route()
        )

        metadata["token"] = "forbidden"
        XCTAssertNil(NotificationRouteParser.route(
            categoryIdentifier: NotificationCategory.completion,
            userInfo: metadata
        ))
        XCTAssertNil(NotificationRouteParser.route(
            categoryIdentifier: NotificationCategory.input,
            userInfo: [NotificationRouteMetadata.storedSessionIDKey: "stored-1"]
        ))
        XCTAssertNil(NotificationRouteParser.route(
            categoryIdentifier: NotificationCategory.input,
            userInfo: [
                NotificationRouteMetadata.storedSessionIDKey: "stored-1",
                NotificationRouteMetadata.profileScopeKey: scope.uppercased()
            ]
        ))
    }

    @MainActor
    func testRouterQueuesColdTapAndConsumesItExactlyOnce() async {
        let router = NotificationResponseRouter()
        var routed: [NotificationRoute] = []
        let cold = route("stored-cold")

        await router.receive(route: cold)
        await router.install { routed.append($0) }
        await router.install { routed.append($0) }

        XCTAssertEqual(routed, [cold])
    }

    @MainActor
    func testRouterForwardsHotTapImmediately() async {
        let router = NotificationResponseRouter()
        var routed: [NotificationRoute] = []
        let hot = route("stored-hot")
        await router.install { routed.append($0) }
        await router.receive(route: hot)
        XCTAssertEqual(routed, [hot])
    }

    @MainActor
    func testDelegateAlwaysCompletesInvalidResponse() {
        let delegate = NotificationAppDelegate()
        var completionCalls = 0
        delegate.handleNotificationResponse(
            categoryIdentifier: NotificationCategory.completion,
            userInfo: ["session_id": "runtime-only"]
        ) {
            completionCalls += 1
        }
        XCTAssertEqual(completionCalls, 1)
    }
}
