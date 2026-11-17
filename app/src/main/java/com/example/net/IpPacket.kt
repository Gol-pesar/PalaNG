package com.example.net

import java.net.InetAddress

object IpConstants {
  const val PROTO_ICMPV4 = 1
  const val PROTO_TCP = 6
  const val PROTO_UDP = 17
  const val PROTO_ICMPV6 = 58

  const val TCP_FIN = 0x01
  const val TCP_SYN = 0x02
  const val TCP_RST = 0x04
  const val TCP_PSH = 0x08
  const val TCP_ACK = 0x10
}

data class ParsedPacket(
  val version: Int,
  val protocol: Int,
  val ipHeaderLen: Int,
  val totalLen: Int,
  val srcIp: ByteArray,
  val dstIp: ByteArray,
  val srcPort: Int,
  val dstPort: Int,
  val seqNum: Long,
  val ackNum: Long,
  val tcpFlags: Int,
  val tcpWindow: Int,
  val tcpMss: Int?,
  val tcpWindowScale: Int?,
  val payloadOffset: Int,
  val payloadLength: Int,
  val raw: ByteArray,
) {
  val isIpv6: Boolean get() = version == 6

  fun srcIpString(): String = InetAddress.getByAddress(srcIp).hostAddress ?: ""

  fun dstIpString(): String = InetAddress.getByAddress(dstIp).hostAddress ?: ""

  fun payloadBytes(): ByteArray =
    if (payloadLength <= 0) ByteArray(0)
    else raw.copyOfRange(payloadOffset, payloadOffset + payloadLength)
}

object PacketCodec {

  fun parse(raw: ByteArray, length: Int): ParsedPacket? {
    if (length < 20) return null
    val version = (raw[0].toInt() ushr 4) and 0x0F
    return when (version) {
      4 -> parseIpv4(raw, length)
      6 -> parseIpv6(raw, length)
      else -> null
    }
  }

  private fun parseIpv4(raw: ByteArray, length: Int): ParsedPacket? {
    val ihl = (raw[0].toInt() and 0x0F) * 4
    if (ihl < 20 || length < ihl) return null
    val totalLen = readU16(raw, 2).coerceAtMost(length)
    if (totalLen < ihl) return null
    // Ignore fragmented IPv4 packets other than first unfragmented
    val fragOffset = readU16(raw, 6) and 0x1FFF
    val moreFragments = (raw[6].toInt() and 0x20) != 0
    if (fragOffset != 0 || moreFragments) return null

    val proto = raw[9].toInt() and 0xFF
    val srcIp = raw.copyOfRange(12, 16)
    val dstIp = raw.copyOfRange(16, 20)

    return parseTransport(
      version = 4,
      protocol = proto,
      ipHeaderLen = ihl,
      totalLen = totalLen,
      srcIp = srcIp,
      dstIp = dstIp,
      raw = raw,
    )
  }

  private fun parseIpv6(raw: ByteArray, length: Int): ParsedPacket? {
    if (length < 40) return null
    val payloadLen = readU16(raw, 4)
    val totalLen = (40 + payloadLen).coerceAtMost(length)
    val nextHeader = raw[6].toInt() and 0xFF
    val srcIp = raw.copyOfRange(8, 24)
    val dstIp = raw.copyOfRange(24, 40)

    return parseTransport(
      version = 6,
      protocol = nextHeader,
      ipHeaderLen = 40,
      totalLen = totalLen,
      srcIp = srcIp,
      dstIp = dstIp,
      raw = raw,
    )
  }

  private fun parseTransport(
    version: Int,
    protocol: Int,
    ipHeaderLen: Int,
    totalLen: Int,
    srcIp: ByteArray,
    dstIp: ByteArray,
    raw: ByteArray,
  ): ParsedPacket? {
    return when (protocol) {
      IpConstants.PROTO_TCP -> {
        if (totalLen < ipHeaderLen + 20) return null
        val srcPort = readU16(raw, ipHeaderLen)
        val dstPort = readU16(raw, ipHeaderLen + 2)
        val seq = readU32(raw, ipHeaderLen + 4)
        val ack = readU32(raw, ipHeaderLen + 8)
        val dataOffset = ((raw[ipHeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || totalLen < ipHeaderLen + dataOffset) return null
        val flags = raw[ipHeaderLen + 13].toInt() and 0xFF
        val window = readU16(raw, ipHeaderLen + 14)

        var mss: Int? = null
        var windowScale: Int? = null
        if (dataOffset > 20 && (flags and IpConstants.TCP_SYN) != 0) {
          var optIdx = ipHeaderLen + 20
          val optEnd = ipHeaderLen + dataOffset
          while (optIdx < optEnd) {
            val kind = raw[optIdx].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) {
              optIdx++
              continue
            }
            if (optIdx + 1 >= optEnd) break
            val optLen = raw[optIdx + 1].toInt() and 0xFF
            if (optLen < 2 || optIdx + optLen > optEnd) break
            if (kind == 2 && optLen == 4) {
              mss = readU16(raw, optIdx + 2)
            } else if (kind == 3 && optLen == 3) {
              windowScale = (raw[optIdx + 2].toInt() and 0xFF).coerceIn(0, 14)
            }
            optIdx += optLen
          }
        }

        val payloadOffset = ipHeaderLen + dataOffset
        val payloadLength = (totalLen - payloadOffset).coerceAtLeast(0)
        ParsedPacket(
          version = version,
          protocol = protocol,
          ipHeaderLen = ipHeaderLen,
          totalLen = totalLen,
          srcIp = srcIp,
          dstIp = dstIp,
          srcPort = srcPort,
          dstPort = dstPort,
          seqNum = seq,
          ackNum = ack,
          tcpFlags = flags,
          tcpWindow = window,
          tcpMss = mss,
          tcpWindowScale = windowScale,
          payloadOffset = payloadOffset,
          payloadLength = payloadLength,
          raw = raw,
        )
      }
      IpConstants.PROTO_UDP -> {
        if (totalLen < ipHeaderLen + 8) return null
        val srcPort = readU16(raw, ipHeaderLen)
        val dstPort = readU16(raw, ipHeaderLen + 2)
        val udpLen = readU16(raw, ipHeaderLen + 4)
        if (udpLen < 8 || ipHeaderLen + udpLen > totalLen) return null
        val payloadOffset = ipHeaderLen + 8
        val payloadLength = udpLen - 8
        ParsedPacket(
          version = version,
          protocol = protocol,
          ipHeaderLen = ipHeaderLen,
          totalLen = totalLen,
          srcIp = srcIp,
          dstIp = dstIp,
          srcPort = srcPort,
          dstPort = dstPort,
          seqNum = 0L,
          ackNum = 0L,
          tcpFlags = 0,
          tcpWindow = 0,
          tcpMss = null,
          tcpWindowScale = null,
          payloadOffset = payloadOffset,
          payloadLength = payloadLength,
          raw = raw,
        )
      }
      else -> {
        ParsedPacket(
          version = version,
          protocol = protocol,
          ipHeaderLen = ipHeaderLen,
          totalLen = totalLen,
          srcIp = srcIp,
          dstIp = dstIp,
          srcPort = 0,
          dstPort = 0,
          seqNum = 0L,
          ackNum = 0L,
          tcpFlags = 0,
          tcpWindow = 0,
          tcpMss = null,
          tcpWindowScale = null,
          payloadOffset = ipHeaderLen,
          payloadLength = (totalLen - ipHeaderLen).coerceAtLeast(0),
          raw = raw,
        )
      }
    }
  }

  fun buildTcpPacket(
    isIpv6: Boolean,
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seqNum: Long,
    ackNum: Long,
    flags: Int,
    window: Int,
    payload: ByteArray = ByteArray(0),
    mssOption: Int? = null,
    windowScaleOption: Int? = null,
  ): ByteArray {
    var optionsLen = 0
    if (mssOption != null) optionsLen += 4
    if (windowScaleOption != null) optionsLen += 4 // NOP + Kind 3 + Len 3 + Shift (4 bytes aligned)
    val tcpHeaderLen = 20 + optionsLen
    val ipHeaderLen = if (isIpv6) 40 else 20
    val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
    val pkt = ByteArray(totalLen)

    if (isIpv6) {
      writeIpv6Header(pkt, totalLen - 40, IpConstants.PROTO_TCP, srcIp, dstIp)
    } else {
      writeIpv4Header(pkt, totalLen, IpConstants.PROTO_TCP, srcIp, dstIp)
    }

    // TCP Header
    writeU16(pkt, ipHeaderLen, srcPort)
    writeU16(pkt, ipHeaderLen + 2, dstPort)
    writeU32(pkt, ipHeaderLen + 4, seqNum)
    writeU32(pkt, ipHeaderLen + 8, ackNum)
    pkt[ipHeaderLen + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
    pkt[ipHeaderLen + 13] = (flags and 0xFF).toByte()
    writeU16(pkt, ipHeaderLen + 14, window.coerceIn(0, 65535))

    var optOffset = ipHeaderLen + 20
    if (mssOption != null) {
      pkt[optOffset] = 2
      pkt[optOffset + 1] = 4
      writeU16(pkt, optOffset + 2, mssOption)
      optOffset += 4
    }
    if (windowScaleOption != null) {
      pkt[optOffset] = 1 // NOP
      pkt[optOffset + 1] = 3
      pkt[optOffset + 2] = 3
      pkt[optOffset + 3] = windowScaleOption.coerceIn(0, 14).toByte()
    }

    if (payload.isNotEmpty()) {
      System.arraycopy(payload, 0, pkt, ipHeaderLen + tcpHeaderLen, payload.size)
    }

    val tcpChecksum =
      computeTransportChecksum(
        isIpv6 = isIpv6,
        srcIp = srcIp,
        dstIp = dstIp,
        protocol = IpConstants.PROTO_TCP,
        buf = pkt,
        transportOffset = ipHeaderLen,
        transportLen = tcpHeaderLen + payload.size,
      )
    writeU16(pkt, ipHeaderLen + 16, tcpChecksum)
    return pkt
  }

  fun buildUdpPacket(
    isIpv6: Boolean,
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
  ): ByteArray {
    val ipHeaderLen = if (isIpv6) 40 else 20
    val udpLen = 8 + payload.size
    val totalLen = ipHeaderLen + udpLen
    val pkt = ByteArray(totalLen)

    if (isIpv6) {
      writeIpv6Header(pkt, udpLen, IpConstants.PROTO_UDP, srcIp, dstIp)
    } else {
      writeIpv4Header(pkt, totalLen, IpConstants.PROTO_UDP, srcIp, dstIp)
    }

    writeU16(pkt, ipHeaderLen, srcPort)
    writeU16(pkt, ipHeaderLen + 2, dstPort)
    writeU16(pkt, ipHeaderLen + 4, udpLen)
    System.arraycopy(payload, 0, pkt, ipHeaderLen + 8, payload.size)

    val checksum =
      computeTransportChecksum(
        isIpv6 = isIpv6,
        srcIp = srcIp,
        dstIp = dstIp,
        protocol = IpConstants.PROTO_UDP,
        buf = pkt,
        transportOffset = ipHeaderLen,
        transportLen = udpLen,
      )
    writeU16(pkt, ipHeaderLen + 6, if (checksum == 0 && !isIpv6) 0xFFFF else checksum)
    return pkt
  }

  /**
   * Builds an ICMP Port Unreachable packet (ICMPv4 Type 3 Code 3 or ICMPv6 Type 1 Code 4)
   * so apps using QUIC (UDP 443) immediately fall back to TCP HTTPS without waiting for timeout.
   */
  fun buildIcmpPortUnreachable(original: ParsedPacket): ByteArray? {
    return if (!original.isIpv6) {
      val quotedLen = minOf(original.totalLen, original.ipHeaderLen + 8)
      val icmpLen = 8 + quotedLen
      val totalLen = 20 + icmpLen
      val pkt = ByteArray(totalLen)
      writeIpv4Header(pkt, totalLen, IpConstants.PROTO_ICMPV4, original.dstIp, original.srcIp)
      pkt[20] = 3 // Destination Unreachable
      pkt[21] = 3 // Port Unreachable
      System.arraycopy(original.raw, 0, pkt, 28, quotedLen)
      val csum = internetChecksum(pkt, 20, icmpLen)
      writeU16(pkt, 22, csum)
      pkt
    } else {
      val quotedLen = minOf(original.totalLen, 1280 - 48)
      val icmpLen = 8 + quotedLen
      val totalLen = 40 + icmpLen
      val pkt = ByteArray(totalLen)
      writeIpv6Header(pkt, icmpLen, IpConstants.PROTO_ICMPV6, original.dstIp, original.srcIp)
      pkt[40] = 1 // Destination Unreachable
      pkt[41] = 4 // Port Unreachable
      System.arraycopy(original.raw, 0, pkt, 48, quotedLen)
      val csum =
        computeTransportChecksum(
          isIpv6 = true,
          srcIp = original.dstIp,
          dstIp = original.srcIp,
          protocol = IpConstants.PROTO_ICMPV6,
          buf = pkt,
          transportOffset = 40,
          transportLen = icmpLen,
        )
      writeU16(pkt, 42, csum)
      pkt
    }
  }

  private fun writeIpv4Header(
    buf: ByteArray,
    totalLen: Int,
    protocol: Int,
    srcIp: ByteArray,
    dstIp: ByteArray,
  ) {
    buf[0] = 0x45.toByte()
    buf[1] = 0x00
    writeU16(buf, 2, totalLen)
    writeU16(buf, 4, 0)
    buf[6] = 0x40.toByte() // Don't Fragment
    buf[7] = 0x00
    buf[8] = 64 // TTL
    buf[9] = protocol.toByte()
    buf[10] = 0
    buf[11] = 0
    System.arraycopy(srcIp, 0, buf, 12, 4)
    System.arraycopy(dstIp, 0, buf, 16, 4)
    val csum = internetChecksum(buf, 0, 20)
    writeU16(buf, 10, csum)
  }

  private fun writeIpv6Header(
    buf: ByteArray,
    payloadLen: Int,
    nextHeader: Int,
    srcIp: ByteArray,
    dstIp: ByteArray,
  ) {
    buf[0] = 0x60.toByte()
    buf[1] = 0x00
    buf[2] = 0x00
    buf[3] = 0x00
    writeU16(buf, 4, payloadLen)
    buf[6] = nextHeader.toByte()
    buf[7] = 64 // Hop limit
    System.arraycopy(srcIp, 0, buf, 8, 16)
    System.arraycopy(dstIp, 0, buf, 24, 16)
  }

  fun internetChecksum(buf: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
      val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
      sum += word
      i += 2
    }
    if (i < end) {
      sum += (buf[i].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }

  fun computeTransportChecksum(
    isIpv6: Boolean,
    srcIp: ByteArray,
    dstIp: ByteArray,
    protocol: Int,
    buf: ByteArray,
    transportOffset: Int,
    transportLen: Int,
  ): Int {
    var sum = 0L
    // Pseudo-header src + dst
    var i = 0
    while (i < srcIp.size) {
      sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
      sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
      i += 2
    }
    if (isIpv6) {
      sum += (transportLen ushr 16) and 0xFFFF
      sum += transportLen and 0xFFFF
      sum += protocol and 0xFF
    } else {
      sum += protocol and 0xFF
      sum += transportLen and 0xFFFF
    }

    // Transport header + payload
    var j = transportOffset
    val end = transportOffset + transportLen
    while (j + 1 < end) {
      sum += ((buf[j].toInt() and 0xFF) shl 8) or (buf[j + 1].toInt() and 0xFF)
      j += 2
    }
    if (j < end) {
      sum += (buf[j].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }

  fun readU16(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

  fun readU32(buf: ByteArray, offset: Int): Long =
    ((buf[offset].toLong() and 0xFF) shl 24) or
      ((buf[offset + 1].toLong() and 0xFF) shl 16) or
      ((buf[offset + 2].toLong() and 0xFF) shl 8) or
      (buf[offset + 3].toLong() and 0xFF)

  fun writeU16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
  }

  fun writeU32(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
  }
}
