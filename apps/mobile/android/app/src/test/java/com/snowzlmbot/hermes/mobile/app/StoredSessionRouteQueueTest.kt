package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.platform.StoredSessionRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredSessionRouteQueueTest {
  private val route = StoredSessionRoute("stored-1", "scope-a")

  @Test
  fun consumesOnceForMatchingScope() {
    val queue = StoredSessionRouteQueue()
    queue.enqueue(route)
    assertEquals(route, queue.consume("scope-a"))
    assertNull(queue.consume("scope-a"))
  }

  @Test
  fun mismatchedScopeIsDiscarded() {
    val queue = StoredSessionRouteQueue()
    queue.enqueue(route)
    assertNull(queue.consume("scope-b"))
    assertNull(queue.consume("scope-a"))
  }

  @Test
  fun clearDropsPendingRoute() {
    val queue = StoredSessionRouteQueue()
    queue.enqueue(route)
    queue.clear()
    assertNull(queue.consume("scope-a"))
  }
}
