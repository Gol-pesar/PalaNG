package com.example.protocol

import android.net.VpnService
import com.example.model.TunnelConfig
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Custom SocketFactory that binds/protects every outbound socket through VpnService.protect()
 * BEFORE connecting, preventing routing loops when the full-device VPN is active.
 */
class ProtectedSocketFactory(
  private val vpnService: VpnService?,
) : SocketFactory() {

  private val delegate = getDefault()

  private fun protect(socket: Socket): Socket {
    vpnService?.protect(socket)
    return socket
  }

  override fun createSocket(): Socket = protect(delegate.createSocket())

  override fun createSocket(host: String?, port: Int): Socket {
    val socket = delegate.createSocket()
    protect(socket)
    socket.connect(java.net.InetSocketAddress(host, port))
    return socket
  }

  override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
    val socket = delegate.createSocket()
    protect(socket)
    socket.bind(java.net.InetSocketAddress(localHost, localPort))
    socket.connect(java.net.InetSocketAddress(host, port))
    return socket
  }

  override fun createSocket(host: InetAddress?, port: Int): Socket {
    val socket = delegate.createSocket()
    protect(socket)
    socket.connect(java.net.InetSocketAddress(host, port))
    return socket
  }

  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int,
  ): Socket {
    val socket = delegate.createSocket()
    protect(socket)
    socket.bind(java.net.InetSocketAddress(localAddress, localPort))
    socket.connect(java.net.InetSocketAddress(address, port))
    return socket
  }
}

class WorkerClientPool(
  private val vpnService: VpnService?,
) {
  fun createClient(workerHost: String, cleanIp: String): OkHttpClient {
    val builder =
      OkHttpClient.Builder()
        .socketFactory(ProtectedSocketFactory(vpnService))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)

    if (cleanIp.isNotBlank()) {
      val normalizedIp = cleanIp.trim().removePrefix("[").removeSuffix("]")
      builder.dns(
        object : Dns {
          override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(workerHost.trim(), ignoreCase = true)) {
              return listOf(InetAddress.getByName(normalizedIp))
            }
            return Dns.SYSTEM.lookup(hostname)
          }
        }
      )
    }
    return builder.build()
  }
}

class WorkerTunnelSession(
  private val config: TunnelConfig,
  private val primaryClient: OkHttpClient,
  private val fallbackClient: OkHttpClient,
) {
  private var webSocket: WebSocket? = null
  private var sessionKey: ByteArray = ByteArray(0)
  private val closed = AtomicBoolean(false)

  interface Listener {
    fun onData(decryptedData: ByteArray)

    fun onClosed(reason: String?)
  }

  suspend fun connect(
    targetHost: String,
    targetPort: Int,
    listener: Listener,
  ): Boolean {
    val cleanIp = config.cleanIp.trim()
    if (cleanIp.isNotEmpty()) {
      if (tryConnectWithClient(primaryClient, targetHost, targetPort, listener)) {
        return true
      }
    }
    return tryConnectWithClient(fallbackClient, targetHost, targetPort, listener)
  }

  private suspend fun tryConnectWithClient(
    client: OkHttpClient,
    targetHost: String,
    targetPort: Int,
    listener: Listener,
  ): Boolean {
    val host = config.workerHost.trim()
    val url = "https://$host/"
    val requestBuilder = Request.Builder().url(url).header("Host", host)
    val subproto = config.subprotocol.trim()
    if (subproto.isNotEmpty()) {
      requestBuilder.header("Sec-WebSocket-Protocol", subproto)
    }

    val initResult = TunnelCrypto.buildInitPacket(config.uuid.trim(), targetHost, targetPort)
    sessionKey = initResult.key

    val openDeferred = CompletableDeferred<WebSocket>()
    val ackDeferred = CompletableDeferred<Boolean>()
    val handshakeDone = AtomicBoolean(false)

    val wsListener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          openDeferred.complete(webSocket)
          webSocket.send(initResult.packet.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          val raw = bytes.toByteArray()
          if (handshakeDone.compareAndSet(false, true)) {
            val ok = TunnelCrypto.verifyAck(raw, sessionKey)
            ackDeferred.complete(ok)
            if (!ok) {
              webSocket.close(1002, "Invalid ACK")
            }
            return
          }
          if (raw.isNotEmpty() && !closed.get()) {
            val decrypted = TunnelCrypto.xorRepeating(raw, 0, raw.size, sessionKey)
            listener.onData(decrypted)
          }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          val raw = text.toByteArray(Charsets.UTF_8)
          if (handshakeDone.compareAndSet(false, true)) {
            val ok = TunnelCrypto.verifyAck(raw, sessionKey)
            ackDeferred.complete(ok)
            if (!ok) {
              webSocket.close(1002, "Invalid ACK")
            }
            return
          }
          if (raw.isNotEmpty() && !closed.get()) {
            val decrypted = TunnelCrypto.xorRepeating(raw, 0, raw.size, sessionKey)
            listener.onData(decrypted)
          }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          if (!ackDeferred.isCompleted) {
            ackDeferred.complete(false)
          }
          webSocket.close(code, reason)
          if (closed.compareAndSet(false, true)) {
            listener.onClosed(reason.ifEmpty { "Remote closed ($code)" })
          }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          if (!ackDeferred.isCompleted) {
            ackDeferred.complete(false)
          }
          if (closed.compareAndSet(false, true)) {
            listener.onClosed(reason.ifEmpty { "Closed ($code)" })
          }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          if (!openDeferred.isCompleted) {
            openDeferred.completeExceptionally(t)
          }
          if (!ackDeferred.isCompleted) {
            ackDeferred.complete(false)
          }
          if (closed.compareAndSet(false, true)) {
            listener.onClosed(t.message ?: "WebSocket failure")
          }
        }
      }

    val ws = client.newWebSocket(requestBuilder.build(), wsListener)
    return try {
      withTimeout(15_000L) { openDeferred.await() }
      val ackOk = withTimeout(10_000L) { ackDeferred.await() }
      if (ackOk) {
        webSocket = ws
        true
      } else {
        ws.cancel()
        false
      }
    } catch (_: Exception) {
      ws.cancel()
      false
    }
  }

  fun sendData(data: ByteArray, offset: Int = 0, length: Int = data.size): Boolean {
    val ws = webSocket ?: return false
    if (closed.get()) return false
    if (length <= 0) return true

    // Respect MAX_CHUNK (65536) and BUFFER_MAX (4194304) from Cl3_04484895.py
    if (ws.queueSize() > TunnelConfig.BUFFER_MAX) {
      return false
    }
    var pos = offset
    var remaining = length
    while (remaining > 0) {
      val chunkLen = minOf(remaining, TunnelConfig.MAX_CHUNK)
      val encrypted = TunnelCrypto.xorRepeating(data, pos, chunkLen, sessionKey)
      if (!ws.send(encrypted.toByteString())) {
        return false
      }
      pos += chunkLen
      remaining -= chunkLen
    }
    return true
  }

  fun close(reason: String = "Client closed") {
    if (closed.compareAndSet(false, true)) {
      try {
        webSocket?.close(1000, reason)
      } catch (_: Exception) {
        webSocket?.cancel()
      }
      webSocket = null
    }
  }

  companion object {
    suspend fun testHandshakeLatency(
      config: TunnelConfig,
      targetHost: String = "example.com",
      targetPort: Int = 443,
      vpnService: VpnService? = null,
    ): Result<Long> {
      val pool = WorkerClientPool(vpnService)
      val primary = pool.createClient(config.workerHost, config.cleanIp)
      val fallback = pool.createClient(config.workerHost, "")
      val start = System.nanoTime()
      val session = WorkerTunnelSession(config, primary, fallback)
      return try {
        val ok =
          session.connect(
            targetHost = targetHost,
            targetPort = targetPort,
            listener =
              object : Listener {
                override fun onData(decryptedData: ByteArray) {}

                override fun onClosed(reason: String?) {}
              },
          )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        session.close("Diagnostic probe complete")
        if (ok) {
          Result.success(elapsedMs)
        } else {
          Result.failure(IOException("Worker ACK handshake failed"))
        }
      } catch (e: Exception) {
        session.close("Error")
        Result.failure(e)
      } finally {
        primary.dispatcher.executorService.shutdown()
        primary.connectionPool.evictAll()
        fallback.dispatcher.executorService.shutdown()
        fallback.connectionPool.evictAll()
      }
    }
  }
}
