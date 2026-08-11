package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import com.snowzlmbot.hermes.mobile.core.AndroidCredentialStore
import com.snowzlmbot.hermes.mobile.core.AndroidProfileStore
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository
import com.snowzlmbot.hermes.mobile.platform.LocalNotificationService

class HermesApplication : Application() {
  internal lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    LocalNotificationService(this).initializeChannel()
    graph = AppGraph(
      GatewayProfileRepository(
        profileStore = AndroidProfileStore(this),
        credentialStore = AndroidCredentialStore(this),
      ),
    )
  }
}
