package com.example.model

enum class DnsMode(val key: String) {
  DOH_OVER_TUNNEL("doh"),
  TCP_OVER_TUNNEL("tcp");

  companion object {
    fun fromKey(key: String?): DnsMode = entries.firstOrNull { it.key == key } ?: DOH_OVER_TUNNEL
  }
}

enum class Ipv6Mode(val key: String) {
  FORCE_IPV4_NO_LEAK("force_ipv4"),
  TUNNEL_DUAL_STACK("dual_stack");

  companion object {
    fun fromKey(key: String?): Ipv6Mode = entries.firstOrNull { it.key == key } ?: FORCE_IPV4_NO_LEAK
  }
}

data class TunnelConfig(
  val uuid: String = DEFAULT_UUID,
  val workerHost: String = DEFAULT_WORKER_HOST,
  val cleanIp: String = DEFAULT_CLEAN_IP,
  val subprotocol: String = DEFAULT_SUBPROTOCOL,
  val dnsMode: DnsMode = DnsMode.DOH_OVER_TUNNEL,
  val dnsServer: String = DEFAULT_DNS_SERVER,
  val ipv6Mode: Ipv6Mode = Ipv6Mode.FORCE_IPV4_NO_LEAK,
  val bypassLan: Boolean = true,
  val disallowedApps: Set<String> = emptySet(),
) {
  companion object {
    const val DEFAULT_UUID = "63ae8279-fafa-4dd0-b2ef-9d6a4ec3ffdd"
    const val DEFAULT_WORKER_HOST = "twilight-dream-9e3c.aliahmadiphotoofficial.workers.dev"
    const val DEFAULT_CLEAN_IP = "172.67.70.244"
    const val DEFAULT_SUBPROTOCOL = "chat.v2"
    const val DEFAULT_DNS_SERVER = "8.8.8.8"
    const val MAX_CHUNK = 65536
    const val BUFFER_MAX = 4194304
  }
}

sealed class TunnelState {
  data object Disconnected : TunnelState()

  data object Connecting : TunnelState()

  data class Connected(val connectedSinceMs: Long, val activeEndpoint: String) : TunnelState()

  data object Disconnecting : TunnelState()

  data class Error(val message: String) : TunnelState()
}

data class TunnelStats(
  val rxBytes: Long = 0L,
  val txBytes: Long = 0L,
  val rxSpeedBps: Long = 0L,
  val txSpeedBps: Long = 0L,
  val activeConnections: Int = 0,
  val totalConnections: Long = 0L,
  val dnsQueries: Long = 0L,
  val speedHistoryRx: List<Long> = List(30) { 0L },
  val speedHistoryTx: List<Long> = List(30) { 0L },
)

data class ActiveConnectionInfo(
  val id: Long,
  val protocol: String,
  val destination: String,
  val port: Int,
  val state: String,
  val rxBytes: Long,
  val txBytes: Long,
  val startedAtMs: Long,
)

enum class LogLevel {
  INFO,
  WARN,
  ERROR,
}

data class LogEntry(
  val id: Long,
  val timestampMs: Long,
  val level: LogLevel,
  val message: String,
)

data class InstalledAppInfo(
  val packageName: String,
  val label: String,
)
