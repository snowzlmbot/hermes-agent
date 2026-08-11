package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.platform.StoredSessionRoute

internal class StoredSessionRouteQueue {
  private var pending: StoredSessionRoute? = null

  @Synchronized
  fun enqueue(route: StoredSessionRoute) {
    pending = route
  }

  @Synchronized
  fun consume(profileScope: String): StoredSessionRoute? {
    val route = pending ?: return null
    pending = null
    return route.takeIf { it.profileScope == profileScope }
  }

  @Synchronized
  fun clear() {
    pending = null
  }
}
