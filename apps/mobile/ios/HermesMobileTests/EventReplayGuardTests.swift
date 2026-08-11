import XCTest
@testable import HermesMobile

@MainActor
final class EventReplayGuardTests: XCTestCase {
    func testDuplicateHitRefreshesRecentnessBeforeCapacityEviction() {
        let guardStore = EventReplayGuard(capacity: 2)
        guardStore.activate(profileScope: "profile-a")

        XCTAssertTrue(guardStore.shouldConsume(event: event(id: "event-1"), storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertTrue(guardStore.shouldConsume(event: event(id: "event-2"), storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertFalse(guardStore.shouldConsume(event: event(id: "event-1"), storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertTrue(guardStore.shouldConsume(event: event(id: "event-3"), storedSessionID: "stored-1", profileScope: "profile-a"))

        XCTAssertFalse(guardStore.shouldConsume(event: event(id: "event-1"), storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertTrue(guardStore.shouldConsume(event: event(id: "event-2"), storedSessionID: "stored-1", profileScope: "profile-a"))
    }

    func testProfileSwitchAndClearForgetPriorIdentities() {
        let guardStore = EventReplayGuard(capacity: 8)
        let replay = event(id: "event-1")

        guardStore.activate(profileScope: "profile-a")
        XCTAssertTrue(guardStore.shouldConsume(event: replay, storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertFalse(guardStore.shouldConsume(event: replay, storedSessionID: "stored-1", profileScope: "profile-a"))

        guardStore.activate(profileScope: "profile-b")
        XCTAssertTrue(guardStore.shouldConsume(event: replay, storedSessionID: "stored-1", profileScope: "profile-b"))
        XCTAssertFalse(guardStore.shouldConsume(event: replay, storedSessionID: "stored-1", profileScope: "profile-a"))

        guardStore.clear()
        guardStore.activate(profileScope: "profile-b")
        XCTAssertTrue(guardStore.shouldConsume(event: replay, storedSessionID: "stored-1", profileScope: "profile-b"))
    }

    func testLifecycleFallbacksDoNotUseContentAndStreamingRequiresFrameIdentity() {
        let guardStore = EventReplayGuard(capacity: 8)
        guardStore.activate(profileScope: "profile-a")

        let firstCompletion = GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("first")])
        )
        let replayedCompletion = GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("changed")])
        )
        XCTAssertTrue(guardStore.shouldConsume(event: firstCompletion, storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertFalse(guardStore.shouldConsume(event: replayedCompletion, storedSessionID: "stored-1", profileScope: "profile-a"))

        let firstDelta = GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("a")])
        )
        let secondDelta = GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("b")])
        )
        XCTAssertTrue(guardStore.shouldConsume(event: firstDelta, storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertTrue(guardStore.shouldConsume(event: secondDelta, storedSessionID: "stored-1", profileScope: "profile-a"))

        let framedDelta = GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object(["event_id": .string("delta-event"), "message_id": .string("message-1"), "text": .string("c")])
        )
        XCTAssertTrue(guardStore.shouldConsume(event: framedDelta, storedSessionID: "stored-1", profileScope: "profile-a"))
        XCTAssertFalse(guardStore.shouldConsume(event: framedDelta, storedSessionID: "stored-1", profileScope: "profile-a"))
    }

    private func event(id: String) -> GatewayEvent {
        GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-1",
            payload: .object(["event_id": .string(id), "content": .string("must-not-enter-the-key")])
        )
    }
}