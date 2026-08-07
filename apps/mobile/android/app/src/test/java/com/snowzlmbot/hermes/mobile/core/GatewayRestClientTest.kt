package com.snowzlmbot.hermes.mobile.core

import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayRestClientTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun statusUsesPublicEndpointAndParsesAuthCapabilities() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"gateway_running":true,"auth_required":false,"auth_flows":[]}""",
      ),
    )
    val client = GatewayRestClient(GatewayEndpoint.parse(server.url("/proxy/").toString()))

    val status = client.status()

    assertTrue(status.gatewayRunning)
    assertEquals(GatewayLoginStrategy.STATIC_TOKEN, status.loginStrategy)
    val request = server.takeRequest()
    assertEquals("/proxy/api/status", request.path)
    assertFalse(request.headers.names().contains("Authorization"))
  }

  @Test
  fun ticketAndAudioCallsSendOnlyRequiredRedactedCredentialHeaders() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ticket":"one-use-ticket"}"""))
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"ok":true,"transcript":"hello","provider":"fixture"}""",
      ),
    )
    val client = GatewayRestClient(
      endpoint = GatewayEndpoint.parse(server.url("/").toString()),
      credential = RestCredential.StaticToken(SecretValue("private-token")),
    )

    assertEquals(SecretValue("one-use-ticket"), client.mintWebSocketTicket())
    assertEquals("hello", client.transcribeAudio("data:audio/wav;base64,AA==", "audio/wav").transcript)

    val ticketRequest = server.takeRequest()
    assertEquals("Bearer private-token", ticketRequest.getHeader("Authorization"))
    assertEquals("private-token", ticketRequest.getHeader("X-Hermes-Session-Token"))
    assertFalse(ticketRequest.path.orEmpty().contains("private-token"))
    val audioRequest = server.takeRequest()
    assertEquals("POST", audioRequest.method)
    assertTrue(audioRequest.body.readUtf8().contains("data_url"))
    assertFalse(audioRequest.body.readUtf8().contains("private-token"))
  }

  @Test
  fun exchangesAndRefreshesNativeTokenResponseWithoutLoggingSecrets() = runTest {
    val access = "access-token"
    val refresh = "refresh-token"
    val encoded = Base64.getEncoder().encodeToString("audio".toByteArray())
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"$access","refresh_token":"$refresh","expires_at":4102444800,"provider":"nous","user_id":"u1"}""",
      ),
    )
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"access_token":"new-access","refresh_token":"new-refresh","expires_at":4102444801,"provider":"nous","user_id":"u1"}""",
      ),
    )
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"ok":true,"data_url":"data:audio/wav;base64,$encoded","mime_type":"audio/wav"}""",
      ),
    )
    val client = GatewayRestClient(GatewayEndpoint.parse(server.url("/").toString()))

    val exchanged = client.exchangeNativeCode("gateway-code", "verifier")
    val refreshed = client.refreshNativeToken(exchanged.refreshToken, exchanged.provider)
    val spoken = client.speakText("Read this")

    assertEquals(SecretValue(access), exchanged.accessToken)
    assertEquals(SecretValue("new-access"), refreshed.accessToken)
    assertEquals("audio/wav", spoken.mimeType)
    assertTrue(spoken.dataUrl.startsWith("data:audio/wav;base64,"))
    val exchangeRequest = server.takeRequest()
    assertEquals("/auth/native/token", exchangeRequest.path)
    assertFalse(exchangeRequest.body.readUtf8().contains(access))
    val refreshRequest = server.takeRequest()
    assertEquals("/auth/native/refresh", refreshRequest.path)
    assertTrue(refreshRequest.body.readUtf8().contains(refresh))
    val speakRequest = server.takeRequest()
    assertEquals("/api/audio/speak", speakRequest.path)
    assertFalse(speakRequest.body.readUtf8().contains(access))
  }

  @Test
  fun sessionMutationsUseDurableRestIdentityAndBearerAuth() = runTest {
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"sessions":[{"id":"stored-1","title":"Before","preview":"","started_at":1,"message_count":1,"source":"mobile","archived":false}],"total":1,"limit":20,"offset":0}""",
      ),
    )
    server.enqueue(
      MockResponse().setResponseCode(200).setBody(
        """{"ok":true,"title":"After","archived":true}""",
      ),
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
    val client = GatewayRestClient(
      endpoint = GatewayEndpoint.parse(server.url("/proxy/").toString()),
      credential = RestCredential.OAuthBearer(SecretValue("access-private")),
    )

    val sessions = client.listSessions(includeArchived = true)
    val updated = client.updateSession("stored-1", title = "After", archived = true)
    client.deleteSession("stored-1")

    assertEquals("stored-1", sessions.single().storedId)
    assertEquals("After", updated.title)
    assertTrue(updated.archived)

    val list = server.takeRequest()
    assertEquals("GET", list.method)
    assertEquals("/proxy/api/sessions?order=recent&archived=include", list.path)
    assertEquals("Bearer access-private", list.getHeader("Authorization"))
    val patch = server.takeRequest()
    assertEquals("PATCH", patch.method)
    assertEquals("/proxy/api/sessions/stored-1", patch.path)
    assertTrue(patch.body.readUtf8().contains("\"archived\":true"))
    val delete = server.takeRequest()
    assertEquals("DELETE", delete.method)
    assertEquals("/proxy/api/sessions/stored-1", delete.path)
  }
}