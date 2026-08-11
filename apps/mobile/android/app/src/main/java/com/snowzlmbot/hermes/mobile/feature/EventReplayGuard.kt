package com.snowzlmbot.hermes.mobile.feature

import com.snowzlmbot.hermes.mobile.core.GatewayEvent
import com.snowzlmbot.hermes.mobile.core.GatewayEventType
import kotlinx.serialization.json.JsonPrimitive

internal class EventReplayGuard(private val capacity: Int = 1024) {
  init {
    require(capacity > 0) { "Replay capacity must be positive" }
  }

  private data class Key(
    val storedSessionId: String,
    val eventType: String,
    val stableIdentity: String,
  )

  private var activeProfileScope: String? = null
  private val seen = LinkedHashMap<Key, Unit>(capacity, 0.75f, true)

  @Synchronized
  fun activate(profileScope: String) {
    val normalized = profileScope.trim()
    require(normalized.isNotEmpty()) { "Profile scope must not be empty" }
    if (activeProfileScope != normalized) {
      seen.clear()
      activeProfileScope = normalized
    }
  }

  @Synchronized
  fun accept(profileScope: String, storedSessionId: String, event: GatewayEvent): Boolean {
    val normalizedProfile = profileScope.trim()
    if (normalizedProfile.isEmpty()) return false
    if (activeProfileScope == null) activeProfileScope = normalizedProfile
    if (activeProfileScope != normalizedProfile) return false
    val normalizedStoredId = storedSessionId.trim()
    if (normalizedStoredId.isEmpty()) return true
    val identity = stableIdentity(event) ?: return true
    val key = Key(normalizedStoredId, event.wireType, identity)
    if (seen[key] != null) return false
    seen[key] = Unit
    while (seen.size > capacity) {
      val eldest = seen.entries.iterator()
      eldest.next()
      eldest.remove()
    }
    return true
  }

  @Synchronized
  fun clear() {
    seen.clear()
    activeProfileScope = null
  }

  private fun stableIdentity(event: GatewayEvent): String? {
    primitiveIdentity(event.payload["event_id"])?.let { return "event_id:$it" }
    primitiveIdentity(event.payload["eventId"])?.let { return "event_id:$it" }
    primitiveIdentity(event.payload["sequence"])?.let { return "sequence:$it" }
    return fallbackFields(event.type)
      .firstNotNullOfOrNull { field -> primitiveIdentity(event.payload[field])?.let { "$field:$it" } }
  }

  private fun primitiveIdentity(value: kotlinx.serialization.json.JsonElement?): String? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content.trim().takeIf(String::isNotEmpty)
    return primitive.content.toLongOrNull()?.toString()
  }

  private fun fallbackFields(type: GatewayEventType): List<String> = when (type) {
    GatewayEventType.MESSAGE_START,
    GatewayEventType.MESSAGE_COMPLETE -> listOf("message_id")
    GatewayEventType.TOOL_START,
    GatewayEventType.TOOL_COMPLETE -> listOf("tool_id", "tool_call_id")
    GatewayEventType.APPROVAL_REQUEST,
    GatewayEventType.CLARIFY_REQUEST,
    GatewayEventType.CLARIFY_EXPIRE,
    GatewayEventType.SECRET_REQUEST,
    GatewayEventType.SECRET_EXPIRE,
    GatewayEventType.SUDO_REQUEST,
    GatewayEventType.SUDO_EXPIRE -> listOf("request_id", "id")
    else -> emptyList()
  }
}
