package com.snowzlmbot.hermes.mobile.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewaySocketLifecycleTest {
  @Test
  fun closeDuringDelayedCredentialPreventsLateDial() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val socket = GatewaySocketClient(
        endpoint = GatewayEndpoint.parse(server.url("/").toString()),
        credentialProvider = {
          started.complete(Unit)
          release.await()
          null
        },
      )
      val connect = async { runCatching { socket.connect() } }

      started.await()
      socket.close()
      release.complete(Unit)
      connect.await()

      assertEquals(0, server.requestCount)
    } finally {
      server.shutdown()
    }
  }
}
