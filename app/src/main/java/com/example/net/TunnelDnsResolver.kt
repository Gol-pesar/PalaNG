package com.example.net

import com.example.model.DnsMode
import com.example.model.Ipv6Mode
import com.example.model.TunnelConfig
import com.example.protocol.WorkerTunnelSession
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * Maps resolved IP addresses back to their queried domain names so TCP connections
 * can optionally pass the hostname to the Cloudflare Worker.
 */
class DnsDomainCache {
  private data class Entry(val domain: String, val expiresAtMs: Long)

  private val map = ConcurrentHashMap<String, Entry>()

  fun put(ip: String, domain: String, ttlSeconds: Long = 300L) {
    if (ip.isBlank() || domain.isBlank()) return
    val clamped = ttlSeconds.coerceIn(30L, 600L)
    map[ip] = Entry(domain, System.currentTimeMillis() + clamped * 1000L)
    if (map.size > 2048) {
      val now = System.currentTimeMillis()
      map.entries.removeIf { it.value.expiresAtMs < now }
    }
  }

  fun get(ip: String): String? {
    val entry = map[ip] ?: return null
    if (System.currentTimeMillis() > entry.expiresAtMs) {
      map.remove(ip)
      return null
    }
    return entry.domain
  }

  fun clear() = map.clear()
}

class TunnelDnsResolver(
  private val config: TunnelConfig,
  private val primaryClient: OkHttpClient,
  private val fallbackClient: OkHttpClient,
  private val domainCache: DnsDomainCache,
  private val onLog: (String) -> Unit,
) {
  private data class CacheKey(val qname: String, val qtype: Int)

  private data class CacheValue(val response: ByteArray, val expiresAtMs: Long)

  private val responseCache = ConcurrentHashMap<CacheKey, CacheValue>()

  suspend fun resolve(queryPayload: ByteArray): ByteArray? {
    if (queryPayload.size < 12) return null
    val question = parseQuestion(queryPayload)

    // When IPv6 is in Force-IPv4 No-Leak mode, reply with empty NODATA for AAAA (type 28)
    // so apps immediately connect via IPv4 without wasting time on unreachable IPv6 routes.
    if (question != null && question.qtype == 28 && config.ipv6Mode == Ipv6Mode.FORCE_IPV4_NO_LEAK) {
      return buildEmptyNoErrorResponse(queryPayload)
    }

    if (question != null) {
      val cached = responseCache[CacheKey(question.qname, question.qtype)]
      if (cached != null && System.currentTimeMillis() < cached.expiresAtMs) {
        val patched = cached.response.copyOf()
        patched[0] = queryPayload[0]
        patched[1] = queryPayload[1]
        return patched
      }
    }

    val answer =
      when (config.dnsMode) {
        DnsMode.DOH_OVER_TUNNEL ->
          resolveViaDohOverTunnel(queryPayload) ?: resolveViaTcpOverTunnel(queryPayload)
        DnsMode.TCP_OVER_TUNNEL ->
          resolveViaTcpOverTunnel(queryPayload) ?: resolveViaDohOverTunnel(queryPayload)
      }

    if (answer != null && answer.size >= 12 && question != null) {
      responseCache[CacheKey(question.qname, question.qtype)] =
        CacheValue(answer.copyOf(), System.currentTimeMillis() + 60_000L)
      extractIpMappings(answer, question.qname)
      if (responseCache.size > 1024) {
        val now = System.currentTimeMillis()
        responseCache.entries.removeIf { it.value.expiresAtMs < now }
      }
    }
    return answer
  }

  private suspend fun resolveViaTcpOverTunnel(queryPayload: ByteArray): ByteArray? {
    val dnsIp = config.dnsServer.trim().ifEmpty { TunnelConfig.DEFAULT_DNS_SERVER }
    val session = WorkerTunnelSession(config, primaryClient, fallbackClient)
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, 65536)

    return try {
      val connected =
        session.connect(
          targetHost = dnsIp,
          targetPort = 53,
          listener =
            object : WorkerTunnelSession.Listener {
              override fun onData(decryptedData: ByteArray) {
                runCatching {
                  pipeOut.write(decryptedData)
                  pipeOut.flush()
                }
              }

              override fun onClosed(reason: String?) {
                runCatching { pipeOut.close() }
              }
            },
        )
      if (!connected) return null

      val framed = ByteArray(2 + queryPayload.size)
      framed[0] = ((queryPayload.size ushr 8) and 0xFF).toByte()
      framed[1] = (queryPayload.size and 0xFF).toByte()
      System.arraycopy(queryPayload, 0, framed, 2, queryPayload.size)
      if (!session.sendData(framed)) return null

      withTimeout(6000L) {
        val lenBytes = readExact(pipeIn, 2) ?: return@withTimeout null
        val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
        if (respLen <= 0 || respLen > 4096) return@withTimeout null
        readExact(pipeIn, respLen)
      }
    } catch (_: Exception) {
      null
    } finally {
      session.close("DNS TCP done")
      runCatching { pipeOut.close() }
      runCatching { pipeIn.close() }
    }
  }

  private suspend fun resolveViaDohOverTunnel(queryPayload: ByteArray): ByteArray? {
    val session = WorkerTunnelSession(config, primaryClient, fallbackClient)
    val pipeOut = PipedOutputStream()
    val pipeIn = PipedInputStream(pipeOut, 65536)

    return try {
      val connected =
        session.connect(
          targetHost = "dns.google",
          targetPort = 443,
          listener =
            object : WorkerTunnelSession.Listener {
              override fun onData(decryptedData: ByteArray) {
                runCatching {
                  pipeOut.write(decryptedData)
                  pipeOut.flush()
                }
              }

              override fun onClosed(reason: String?) {
                runCatching { pipeOut.close() }
              }
            },
        )
      if (!connected) return null

      withTimeout(8000L) {
        val virtualSocket = TunnelBridgeSocket(pipeIn, session)
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket =
          sslFactory.createSocket(virtualSocket, "dns.google", 443, true) as SSLSocket
        sslSocket.startHandshake()

        val httpReq =
          buildString {
            append("POST /dns-query HTTP/1.1\r\n")
            append("Host: dns.google\r\n")
            append("Content-Type: application/dns-message\r\n")
            append("Accept: application/dns-message\r\n")
            append("Content-Length: ${queryPayload.size}\r\n")
            append("Connection: close\r\n\r\n")
          }.toByteArray(Charsets.US_ASCII)

        val out = sslSocket.outputStream
        out.write(httpReq)
        out.write(queryPayload)
        out.flush()

        val rawResp = readHttpBody(sslSocket.inputStream)
        runCatching { sslSocket.close() }
        rawResp
      }
    } catch (e: Exception) {
      onLog("DoH tunnel fallback: ${e.message}")
      null
    } finally {
      session.close("DoH done")
      runCatching { pipeOut.close() }
      runCatching { pipeIn.close() }
    }
  }

  private fun readHttpBody(input: InputStream): ByteArray? {
    val buf = ByteArrayOutputStream()
    val temp = ByteArray(4096)
    while (true) {
      val n = input.read(temp)
      if (n <= 0) break
      buf.write(temp, 0, n)
      val arr = buf.toByteArray()
      val headerEnd = findHeaderEnd(arr)
      if (headerEnd >= 0) {
        val headers = String(arr, 0, headerEnd, Charsets.US_ASCII)
        val contentLen =
          Regex("(?im)^Content-Length:\\s*(\\d+)")
            .find(headers)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val bodyStart = headerEnd + 4
        if (contentLen != null) {
          val remaining = contentLen - (arr.size - bodyStart)
          if (remaining <= 0) {
            return arr.copyOfRange(bodyStart, bodyStart + contentLen)
          }
          val rest = readExact(input, remaining) ?: return null
          return arr.copyOfRange(bodyStart, arr.size) + rest
        }
      }
      if (buf.size() > 16384) break
    }
    val arr = buf.toByteArray()
    val headerEnd = findHeaderEnd(arr)
    if (headerEnd < 0) return null
    return arr.copyOfRange(headerEnd + 4, arr.size)
  }

  private fun findHeaderEnd(bytes: ByteArray): Int {
    for (i in 0..bytes.size - 4) {
      if (
        bytes[i] == 13.toByte() &&
          bytes[i + 1] == 10.toByte() &&
          bytes[i + 2] == 13.toByte() &&
          bytes[i + 3] == 10.toByte()
      ) {
        return i
      }
    }
    return -1
  }

  private fun readExact(input: InputStream, count: Int): ByteArray? {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
      val n = input.read(out, read, count - read)
      if (n <= 0) return null
      read += n
    }
    return out
  }

  private data class DnsQuestion(val qname: String, val qtype: Int)

  private fun parseQuestion(payload: ByteArray): DnsQuestion? {
    if (payload.size < 13) return null
    val qdCount = PacketCodec.readU16(payload, 4)
    if (qdCount < 1) return null
    val (name, nextIdx) = readDnsName(payload, 12) ?: return null
    if (nextIdx + 4 > payload.size) return null
    val qtype = PacketCodec.readU16(payload, nextIdx)
    return DnsQuestion(name.lowercase(), qtype)
  }

  private fun readDnsName(buf: ByteArray, startOffset: Int): Pair<String, Int>? {
    val sb = StringBuilder()
    var idx = startOffset
    var jumped = false
    var endOffset = startOffset
    var jumps = 0
    while (idx < buf.size) {
      val len = buf[idx].toInt() and 0xFF
      if (len == 0) {
        if (!jumped) endOffset = idx + 1
        return sb.toString() to endOffset
      }
      if ((len and 0xC0) == 0xC0) {
        if (idx + 1 >= buf.size || jumps++ > 10) return null
        val ptr = ((len and 0x3F) shl 8) or (buf[idx + 1].toInt() and 0xFF)
        if (!jumped) endOffset = idx + 2
        jumped = true
        idx = ptr
      } else {
        idx++
        if (idx + len > buf.size) return null
        if (sb.isNotEmpty()) sb.append('.')
        sb.append(String(buf, idx, len, Charsets.US_ASCII))
        idx += len
      }
    }
    return null
  }

  private fun extractIpMappings(resp: ByteArray, qname: String) {
    if (resp.size < 12) return
    val qdCount = PacketCodec.readU16(resp, 4)
    val anCount = PacketCodec.readU16(resp, 6)
    var idx = 12
    repeat(qdCount) {
      val (_, next) = readDnsName(resp, idx) ?: return
      idx = next + 4
    }
    repeat(anCount) {
      val (_, next) = readDnsName(resp, idx) ?: return
      if (next + 10 > resp.size) return
      val type = PacketCodec.readU16(resp, next)
      val ttl = PacketCodec.readU32(resp, next + 4)
      val rdLen = PacketCodec.readU16(resp, next + 8)
      val rdataOffset = next + 10
      if (rdataOffset + rdLen > resp.size) return
      if (type == 1 && rdLen == 4) {
        val ip = InetAddress.getByAddress(resp.copyOfRange(rdataOffset, rdataOffset + 4)).hostAddress
        if (ip != null) domainCache.put(ip, qname, ttl)
      } else if (type == 28 && rdLen == 16) {
        val ip =
          InetAddress.getByAddress(resp.copyOfRange(rdataOffset, rdataOffset + 16)).hostAddress
        if (ip != null) domainCache.put(ip, qname, ttl)
      }
      idx = rdataOffset + rdLen
    }
  }

  private fun buildEmptyNoErrorResponse(query: ByteArray): ByteArray {
    // Copy query header + question, set QR=1, RA=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
    val question = parseQuestion(query)
    val endOfQuestion =
      if (question != null) {
        val (_, next) = readDnsName(query, 12) ?: ("" to 12)
        minOf(query.size, next + 4)
      } else {
        minOf(query.size, 12)
      }
    val out = query.copyOfRange(0, endOfQuestion)
    out[2] = ((out[2].toInt() and 0x01) or 0x81).toByte() // QR=1, keep RD
    out[3] = 0x80.toByte() // RA=1, RCODE=0 (NOERROR)
    out[6] = 0
    out[7] = 0
    out[8] = 0
    out[9] = 0
    out[10] = 0
    out[11] = 0
    return out
  }

  /**
   * Minimal java.net.Socket bridge over a WorkerTunnelSession so standard Java SSLSocketFactory
   * can perform full TLS verification inside the WebSocket tunnel (used for DoH).
   */
  private class TunnelBridgeSocket(
    private val input: InputStream,
    private val session: WorkerTunnelSession,
  ) : Socket() {
    private val out =
      object : OutputStream() {
        override fun write(b: Int) {
          session.sendData(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          session.sendData(b, off, len)
        }
      }

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = out

    override fun isConnected(): Boolean = true

    override fun isClosed(): Boolean = false

    override fun close() {
      session.close("Socket closed")
    }
  }
}
