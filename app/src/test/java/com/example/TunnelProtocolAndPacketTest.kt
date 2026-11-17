package com.example

import com.example.model.TunnelConfig
import com.example.net.IpConstants
import com.example.net.PacketCodec
import com.example.protocol.TunnelCrypto
import com.example.service.PublicIpv4Routes
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelProtocolAndPacketTest {

  @Test
  fun `deriveKey and buildInitPacket match Cl3_04484895 wire protocol`() {
    val nonce = ByteArray(16) { it.toByte() }
    val uuid = TunnelConfig.DEFAULT_UUID
    val key = TunnelCrypto.deriveKey(uuid, nonce)
    assertEquals(16, key.size)

    // Verify domain init packet
    val domainRes = TunnelCrypto.buildInitPacket(uuid, "example.com", 443, nonce = nonce)
    val encLen = 1 + 2 + 1 + 1 + "example.com".length
    assertEquals(16 + encLen + 8, domainRes.packet.size)
    assertArrayEquals(nonce, domainRes.packet.copyOfRange(0, 16))

    // Decrypt body and verify header fields
    val enc = domainRes.packet.copyOfRange(16, 16 + encLen)
    val dec = TunnelCrypto.xorRepeating(enc, 0, enc.size, key)
    assertEquals(1, dec[0].toInt() and 0xFF) // Command = 1
    assertEquals(443, ((dec[1].toInt() and 0xFF) shl 8) or (dec[2].toInt() and 0xFF))
    assertEquals(2, dec[3].toInt() and 0xFF) // AT = 2 (domain)
    assertEquals("example.com".length, dec[4].toInt() and 0xFF)
    assertEquals("example.com", String(dec, 5, dec[4].toInt() and 0xFF, Charsets.UTF_8))

    // Verify HMAC-SHA256 truncated to 8 bytes
    val expectedMac = TunnelCrypto.hmacSha256(key, enc).copyOfRange(0, 8)
    val actualMac = domainRes.packet.copyOfRange(16 + encLen, domainRes.packet.size)
    assertArrayEquals(expectedMac, actualMac)

    // Verify IPv4 literal encoding (AT = 1)
    val ipv4Res = TunnelCrypto.buildInitPacket(uuid, "1.1.1.1", 53, nonce = nonce)
    val ipv4Enc = ipv4Res.packet.copyOfRange(16, ipv4Res.packet.size - 8)
    val ipv4Dec = TunnelCrypto.xorRepeating(ipv4Enc, 0, ipv4Enc.size, key)
    assertEquals(1, ipv4Dec[3].toInt() and 0xFF)
    assertEquals(8, ipv4Dec.size)
  }

  @Test
  fun `verifyAck validates encrypted HMAC-SHA256 OK response`() {
    val nonce = ByteArray(16) { (it + 5).toByte() }
    val key = TunnelCrypto.deriveKey(TunnelConfig.DEFAULT_UUID, nonce)
    val plainAck = TunnelCrypto.hmacSha256(key, "OK".toByteArray(Charsets.US_ASCII)).copyOfRange(0, 16)
    val encryptedAck = TunnelCrypto.xorRepeating(plainAck, 0, plainAck.size, key)

    assertTrue(TunnelCrypto.verifyAck(encryptedAck, key))
    val tampered = encryptedAck.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
    assertFalse(TunnelCrypto.verifyAck(tampered, key))
  }

  @Test
  fun `PacketCodec builds valid IPv4 TCP packet with valid checksums`() {
    val srcIp = InetAddress.getByName("10.8.0.2").address
    val dstIp = InetAddress.getByName("93.184.216.34").address
    val payload = "GET / HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII)

    val raw =
      PacketCodec.buildTcpPacket(
        isIpv6 = false,
        srcIp = srcIp,
        dstIp = dstIp,
        srcPort = 51234,
        dstPort = 443,
        seqNum = 1000L,
        ackNum = 2000L,
        flags = IpConstants.TCP_PSH or IpConstants.TCP_ACK,
        window = 65535,
        payload = payload,
      )

    // IPv4 header checksum over 20 bytes must verify to 0
    assertEquals(0, PacketCodec.internetChecksum(raw, 0, 20))
    // TCP checksum over pseudo-header + TCP segment must verify to 0
    assertEquals(
      0,
      PacketCodec.computeTransportChecksum(
        isIpv6 = false,
        srcIp = srcIp,
        dstIp = dstIp,
        protocol = IpConstants.PROTO_TCP,
        buf = raw,
        transportOffset = 20,
        transportLen = raw.size - 20,
      ),
    )

    val parsed = PacketCodec.parse(raw, raw.size)
    assertNotNull(parsed)
    assertEquals(4, parsed!!.version)
    assertEquals(IpConstants.PROTO_TCP, parsed.protocol)
    assertEquals(51234, parsed.srcPort)
    assertEquals(443, parsed.dstPort)
    assertEquals(1000L, parsed.seqNum)
    assertEquals(2000L, parsed.ackNum)
    assertArrayEquals(payload, parsed.payloadBytes())
  }

  @Test
  fun `PublicIpv4Routes excludes private ranges cleanly`() {
    val routes = PublicIpv4Routes.getPublicRoutes()
    assertTrue(routes.isNotEmpty())
    val cidrs = routes.map { "${it.first}/${it.second}" }.toSet()
    assertFalse(cidrs.contains("10.0.0.0/8"))
    assertFalse(cidrs.contains("192.168.0.0/16"))
    assertFalse(cidrs.contains("172.16.0.0/12"))
    assertTrue(cidrs.contains("1.0.0.0/8"))
    assertTrue(cidrs.contains("8.0.0.0/7"))
  }
}
