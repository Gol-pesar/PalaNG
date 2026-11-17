package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ConfigRepository
import com.example.data.TunnelStateHolder
import com.example.model.LogLevel
import com.example.model.TunnelConfig
import com.example.model.TunnelState
import com.example.net.TunnelEngine
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TunnelVpnService : VpnService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var tunInterface: ParcelFileDescriptor? = null
  private var engine: TunnelEngine? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopTunnel()
        return START_NOT_STICKY
      }
      ACTION_START,
      null,
      SERVICE_INTERFACE -> {
        startTunnel()
        return START_STICKY
      }
      else -> return START_NOT_STICKY
    }
  }

  private fun startTunnel() {
    if (engine != null) return
    TunnelStateHolder.setState(TunnelState.Connecting)
    TunnelStateHolder.resetSessionStats()

    val notification = buildNotification("در حال برقراری اتصال تانل مستقیم...")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    serviceScope.launch {
      try {
        val config = ConfigRepository(applicationContext).getCurrentConfig()
        if (config.uuid.isBlank() || config.workerHost.isBlank()) {
          throw IllegalStateException("UUID یا آدرس Worker نامعتبر است")
        }

        val builder =
          Builder()
            .setSession("DirectTunnel")
            .setMtu(1500)
            .addAddress(VPN_IPV4_ADDR, 32)
            .addDnsServer(VPN_IPV4_DNS)
            .addAddress(VPN_IPV6_ADDR, 128)
            .addRoute("::", 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          builder.setMetered(false)
        }

        if (config.bypassLan) {
          for ((cidrAddr, prefix) in PublicIpv4Routes.getPublicRoutes()) {
            builder.addRoute(cidrAddr, prefix)
          }
          // Ensure our virtual DNS IP enters the TUN even though 10.0.0.0/8 is bypassed
          builder.addRoute(VPN_IPV4_DNS, 32)
        } else {
          builder.addRoute("0.0.0.0", 0)
        }

        // Never route this app's own control-plane WebSocket traffic back into TUN
        try {
          builder.addDisallowedApplication(applicationContext.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }

        for (pkg in config.disallowedApps) {
          try {
            builder.addDisallowedApplication(pkg)
          } catch (_: PackageManager.NameNotFoundException) {
          }
        }

        val fd =
          builder.establish()
            ?: throw IllegalStateException("دسترسی VPN توسط سیستم صادر نشد")

        tunInterface = fd
        val tunnelEngine =
          TunnelEngine(
            vpnService = this@TunnelVpnService,
            tunFd = fd,
            config = config,
            stateHolder = TunnelStateHolder,
          )
        engine = tunnelEngine
        tunnelEngine.start()

        val endpoint =
          if (config.cleanIp.isNotBlank()) "${config.cleanIp} -> ${config.workerHost}"
          else config.workerHost

        TunnelStateHolder.setState(
          TunnelState.Connected(
            connectedSinceMs = System.currentTimeMillis(),
            activeEndpoint = endpoint,
          )
        )
        updateNotification("متصل به $endpoint")
      } catch (e: Exception) {
        TunnelStateHolder.appendLog(LogLevel.ERROR, "Start failed: ${e.message}")
        TunnelStateHolder.setState(TunnelState.Error(e.message ?: "خطا در راه‌اندازی تانل"))
        cleanupResources()
        stopSelf()
      }
    }
  }

  private fun stopTunnel() {
    TunnelStateHolder.setState(TunnelState.Disconnecting)
    cleanupResources()
    TunnelStateHolder.setState(TunnelState.Disconnected)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION") stopForeground(true)
    }
    stopSelf()
  }

  private fun cleanupResources() {
    engine?.stop()
    engine = null
    runCatching { tunInterface?.close() }
    tunInterface = null
  }

  override fun onRevoke() {
    TunnelStateHolder.appendLog(LogLevel.WARN, "VPN permission revoked by system")
    stopTunnel()
    super.onRevoke()
  }

  override fun onDestroy() {
    cleanupResources()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(NotificationManager::class.java)
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "DirectTunnel VPN Status",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "نمایش وضعیت اتصال فعال تانل مستقیم گوشی"
          setShowBadge(false)
        }
      mgr.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(contentText: String): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, TunnelVpnService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("DirectTunnel")
      .setContentText(contentText)
      .setSmallIcon(R.drawable.ic_vpn_notification)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .addAction(0, "قطع اتصال", stopIntent)
      .build()
  }

  private fun updateNotification(text: String) {
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.notify(NOTIFICATION_ID, buildNotification(text))
  }

  companion object {
    const val ACTION_START = "com.example.action.START_TUNNEL"
    const val ACTION_STOP = "com.example.action.STOP_TUNNEL"

    private const val CHANNEL_ID = "direct_tunnel_status"
    private const val NOTIFICATION_ID = 4401

    private const val VPN_IPV4_ADDR = "10.8.0.2"
    private const val VPN_IPV4_DNS = "10.8.0.1"
    private const val VPN_IPV6_ADDR = "fd00:10:8::2"

    fun start(context: Context) {
      val intent = Intent(context, TunnelVpnService::class.java).apply { action = ACTION_START }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, TunnelVpnService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }
}

/**
 * Computes exact non-overlapping public IPv4 CIDR routes excluding private/local ranges
 * so LAN traffic remains directly reachable when bypassLan is enabled.
 */
object PublicIpv4Routes {
  private val reservedRanges =
    listOf(
      ip("0.0.0.0") to ip("0.255.255.255"),         // Current network
      ip("10.0.0.0") to ip("10.255.255.255"),       // RFC 1918
      ip("127.0.0.0") to ip("127.255.255.255"),     // Loopback
      ip("169.254.0.0") to ip("169.254.255.255"),   // Link-local
      ip("172.16.0.0") to ip("172.31.255.255"),     // RFC 1918
      ip("192.168.0.0") to ip("192.168.255.255"),   // RFC 1918
      ip("224.0.0.0") to ip("255.255.255.255"),     // Multicast + Reserved
    )

  private val cachedRoutes: List<Pair<String, Int>> by lazy { computeRoutes() }

  fun getPublicRoutes(): List<Pair<String, Int>> = cachedRoutes

  private fun computeRoutes(): List<Pair<String, Int>> {
    val allowed = mutableListOf<Pair<Long, Long>>()
    var cursor = 0L
    for ((resStart, resEnd) in reservedRanges) {
      if (cursor < resStart) {
        allowed.add(cursor to (resStart - 1L))
      }
      cursor = maxOf(cursor, resEnd + 1L)
    }
    if (cursor <= 0xFFFFFFFFL) {
      allowed.add(cursor to 0xFFFFFFFFL)
    }

    val cidrs = mutableListOf<Pair<String, Int>>()
    for ((start, end) in allowed) {
      rangeToCidrs(start, end, cidrs)
    }
    return cidrs
  }

  private fun rangeToCidrs(startIp: Long, endIp: Long, out: MutableList<Pair<String, Int>>) {
    var start = startIp
    while (start <= endIp) {
      var maxSize = 32
      while (maxSize > 0) {
        val mask = ((1L shl 32) - (1L shl (32 - (maxSize - 1)))) and 0xFFFFFFFFL
        val base = start and mask
        if (base != start) break
        maxSize--
      }
      val diff = (endIp - start) + 1L
      var bits = 0
      while ((1L shl (bits + 1)) <= diff && bits < 32) {
        bits++
      }
      val prefixFromDiff = 32 - bits
      val prefix = maxOf(maxSize, prefixFromDiff)
      out.add(longToIp(start) to prefix)
      start += 1L shl (32 - prefix)
    }
  }

  private fun ip(str: String): Long {
    val bytes = InetAddress.getByName(str).address
    return ((bytes[0].toLong() and 0xFF) shl 24) or
      ((bytes[1].toLong() and 0xFF) shl 16) or
      ((bytes[2].toLong() and 0xFF) shl 8) or
      (bytes[3].toLong() and 0xFF)
  }

  private fun longToIp(v: Long): String =
    "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}
