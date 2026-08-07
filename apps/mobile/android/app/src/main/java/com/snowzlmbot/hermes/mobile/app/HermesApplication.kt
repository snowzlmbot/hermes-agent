package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import com.snowzlmbot.hermes.mobile.core.AndroidCredentialStore
import com.snowzlmbot.hermes.mobile.core.AndroidProfileStore
import com.snowzlmbot.hermes.mobile.core.GatewayProfileRepository

class HermesApplication : Application() {
  internal lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(
      GatewayProfileRepository(
        profileStore = AndroidProfileStore(this),
        credentialStore = AndroidCredentialStore(this),
      ),
    )
  }
}
