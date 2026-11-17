package com.example.net

import com.example.model.ActiveConnectionInfo
import com.example.model.TunnelConfig
import com.example.protocol.WorkerTunnelSession
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

data class SessionKey(
  val isIpv6: Boolean,
  val srcIp: ByteArray,
  val srcPort: Int,
  val dstIp: ByteArray,
  val dstPort: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SessionKey) return false
    return isIpv6 == other.isIpv6 &&
      srcPort == other.srcPort &&
      dstPort == other.dstPort &&
      srcIp.contentEquals(other.srcIp) &&
      dstIp.contentEquals(other.dstIp)
  }

  override fun hashCode(): Int {
    var result = isIpv6.hashCode()
    result = 31 * result + srcIp.contentHashCode()
    result = 31 * result + srcPort
    result = 31 * result + dstIp.contentHashCode()
    result = 31 * result + dstPort
    return result
  }
}

class TunTcpSession(
  val id: Long,
  val key: SessionKey,
  val targetHost: String,
  private val config: TunnelConfig,
  private val primaryClient: OkHttpClient,
  private val fallbackClient: OkHttpClient,
  private val scope: CoroutineScope,
  private val writeToTun: (ByteArray) -> Unit,
  private val onTraffic: (rxDelta: Long, txDelta: Long) -> Unit,
  private val onSessionClosed: (SessionKey) -> Unit,
  private val onLog: (String) -> Unit,
) {
  enum class TcpState {
    SYN_RECEIVED,
    ESTABLISHED,
    FIN_WAIT_1,
    FIN_WAIT_2,
    CLOSE_WAIT,
    LAST_ACK,
    CLOSED,
  }

  private data class OutboundSegment(
    val seqStart: Long,
    val payload: ByteArray,
    var sentAtMs: Long,
    var retries: Int = 0,
  ) {
    val seqEnd: Long get() = (seqStart + payload.size) and 0xFFFFFFFFL
  }

  private val mutex = Mutex()
  private val closedFlag = AtomicBoolean(false)
  private val workerSession = WorkerTunnelSession(config, primaryClient, fallbackClient)

  @Volatile var state: TcpState = TcpState.SYN_RECEIVED
    private set

  val startedAtMs: Long = System.currentTimeMillis()
  val rxBytes = AtomicLong(0L)
  val txBytes = AtomicLong(0L)

  private var mySeq: Long = (Random.nextLong() and 0x7FFFFFFFL)
  private var myUnackedSeq: Long = mySeq
  private var theirSeq: Long = 0L

  private var clientMss: Int = if (key.isIpv6) 1340 else 1380
  private var clientWindowShift: Int = 0
  private var myWindowShift: Int = 4 // Advertise up to ~1MB receive window
  private var clientWindow: Long = 65535L

  private var lastClientAck: Long = mySeq
  private var dupAckCount: Int = 0

  private val outOfOrder = TreeMap<Long, ByteArray> { a, b -> seqCompare(a, b) }
  private val unackedSegments = ArrayDeque<OutboundSegment>()
  private var unackedByteCount: Long = 0L

  private val downChannel = Channel<ByteArray>(capacity = 256)
  private val windowSignal = Channel<Unit>(Channel.CONFLATED)
  private var senderJob: Job? = null
  private var retransmitJob: Job? = null

  @Volatile var lastActivityMs: Long = startedAtMs
    private set

  fun toConnectionInfo(): ActiveConnectionInfo =
    ActiveConnectionInfo(
      id = id,
      protocol = "TCP",
      destination = targetHost,
      port = key.dstPort,
      state = state.name,
      rxBytes = rxBytes.get(),
      txBytes = txBytes.get(),
      startedAtMs = startedAtMs,
    )

  fun startOnSyn(synPacket: ParsedPacket) {
    scope.launch(Dispatchers.IO) {
      mutex.withLock {
        theirSeq = incSeq(synPacket.seqNum, 1L)
        clientMss =
          (synPacket.tcpMss ?: 1400).coerceIn(536, if (key.isIpv6) 1340 else 1380)
        clientWindowShift = synPacket.tcpWindowScale ?: 0
        if (synPacket.tcpWindowScale == null) {
          myWindowShift = 0
        }
        clientWindow = synPacket.tcpWindow.toLong().coerceAtLeast(4096L)
        lastActivityMs = System.currentTimeMillis()
      }

      val connected =
        workerSession.connect(
          targetHost = targetHost,
          targetPort = key.dstPort,
          listener =
            object : WorkerTunnelSession.Listener {
              override fun onData(decryptedData: ByteArray) {
                lastActivityMs = System.currentTimeMillis()
                rxBytes.addAndGet(decryptedData.size.toLong())
                onTraffic(decryptedData.size.toLong(), 0L)
                val offerResult = downChannel.trySend(decryptedData)
                if (!offerResult.isSuccess && !closedFlag.get()) {
                  scope.launch(Dispatchers.IO) {
                    runCatching { downChannel.send(decryptedData) }
                  }
                }
              }

              override fun onClosed(reason: String?) {
                downChannel.close()
                scope.launch(Dispatchers.IO) { handleRemoteEof() }
              }
            },
        )

      if (!connected || closedFlag.get()) {
        onLog("TCP connect failed: $targetHost:${key.dstPort}")
        sendResetAndClose(ackNum = theirSeq, seqNum = mySeq)
        return@launch
      }

      mutex.withLock {
        if (closedFlag.get()) return@launch
        val synAck =
          PacketCodec.buildTcpPacket(
            isIpv6 = key.isIpv6,
            srcIp = key.dstIp,
            dstIp = key.srcIp,
            srcPort = key.dstPort,
            dstPort = key.srcPort,
            seqNum = mySeq,
            ackNum = theirSeq,
            flags = IpConstants.TCP_SYN or IpConstants.TCP_ACK,
            window = 65535,
            mssOption = clientMss,
            windowScaleOption = if (clientWindowShift > 0 || synPacket.tcpWindowScale != null) myWindowShift else null,
          )
        mySeq = incSeq(mySeq, 1L)
        state = TcpState.ESTABLISHED
        writeToTun(synAck)
      }

      startDownstreamJobs()
    }
  }

  private fun startDownstreamJobs() {
    senderJob =
      scope.launch(Dispatchers.IO) {
        try {
          for (chunk in downChannel) {
            var offset = 0
            while (offset < chunk.size && !closedFlag.get()) {
              val maxChunk = clientMss
              val len = minOf(maxChunk, chunk.size - offset)
              val slice = chunk.copyOfRange(offset, offset + len)
              offset += len

              // Flow control: wait if client's receive window is saturated
              while (!closedFlag.get()) {
                val canSend =
                  mutex.withLock {
                    val effectiveWindow = maxOf(clientWindow, clientMss.toLong())
                    if (unackedByteCount + slice.size <= effectiveWindow * 2) {
                      val seq = mySeq
                      mySeq = incSeq(mySeq, slice.size.toLong())
                      val seg =
                        OutboundSegment(
                          seqStart = seq,
                          payload = slice,
                          sentAtMs = System.currentTimeMillis(),
                        )
                      unackedSegments.addLast(seg)
                      unackedByteCount += slice.size
                      sendTcpDataPacket(seq, slice)
                      true
                    } else {
                      false
                    }
                  }
                if (canSend) break
                withTimeoutOrNullCompat(150L) { windowSignal.receive() }
              }
            }
          }
        } catch (_: Exception) {
        }
      }

    retransmitJob =
      scope.launch(Dispatchers.IO) {
        while (isActive && !closedFlag.get()) {
          delay(250L)
          val now = System.currentTimeMillis()
          var shouldClose = false
          mutex.withLock {
            if (unackedSegments.isNotEmpty()) {
              val oldest = unackedSegments.first()
              val rto = 600L shl minOf(oldest.retries, 4)
              if (now - oldest.sentAtMs >= rto) {
                if (oldest.retries >= 6) {
                  shouldClose = true
                } else {
                  oldest.retries++
                  oldest.sentAtMs = now
                  sendTcpDataPacket(oldest.seqStart, oldest.payload)
                }
              }
            }
          }
          if (shouldClose) {
            closeSession("TCP retransmit timeout")
            break
          }
        }
      }
  }

  fun onClientPacket(packet: ParsedPacket) {
    scope.launch(Dispatchers.IO) {
      lastActivityMs = System.currentTimeMillis()
      val flags = packet.tcpFlags

      if ((flags and IpConstants.TCP_RST) != 0) {
        closeSession("Client RST")
        return@launch
      }

      if ((flags and IpConstants.TCP_SYN) != 0 && state == TcpState.SYN_RECEIVED) {
        // Duplicate SYN while Worker handshake is still in flight; ignore cleanly
        return@launch
      }

      var dataToSendUp: ByteArray? = null
      var shouldSendAck = false
      var ackPacketToSend: ByteArray? = null
      var shouldCloseAfter = false

      mutex.withLock {
        if (closedFlag.get() || state == TcpState.CLOSED) return@launch

        // Update client receive window & process ACK
        if ((flags and IpConstants.TCP_ACK) != 0) {
          clientWindow = (packet.tcpWindow.toLong() shl clientWindowShift).coerceAtLeast(0L)
          processAckLocked(packet.ackNum)
        }

        // Process payload
        if (packet.payloadLength > 0) {
          val segStart = packet.seqNum
          val payload = packet.payloadBytes()
          val cmp = seqCompare(segStart, theirSeq)
          when {
            cmp == 0 -> {
              val assembled = mutableListOf(payload)
              theirSeq = incSeq(theirSeq, payload.size.toLong())
              drainOutOfOrderLocked(assembled)
              dataToSendUp = mergeChunks(assembled)
              shouldSendAck = true
            }
            cmp < 0 -> {
              // Overlapping / duplicate retransmission
              val overlap = (theirSeq - segStart).toInt()
              if (overlap in 1 until payload.size) {
                val fresh = payload.copyOfRange(overlap, payload.size)
                val assembled = mutableListOf(fresh)
                theirSeq = incSeq(theirSeq, fresh.size.toLong())
                drainOutOfOrderLocked(assembled)
                dataToSendUp = mergeChunks(assembled)
              }
              shouldSendAck = true
            }
            else -> {
              // Out-of-order segment within 2MB buffer window
              if ((segStart - theirSeq) and 0xFFFFFFFFL < 2_097_152L && outOfOrder.size < 128) {
                outOfOrder[segStart] = payload
              }
              shouldSendAck = true
            }
          }
        }

        // Process FIN
        if ((flags and IpConstants.TCP_FIN) != 0) {
          val finSeq = incSeq(packet.seqNum, packet.payloadLength.toLong())
          if (seqCompare(finSeq, theirSeq) <= 0) {
            if (state == TcpState.ESTABLISHED || state == TcpState.CLOSE_WAIT) {
              theirSeq = incSeq(finSeq, 1L)
              shouldSendAck = true
              state = TcpState.CLOSE_WAIT
            } else if (state == TcpState.FIN_WAIT_1 || state == TcpState.FIN_WAIT_2) {
              theirSeq = incSeq(finSeq, 1L)
              shouldSendAck = true
              shouldCloseAfter = true
            }
          }
        }

        if (state == TcpState.LAST_ACK && (flags and IpConstants.TCP_ACK) != 0) {
          if (seqCompare(packet.ackNum, mySeq) >= 0) {
            shouldCloseAfter = true
          }
        } else if (state == TcpState.FIN_WAIT_1 && (flags and IpConstants.TCP_ACK) != 0) {
          if (seqCompare(packet.ackNum, mySeq) >= 0) {
            state = TcpState.FIN_WAIT_2
          }
        }

        if (shouldSendAck) {
          ackPacketToSend = buildAckPacketLocked()
        }
      }

      dataToSendUp?.let { bytes ->
        txBytes.addAndGet(bytes.size.toLong())
        onTraffic(0L, bytes.size.toLong())
        val ok = workerSession.sendData(bytes)
        if (!ok) {
          closeSession("Upstream send failed")
          return@launch
        }
      }

      ackPacketToSend?.let { writeToTun(it) }

      if (state == TcpState.CLOSE_WAIT) {
        // Half-close upstream Worker cleanly and send our FIN
        workerSession.close("Client FIN")
        mutex.withLock {
          if (state == TcpState.CLOSE_WAIT && !closedFlag.get()) {
            val finPkt =
              PacketCodec.buildTcpPacket(
                isIpv6 = key.isIpv6,
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seqNum = mySeq,
                ackNum = theirSeq,
                flags = IpConstants.TCP_FIN or IpConstants.TCP_ACK,
                window = 65535,
              )
            mySeq = incSeq(mySeq, 1L)
            state = TcpState.LAST_ACK
            writeToTun(finPkt)
          }
        }
      }

      if (shouldCloseAfter) {
        closeSession("Clean FIN handshake")
      }
    }
  }

  private fun processAckLocked(ackNum: Long) {
    if (seqCompare(ackNum, myUnackedSeq) > 0) {
      myUnackedSeq = ackNum
      dupAckCount = 0
      while (unackedSegments.isNotEmpty()) {
        val head = unackedSegments.first()
        if (seqCompare(head.seqEnd, ackNum) <= 0) {
          unackedSegments.removeFirst()
          unackedByteCount = (unackedByteCount - head.payload.size).coerceAtLeast(0L)
        } else {
          break
        }
      }
      windowSignal.trySend(Unit)
    } else if (ackNum == lastClientAck && unackedSegments.isNotEmpty()) {
      dupAckCount++
      if (dupAckCount == 3) {
        // Fast retransmit
        val oldest = unackedSegments.first()
        oldest.sentAtMs = System.currentTimeMillis()
        sendTcpDataPacket(oldest.seqStart, oldest.payload)
      }
    }
    lastClientAck = ackNum
    if (clientWindow > 0) {
      windowSignal.trySend(Unit)
    }
  }

  private fun drainOutOfOrderLocked(out: MutableList<ByteArray>) {
    while (outOfOrder.isNotEmpty()) {
      val firstSeq = outOfOrder.firstKey()
      val cmp = seqCompare(firstSeq, theirSeq)
      if (cmp == 0) {
        val chunk = outOfOrder.remove(firstSeq) ?: break
        out.add(chunk)
        theirSeq = incSeq(theirSeq, chunk.size.toLong())
      } else if (cmp < 0) {
        val chunk = outOfOrder.remove(firstSeq) ?: break
        val overlap = (theirSeq - firstSeq).toInt()
        if (overlap in 1 until chunk.size) {
          val fresh = chunk.copyOfRange(overlap, chunk.size)
          out.add(fresh)
          theirSeq = incSeq(theirSeq, fresh.size.toLong())
        }
      } else {
        break
      }
    }
  }

  private fun handleRemoteEof() {
    scope.launch(Dispatchers.IO) {
      mutex.withLock {
        if (closedFlag.get()) return@launch
        if (state == TcpState.ESTABLISHED) {
          val finPkt =
            PacketCodec.buildTcpPacket(
              isIpv6 = key.isIpv6,
              srcIp = key.dstIp,
              dstIp = key.srcIp,
              srcPort = key.dstPort,
              dstPort = key.srcPort,
              seqNum = mySeq,
              ackNum = theirSeq,
              flags = IpConstants.TCP_FIN or IpConstants.TCP_ACK,
              window = 65535,
            )
          mySeq = incSeq(mySeq, 1L)
          state = TcpState.FIN_WAIT_1
          writeToTun(finPkt)
        } else if (state == TcpState.CLOSE_WAIT) {
          val finPkt =
            PacketCodec.buildTcpPacket(
              isIpv6 = key.isIpv6,
              srcIp = key.dstIp,
              dstIp = key.srcIp,
              srcPort = key.dstPort,
              dstPort = key.srcPort,
              seqNum = mySeq,
              ackNum = theirSeq,
              flags = IpConstants.TCP_FIN or IpConstants.TCP_ACK,
              window = 65535,
            )
          mySeq = incSeq(mySeq, 1L)
          state = TcpState.LAST_ACK
          writeToTun(finPkt)
        }
      }
    }
  }

  private fun sendTcpDataPacket(seq: Long, payload: ByteArray) {
    val pkt =
      PacketCodec.buildTcpPacket(
        isIpv6 = key.isIpv6,
        srcIp = key.dstIp,
        dstIp = key.srcIp,
        srcPort = key.dstPort,
        dstPort = key.srcPort,
        seqNum = seq,
        ackNum = theirSeq,
        flags = IpConstants.TCP_PSH or IpConstants.TCP_ACK,
        window = 65535,
        payload = payload,
      )
    writeToTun(pkt)
  }

  private fun buildAckPacketLocked(): ByteArray =
    PacketCodec.buildTcpPacket(
      isIpv6 = key.isIpv6,
      srcIp = key.dstIp,
      dstIp = key.srcIp,
      srcPort = key.dstPort,
      dstPort = key.srcPort,
      seqNum = mySeq,
      ackNum = theirSeq,
      flags = IpConstants.TCP_ACK,
      window = 65535,
    )

  private fun sendResetAndClose(ackNum: Long, seqNum: Long) {
    val rst =
      PacketCodec.buildTcpPacket(
        isIpv6 = key.isIpv6,
        srcIp = key.dstIp,
        dstIp = key.srcIp,
        srcPort = key.dstPort,
        dstPort = key.srcPort,
        seqNum = seqNum,
        ackNum = ackNum,
        flags = IpConstants.TCP_RST or IpConstants.TCP_ACK,
        window = 0,
      )
    writeToTun(rst)
    closeSession("RST sent")
  }

  fun closeSession(reason: String = "Closed") {
    if (closedFlag.compareAndSet(false, true)) {
      state = TcpState.CLOSED
      senderJob?.cancel()
      retransmitJob?.cancel()
      downChannel.close()
      windowSignal.close()
      workerSession.close(reason)
      onSessionClosed(key)
    }
  }

  private suspend fun <T> withTimeoutOrNullCompat(ms: Long, block: suspend () -> T): T? =
    kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

  private fun mergeChunks(chunks: List<ByteArray>): ByteArray {
    if (chunks.size == 1) return chunks[0]
    val total = chunks.sumOf { it.size }
    val out = ByteArray(total)
    var pos = 0
    for (c in chunks) {
      System.arraycopy(c, 0, out, pos, c.size)
      pos += c.size
    }
    return out
  }

  companion object {
    fun incSeq(seq: Long, delta: Long): Long = (seq + delta) and 0xFFFFFFFFL

    fun seqCompare(a: Long, b: Long): Int = (a - b).toInt().compareTo(0)
  }
}
