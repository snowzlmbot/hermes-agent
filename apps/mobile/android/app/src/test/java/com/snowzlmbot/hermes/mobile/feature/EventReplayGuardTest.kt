package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReplayGuardTest {
  @Test
  fun retainsKeysAcrossReconnectForTheSameProfile() {
    val guard = EventReplayGuard(capacity = 8)
    val event = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "message-1")
    }

    guard.activate("profile-a")
    assertTrue(guard.accept("profile-a", "stored-1", event))
    guard.activate("profile-a")

    assertFalse(guard.accept("profile-a", "stored-1", event))
  }

  @Test
  fun profileSwitchClearsPreviousProfileKeys() {
    val guard = EventReplayGuard(capacity = 8)
    val event = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "message-1")
    }

    guard.activate("profile-a")
    assertTrue(guard.accept("profile-a", "stored-1", event))
    guard.activate("profile-b")
    assertTrue(guard.accept("profile-b", "stored-1", event))
    assertFalse(guard.accept("profile-a", "stored-1", event))
    guard.activate("profile-a")

    assertTrue(guard.accept("profile-a", "stored-1", event))
  }

  @Test
  fun clearDropsKeysForForgetOrLogout() {
    val guard = EventReplayGuard(capacity = 8)
    val event = event("approval.request", GatewayEventType.APPROVAL_REQUEST) {
      put("request_id", "request-1")
    }

    assertTrue(guard.accept("profile-a", "stored-1", event))
    assertFalse(guard.accept("profile-a", "stored-1", event))
    guard.clear()

    assertTrue(guard.accept("profile-a", "stored-1", event))
  }

  @Test
  fun evictsLeastRecentlyUsedKeyAtCapacityAndRefreshesDuplicateRecency() {
    val guard = EventReplayGuard(capacity = 2)
    val first = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "message-1")
    }
    val second = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "message-2")
    }
    val third = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "message-3")
    }

    assertTrue(guard.accept("profile-a", "stored-1", first))
    assertTrue(guard.accept("profile-a", "stored-1", second))
    assertFalse(guard.accept("profile-a", "stored-1", first))
    assertTrue(guard.accept("profile-a", "stored-1", third))
    assertFalse(guard.accept("profile-a", "stored-1", first))
    assertTrue(guard.accept("profile-a", "stored-1", second))
  }

  @Test
  fun usesFrameIdentityBeforeSingleOccurrenceFallback() {
    val guard = EventReplayGuard(capacity = 8)
    val first = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("event_id", "frame-1")
      put("message_id", "message-1")
      put("text", "safe text")
    }
    val replay = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("event_id", "frame-1")
      put("message_id", "message-2")
      put("text", "different text")
    }

    assertTrue(guard.accept("profile-a", "stored-1", first))
    assertFalse(guard.accept("profile-a", "stored-1", replay))
  }

  @Test
  fun supportsIntegerSequenceForStreamingFrames() {
    val guard = EventReplayGuard(capacity = 8)
    val first = event("tool.progress", GatewayEventType.TOOL_PROGRESS) {
      put("sequence", 7)
      put("preview", "first")
    }
    val replay = event("tool.progress", GatewayEventType.TOOL_PROGRESS) {
      put("sequence", 7)
      put("preview", "changed")
    }

    assertTrue(guard.accept("profile-a", "stored-1", first))
    assertFalse(guard.accept("profile-a", "stored-1", replay))
  }

  @Test
  fun processesStreamingFramesWithoutPerFrameIdentityEvenWhenEntityIdRepeats() {
    val guard = EventReplayGuard(capacity = 8)
    val first = event("message.delta", GatewayEventType.MESSAGE_DELTA) {
      put("message_id", "message-1")
      put("text", "A")
    }
    val second = event("message.delta", GatewayEventType.MESSAGE_DELTA) {
      put("message_id", "message-1")
      put("text", "B")
    }

    assertTrue(guard.accept("profile-a", "stored-1", first))
    assertTrue(guard.accept("profile-a", "stored-1", second))
  }

  @Test
  fun supportsEventIdAndCamelCaseEventIdForStreamingFrames() {
    val guard = EventReplayGuard(capacity = 8)
    val snake = event("message.delta", GatewayEventType.MESSAGE_DELTA) {
      put("event_id", "frame-1")
      put("text", "A")
    }
    val camel = event("message.delta", GatewayEventType.MESSAGE_DELTA) {
      put("eventId", "frame-2")
      put("text", "B")
    }

    assertTrue(guard.accept("profile-a", "stored-1", snake))
    assertFalse(guard.accept("profile-a", "stored-1", snake))
    assertTrue(guard.accept("profile-a", "stored-1", camel))
    assertFalse(guard.accept("profile-a", "stored-1", camel))
  }

  @Test
  fun doesNotDeduplicateDifferentStoredSessionsOrWireTypes() {
    val guard = EventReplayGuard(capacity = 8)
    val messageComplete = event("message.complete", GatewayEventType.MESSAGE_COMPLETE) {
      put("message_id", "entity-1")
    }
    val messageStart = event("message.start", GatewayEventType.MESSAGE_START) {
      put("message_id", "entity-1")
    }

    assertTrue(guard.accept("profile-a", "stored-1", messageComplete))
    assertTrue(guard.accept("profile-a", "stored-2", messageComplete))
    assertTrue(guard.accept("profile-a", "stored-1", messageStart))
  }

  private fun event(
    wireType: String,
    type: GatewayEventType,
    payload: JsonObjectBuilder.() -> Unit,
  ): GatewayEvent = GatewayEvent(
    type = type,
    wireType = wireType,
    runtimeSessionId = "runtime-1",
    payload = buildJsonObject(payload),
  )

  private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder
}
