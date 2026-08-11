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
  private var connectionGeneration: Long = 0
  private var permanentlyClosed = false

  override val events: SharedFlow<GatewayEvent> = mutableEvents
  val state: StateFlow<SocketState> = mutableState

  override suspend fun connect() {
    connectMutex.withLock {
      val generation = synchronized(socketLock) {
        check(!permanentlyClosed) { "Gateway WebSocket client is closed" }
        if (socket != null && mutableState.value == SocketState.CONNECTED) return@withLock
        connectionGeneration += 1
        mutableState.value = SocketState.CONNECTING
        connectionGeneration
      }
      val credential = credentialProvider()
      ensureCurrentGeneration(generation)
      val url = endpoint.webSocketUrl(credential).toString()
      val opened = CompletableDeferred<Unit>()
      val candidate = httpClient.newWebSocket(
        Request.Builder().url(url).build(),
        listener(opened, generation),
      )
      val accepted = synchronized(socketLock) {
        if (permanentlyClosed || connectionGeneration != generation) {
          false
        } else {
          if (socket == null || socket === candidate) socket = candidate
          socket === candidate
        }
      }
      if (!accepted) {
        candidate.cancel()
        throw IllegalStateException("Gateway WebSocket connection was superseded")
      }
      try {
        withTimeout(15_000) { opened.await() }
        ensureCurrentSocket(candidate, generation)
      } catch (error: Throwable) {
        candidate.cancel()
        val current = synchronized(socketLock) {
          if (connectionGeneration == generation && socket === candidate) {
            socket = null
            if (!permanentlyClosed) mutableState.value = SocketState.FAILED
            true
          } else {
            false
          }
        }
        if (current) failPending(error)
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
      connectionGeneration += 1
      socket.also { socket = null }
    }
    current?.close(1000, "client disconnect")
    mutableState.value = SocketState.DISCONNECTED
    failPending(IllegalStateException("Gateway WebSocket disconnected"))
  }

  override fun close() {
    val current = synchronized(socketLock) {
      permanentlyClosed = true
      connectionGeneration += 1
      socket.also { socket = null }
    }
    current?.close(1000, "client close")
    mutableState.value = SocketState.DISCONNECTED
    failPending(IllegalStateException("Gateway WebSocket client closed"))
    httpClient.dispatcher.cancelAll()
  }

  private fun listener(opened: CompletableDeferred<Unit>, generation: Long): WebSocketListener =
    object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        val accepted = synchronized(socketLock) {
          if (permanentlyClosed || connectionGeneration != generation) {
            false
          } else if (socket == null || socket === webSocket) {
            socket = webSocket
            mutableState.value = SocketState.CONNECTED
            true
          } else {
            false
          }
        }
        if (accepted) {
          opened.complete(Unit)
        } else {
          webSocket.cancel()
          opened.completeExceptionally(
            IllegalStateException("Gateway WebSocket connection was superseded"),
          )
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (!isCurrentSocket(webSocket, generation)) return
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
        markDisconnected(webSocket, generation, IllegalStateException("Gateway WebSocket closed"))
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (!opened.isCompleted) opened.completeExceptionally(
          IllegalStateException("Could not connect to Hermes gateway", t),
        )
        markDisconnected(webSocket, generation, IllegalStateException("Gateway WebSocket failed", t))
      }
    }

  private fun markDisconnected(webSocket: WebSocket, generation: Long, error: Throwable) {
    val current = synchronized(socketLock) {
      if (connectionGeneration == generation && socket === webSocket) {
        socket = null
        if (!permanentlyClosed) mutableState.value = SocketState.DISCONNECTED
        true
      } else {
        false
      }
    }
    if (current) failPending(error)
  }

  private fun ensureCurrentGeneration(generation: Long) {
    check(
      synchronized(socketLock) {
        !permanentlyClosed && connectionGeneration == generation
      },
    ) { "Gateway WebSocket connection was superseded" }
  }

  private fun ensureCurrentSocket(candidate: WebSocket, generation: Long) {
    check(isCurrentSocket(candidate, generation)) {
      "Gateway WebSocket connection was superseded"
    }
  }

  private fun isCurrentSocket(candidate: WebSocket, generation: Long): Boolean =
    synchronized(socketLock) {
      !permanentlyClosed && connectionGeneration == generation && socket === candidate
    }
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