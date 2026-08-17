package com.snowzlmbot.hermes.mobile.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewaySocketClientTest {
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
  fun connectsWithFreshCredentialAndCorrelatesResponseWhilePublishingEvents() = runBlocking {
    server.enqueue(
      MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
          webSocket.send(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"change_events":true}}}""",
          )
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
          val request = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
          webSocket.send(
            """{"jsonrpc":"2.0","id":"${request.string("id")}","result":{"sessions":[]}}""",
          )
          webSocket.send(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","payload":{}}}""",
          )
        }

        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
          webSocket.close(code, reason)
        }
      }),
    )
    var credentialCalls = 0
    val socket = GatewaySocketClient(
      endpoint = GatewayEndpoint.parse(server.url("/proxy/").toString()),
      credentialProvider = {
        credentialCalls += 1
        GatewayCredential.Ticket(SecretValue("ticket-$credentialCalls"))
      },
      httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    try {
      withTimeout(5_000) {
        val ready = async(start = CoroutineStart.UNDISPATCHED) { socket.events.first { it.type == GatewayEventType.GATEWAY_READY } }
    socket.connect()
    assertEquals(GatewayEventType.GATEWAY_READY, ready.await().type)
    val changed = async(start = CoroutineStart.UNDISPATCHED) { socket.events.first { it.type == GatewayEventType.SESSIONS_CHANGED } }
    val result = socket.request("session.list", buildJsonObject { put("limit", 20) })

    assertEquals(0, result["sessions"]?.jsonArray?.size)
    assertEquals(GatewayEventType.SESSIONS_CHANGED, changed.await().type)
    assertEquals(1, credentialCalls)
        assertEquals("/proxy/api/ws?ticket=ticket-1", server.takeRequest(5, TimeUnit.SECONDS)?.path)
      }
    } finally {
      socket.close()
    }
  }

  @Test
  fun eachReconnectMintsAnotherSingleUseTicket() = runBlocking {
    repeat(2) {
      server.enqueue(
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
          override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
            webSocket.send(
              """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""",
            )
          }

          override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
          }
        }),
      )
    }
    var calls = 0
    val socket = GatewaySocketClient(
      endpoint = GatewayEndpoint.parse(server.url("/").toString()),
      credentialProvider = {
        calls += 1
        GatewayCredential.Ticket(SecretValue("fresh-$calls"))
      },
    )

    socket.connect()
    socket.disconnect()
    socket.connect()

    assertEquals(2, calls)
    assertTrue(server.takeRequest().path.orEmpty().endsWith("ticket=fresh-1"))
    assertTrue(server.takeRequest().path.orEmpty().endsWith("ticket=fresh-2"))
    socket.close()
  }

  @Test
  fun concurrentConnectsShareOneDialAndSingleUseTicket() = runBlocking {
    server.enqueue(
      MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
          webSocket.send(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""",
          )
        }

        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
          webSocket.close(code, reason)
        }
      }),
    )
    var credentialCalls = 0
    val socket = GatewaySocketClient(
      endpoint = GatewayEndpoint.parse(server.url("/").toString()),
      credentialProvider = {
        credentialCalls += 1
        delay(50)
        GatewayCredential.Ticket(SecretValue("shared-ticket"))
      },
    )

    val first = async { socket.connect() }
    val second = async { socket.connect() }
    first.await()
    second.await()

    assertEquals(1, credentialCalls)
    assertEquals("/api/ws?ticket=shared-ticket", server.takeRequest().path)
    socket.close()
  }
}
