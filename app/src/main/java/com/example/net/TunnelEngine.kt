package com.example.net

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.data.TunnelStateHolder
import com.example.model.Ipv6Mode
import com.example.model.LogLevel
import com.example.model.TunnelConfig
import com.example.model.TunnelStats
import com.example.protocol.WorkerClientPool
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelEngine(
  private val vpnService: VpnService,
  private val tunFd: ParcelFileDescriptor,
  private val config: TunnelConfig,
  private val stateHolder: TunnelStateHolder,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pool = WorkerClientPool(vpnService)
  private val primaryClient = pool.createClient(config.workerHost, config.cleanIp)
  private val fallbackClient = pool.createClient(config.workerHost, "")

  private val writeChannel = Channel<ByteArray>(capacity = 2048)
  private val sessions = ConcurrentHashMap<SessionKey, TunTcpSession>()
  private val domainCache = DnsDomainCache()
  private val dnsResolver =
    TunnelDnsResolver(
      config = config,
      primaryClient = primaryClient,
      fallbackClient = fallbackClient,
      domainCache = domainCache,
      onLog = { msg -> stateHolder.appendLog(LogLevel.WARN, msg) },
    )

  private val nextSessionId = AtomicLong(1L)
  private val totalRx = AtomicLong(0L)
  private val totalTx = AtomicLong(0L)
  private val totalConns = AtomicLong(0L)
  private val dnsCount = AtomicLong(0L)

  private var readerJob: Job? = null
  private var writerJob: Job? = null
  private var statsJob: Job? = null

  fun start() {
    stateHolder.appendLog(
      LogLevel.INFO,
      "TunnelEngine started -> ${config.workerHost} (CleanIP: ${config.cleanIp.ifBlank { "DNS" }})",
    )

    writerJob =
      scope.launch(Dispatchers.IO) {
        val out = FileOutputStream(tunFd.fileDescriptor)
        try {
          for (packet in writeChannel) {
            out.write(packet)
          }
        } catch (e: Exception) {
          if (isActive) {
            stateHolder.appendLog(LogLevel.ERROR, "TUN writer stopped: ${e.message}")
          }
        }
      }

    readerJob =
      scope.launch(Dispatchers.IO) {
        val input = FileInputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(32768)
        try {
          while (isActive) {
            val len = input.read(buffer)
            if (len < 0) break
            if (len == 0) continue
            val pktBytes = buffer.copyOf(len)
            val parsed = PacketCodec.parse(pktBytes, len) ?: continue
            dispatchPacket(parsed)
          }
        } catch (e: Exception) {
          if (isActive) {
            stateHolder.appendLog(LogLevel.INFO, "TUN reader ended: ${e.message}")
          }
        }
      }

    statsJob =
      scope.launch(Dispatchers.Default) {
        var prevRx = 0L
        var prevTx = 0L
        val rxHist = ArrayDeque<Long>(30).apply { repeat(30) { addLast(0L) } }
        val txHist = ArrayDeque<Long>(30).apply { repeat(30) { addLast(0L) } }

        while (isActive) {
          delay(1000L)
          val curRx = totalRx.get()
          val curTx = totalTx.get()
          val rxSpeed = (curRx - prevRx).coerceAtLeast(0L)
          val txSpeed = (curTx - prevTx).coerceAtLeast(0L)
          prevRx = curRx
          prevTx = curTx

          if (rxHist.size >= 30) rxHist.removeFirst()
          if (txHist.size >= 30) txHist.removeFirst()
          rxHist.addLast(rxSpeed)
          txHist.addLast(txSpeed)

          // Reap stale/idle TCP sessions (> 3 minutes idle)
          val now = System.currentTimeMillis()
          sessions.values.forEach { session ->
            if (now - session.lastActivityMs > 180_000L) {
              session.closeSession("Idle timeout")
            }
          }

          val activeList =
            sessions.values
              .map { it.toConnectionInfo() }
              .sortedByDescending { it.startedAtMs }
              .take(50)

          stateHolder.updateStats(
            TunnelStats(
              rxBytes = curRx,
              txBytes = curTx,
              rxSpeedBps = rxSpeed,
              txSpeedBps = txSpeed,
              activeConnections = sessions.size,
              totalConnections = totalConns.get(),
              dnsQueries = dnsCount.get(),
              speedHistoryRx = rxHist.toList(),
              speedHistoryTx = txHist.toList(),
            ),
            activeList,
          )
        }
      }
  }

  private fun dispatchPacket(packet: ParsedPacket) {
    when (packet.protocol) {
      IpConstants.PROTO_TCP -> handleTcpPacket(packet)
      IpConstants.PROTO_UDP -> handleUdpPacket(packet)
      else -> Unit
    }
  }

  private fun handleTcpPacket(packet: ParsedPacket) {
    if (packet.isIpv6 && config.ipv6Mode == Ipv6Mode.FORCE_IPV4_NO_LEAK) {
      // Immediately send RST for any IPv6 TCP attempt so apps fall back to IPv4 instantly
      val rst =
        PacketCodec.buildTcpPacket(
          isIpv6 = true,
          srcIp = packet.dstIp,
          dstIp = packet.srcIp,
          srcPort = packet.dstPort,
          dstPort = packet.srcPort,
          seqNum = packet.ackNum,
          ackNum = TunTcpSession.incSeq(packet.seqNum, maxOf(1, packet.payloadLength).toLong()),
          flags = IpConstants.TCP_RST or IpConstants.TCP_ACK,
          window = 0,
        )
      enqueueWrite(rst)
      return
    }

    val key =
      SessionKey(
        isIpv6 = packet.isIpv6,
        srcIp = packet.srcIp,
        srcPort = packet.srcPort,
        dstIp = packet.dstIp,
        dstPort = packet.dstPort,
      )

    val existing = sessions[key]
    if (existing != null) {
      existing.onClientPacket(packet)
      return
    }

    if ((packet.tcpFlags and IpConstants.TCP_SYN) != 0 &&
      (packet.tcpFlags and IpConstants.TCP_ACK) == 0
    ) {
      val dstIpStr = packet.dstIpString()
      val targetHost = domainCache.get(dstIpStr) ?: dstIpStr
      val session =
        TunTcpSession(
          id = nextSessionId.getAndIncrement(),
          key = key,
          targetHost = targetHost,
          config = config,
          primaryClient = primaryClient,
          fallbackClient = fallbackClient,
          scope = scope,
          writeToTun = ::enqueueWrite,
          onTraffic = { rx, tx ->
            if (rx > 0) totalRx.addAndGet(rx)
            if (tx > 0) totalTx.addAndGet(tx)
          },
          onSessionClosed = { k -> sessions.remove(k) },
          onLog = { msg -> stateHolder.appendLog(LogLevel.WARN, msg) },
        )
      if (sessions.putIfAbsent(key, session) == null) {
        totalConns.incrementAndGet()
        session.startOnSyn(packet)
      }
    } else if ((packet.tcpFlags and IpConstants.TCP_RST) == 0) {
      // Unknown non-SYN packet -> send RST
      val rst =
        PacketCodec.buildTcpPacket(
          isIpv6 = packet.isIpv6,
          srcIp = packet.dstIp,
          dstIp = packet.srcIp,
          srcPort = packet.dstPort,
          dstPort = packet.srcPort,
          seqNum = packet.ackNum,
          ackNum = TunTcpSession.incSeq(packet.seqNum, packet.payloadLength.toLong()),
          flags = IpConstants.TCP_RST or IpConstants.TCP_ACK,
          window = 0,
        )
      enqueueWrite(rst)
    }
  }

  private fun handleUdpPacket(packet: ParsedPacket) {
    if (packet.dstPort == 53 && packet.payloadLength > 0) {
      val queryPayload = packet.payloadBytes()
      dnsCount.incrementAndGet()
      totalTx.addAndGet(queryPayload.size.toLong())

      scope.launch(Dispatchers.IO) {
        val responsePayload = dnsResolver.resolve(queryPayload)
        if (responsePayload != null) {
          totalRx.addAndGet(responsePayload.size.toLong())
          val udpResp =
            PacketCodec.buildUdpPacket(
              isIpv6 = packet.isIpv6,
              srcIp = packet.dstIp,
              dstIp = packet.srcIp,
              srcPort = packet.dstPort,
              dstPort = packet.srcPort,
              payload = responsePayload,
            )
          enqueueWrite(udpResp)
        }
      }
    } else {
      // Non-DNS UDP (e.g. QUIC UDP/443): send ICMP Port Unreachable so apps immediately
      // fall back to TCP/TLS without waiting for QUIC timeout.
      PacketCodec.buildIcmpPortUnreachable(packet)?.let(::enqueueWrite)
    }
  }

  private fun enqueueWrite(packet: ByteArray) {
    writeChannel.trySend(packet)
  }

  fun stop() {
    sessions.values.forEach { it.closeSession("VPN stopped") }
    sessions.clear()
    domainCache.clear()
    writeChannel.close()
    readerJob?.cancel()
    writerJob?.cancel()
    statsJob?.cancel()
    scope.cancel()
    primaryClient.dispatcher.executorService.shutdown()
    primaryClient.connectionPool.evictAll()
    fallbackClient.dispatcher.executorService.shutdown()
    fallbackClient.connectionPool.evictAll()
    stateHolder.appendLog(LogLevel.INFO, "TunnelEngine stopped")
  }
}
