package com.snowzlmbot.hermes.mobile.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class SocketState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  FAILED,
}

class GatewayRpcException(
  val code: Int,
  message: String,
) : IllegalStateException(message)

interface JsonObjectRpcClient {
  val events: SharedFlow<GatewayEvent>

  suspend fun connect()
  suspend fun request(method: String, params: JsonObject = buildJsonObject {}): JsonObject
  fun close()
}

class GatewaySocketClient(
  private val endpoint: GatewayEndpoint,
  private val credentialProvider: suspend () -> GatewayCredential?,
  private val httpClient: OkHttpClient = OkHttpClient(),
  private val requestTimeoutMillis: Long = 60_000,
) : JsonObjectRpcClient {
  private val ids = AtomicLong(0)
  private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
  private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)
  private val mutableState = MutableStateFlow(SocketState.DISCONNECTED)
  private val socketLock = Any()
  private val connectMutex = Mutex()

  @Volatile
  private var socket: WebSocket? = null

  override val events: SharedFlow<GatewayEvent> = mutableEvents
  val state: StateFlow<SocketState> = mutableState

  override suspend fun connect() {
    connectMutex.withLock {
      synchronized(socketLock) {
        if (socket != null && mutableState.value == SocketState.CONNECTED) return@withLock
        mutableState.value = SocketState.CONNECTING
      }
      val credential = credentialProvider()
      val url = endpoint.webSocketUrl(credential).toString()
      val opened = CompletableDeferred<Unit>()
      val candidate = httpClient.newWebSocket(
        Request.Builder().url(url).build(),
        listener(opened),
      )
      synchronized(socketLock) { socket = candidate }
      try {
        withTimeout(15_000) { opened.await() }
      } catch (error: Throwable) {
        candidate.cancel()
        synchronized(socketLock) {
          if (socket === candidate) socket = null
        }
        mutableState.value = SocketState.FAILED
        throw error
      }
    }
  }

  override suspend fun request(
    method: String,
    params: JsonObject,
  ): JsonObject {
    if (mutableState.value != SocketState.CONNECTED) connect()
    val id = "mobile-${ids.incrementAndGet()}"
    val result = CompletableDeferred<JsonObject>()
    pending[id] = result
    val sent = socket?.send(JsonRpcCodec.encodeRequest(id, method, params)) == true
    if (!sent) {
      pending.remove(id)
      throw IllegalStateException("Gateway WebSocket is not connected")
    }
    return try {
      withTimeout(requestTimeoutMillis) { result.await() }
    } finally {
      pending.remove(id)
    }
  }

  fun disconnect() {
    val current = synchronized(socketLock) {
      socket.also { socket = null }
    }
    current?.close(1000, "client disconnect")
    mutableState.value = SocketState.DISCONNECTED
    failPending(IllegalStateException("Gateway WebSocket disconnected"))
  }

  override fun close() {
    disconnect()
    httpClient.dispatcher.cancelAll()
  }

  private fun listener(opened: CompletableDeferred<Unit>): WebSocketListener =
    object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        mutableState.value = SocketState.CONNECTED
        opened.complete(Unit)
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        when (val frame = runCatching { JsonRpcCodec.decode(text) }.getOrNull()) {
          is JsonRpcFrame.Success -> pending.remove(frame.id)?.complete(frame.result)
          is JsonRpcFrame.Failure -> frame.id?.let { id ->
            pending.remove(id)?.completeExceptionally(GatewayRpcException(frame.code, frame.message))
          }
          is JsonRpcFrame.Event -> mutableEvents.tryEmit(frame.event)
          null -> Unit
        }
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        markDisconnected(webSocket, IllegalStateException("Gateway WebSocket closed"))
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!opened.isCompleted) opened.completeExceptionally(
          IllegalStateException("Could not connect to Hermes gateway", t),
        )
        markDisconnected(webSocket, IllegalStateException("Gateway WebSocket failed", t))
      }
    }

  private fun markDisconnected(webSocket: WebSocket, error: Throwable) {
    synchronized(socketLock) {
      if (socket === webSocket) socket = null
    }
    mutableState.value = SocketState.DISCONNECTED
    failPending(error)
  }

  private fun failPending(error: Throwable) {
    pending.values.forEach { it.completeExceptionally(error) }
    pending.clear()
  }
}

fun reconnectBackoffMillis(
  attempt: Int,
  randomFraction: Double,
  baseMillis: Long = 300,
  capMillis: Long = 15_000,
): Long {
  val safeAttempt = attempt.coerceAtLeast(0).coerceAtMost(30)
  val ceiling = (baseMillis * (1L shl safeAttempt)).coerceAtMost(capMillis)
  return (randomFraction.coerceIn(0.0, 1.0) * ceiling).toLong()
}