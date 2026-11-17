package com.example.protocol

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TunnelCrypto {

  private val secureRandom = SecureRandom()

  data class InitPacketResult(
    val packet: ByteArray,
    val key: ByteArray,
  )

  fun randomNonce16(): ByteArray = ByteArray(16).also { secureRandom.nextBytes(it) }

  fun deriveKey(uuid: String, nonce: ByteArray): ByteArray {
    require(nonce.size == 16) { "Nonce must be exactly 16 bytes" }
    val tb = uuid.toByteArray(Charsets.UTF_8)
    require(tb.isNotEmpty()) { "UUID must not be empty" }
    return ByteArray(16) { i ->
      (tb[i % tb.size].toInt() xor nonce[i].toInt()).toByte()
    }
  }

  fun buildInitPacket(
    uuid: String,
    host: String,
    port: Int,
    payload: ByteArray = ByteArray(0),
    nonce: ByteArray = randomNonce16(),
  ): InitPacketResult {
    require(port in 1..65535) { "Invalid port: $port" }
    val key = deriveKey(uuid, nonce)

    val ipv4 = parseIpv4Literal(host)
    val ipv6 = if (ipv4 == null) parseIpv6Literal(host) else null

    val at: Byte
    val addr: ByteArray
    when {
      ipv4 != null -> {
        at = 1
        addr = ipv4
      }
      ipv6 != null -> {
        at = 3
        addr = ipv6
      }
      else -> {
        val hb = host.toByteArray(Charsets.UTF_8)
        require(hb.size <= 255) { "Hostname too long: ${hb.size}" }
        at = 2
        addr = ByteArray(1 + hb.size).apply {
          this[0] = hb.size.toByte()
          System.arraycopy(hb, 0, this, 1, hb.size)
        }
      }
    }

    val body = ByteArray(4 + addr.size + payload.size).apply {
      this[0] = 1
      this[1] = ((port ushr 8) and 0xFF).toByte()
      this[2] = (port and 0xFF).toByte()
      this[3] = at
      System.arraycopy(addr, 0, this, 4, addr.size)
      if (payload.isNotEmpty()) {
        System.arraycopy(payload, 0, this, 4 + addr.size, payload.size)
      }
    }

    val enc = xorRepeating(body, 0, body.size, key)
    val mac = hmacSha256(key, enc).copyOfRange(0, 8)
    val packet = ByteArray(nonce.size + enc.size + mac.size).apply {
      System.arraycopy(nonce, 0, this, 0, nonce.size)
      System.arraycopy(enc, 0, this, nonce.size, enc.size)
      System.arraycopy(mac, 0, this, nonce.size + enc.size, mac.size)
    }
    return InitPacketResult(packet = packet, key = key)
  }

  fun verifyAck(ack: ByteArray, key: ByteArray): Boolean {
    if (ack.size != 16) return false
    val expect = hmacSha256(key, "OK".toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16)
    val dec = xorRepeating(ack, 0, ack.size, key)
    return MessageDigest.isEqual(dec, expect)
  }

  fun xorRepeating(data: ByteArray, offset: Int, length: Int, key: ByteArray): ByteArray {
    val keyLen = key.size
    val out = ByteArray(length)
    for (i in 0 until length) {
      out[i] = (data[offset + i].toInt() xor key[i % keyLen].toInt()).toByte()
    }
    return out
  }

  fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  /**
   * Parses an IPv4 numeric literal strictly without performing any DNS resolution.
   */
  fun parseIpv4Literal(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for (i in 0 until 4) {
      val part = parts[i]
      if (part.isEmpty() || part.length > 3) return null
      if (part.length > 1 && part[0] == '0') return null
      val value = part.toIntOrNull() ?: return null
      if (value !in 0..255) return null
      out[i] = value.toByte()
    }
    return out
  }

  /**
   * Parses an IPv6 numeric literal strictly without performing any DNS resolution.
   */
  fun parseIpv6Literal(host: String): ByteArray? {
    val trimmed = host.removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!trimmed.contains(':')) return null
    // Only allow hex digits, colons, and dots (for IPv4-mapped IPv6)
    for (ch in trimmed) {
      val valid = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' || ch == ':' || ch == '.'
      if (!valid) return null
    }
    return try {
      val addr = InetAddress.getByName(trimmed)
      if (addr is Inet6Address) addr.address else if (addr is Inet4Address) null else null
    } catch (_: Exception) {
      null
    }
  }
}
