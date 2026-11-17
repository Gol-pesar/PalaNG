package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ConfigRepository
import com.example.data.InstalledAppsProvider
import com.example.data.TunnelStateHolder
import com.example.model.ActiveConnectionInfo
import com.example.model.InstalledAppInfo
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.TunnelConfig
import com.example.model.TunnelState
import com.example.model.TunnelStats
import com.example.protocol.WorkerTunnelSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed class ProbeStatus {
  data object Idle : ProbeStatus()

  data object Running : ProbeStatus()

  data class Success(val rttMs: Long) : ProbeStatus()

  data class Failure(val error: String) : ProbeStatus()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = ConfigRepository(application)

  val config: StateFlow<TunnelConfig> =
    repository.configFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = TunnelConfig(),
    )

  val tunnelState: StateFlow<TunnelState> = TunnelStateHolder.tunnelState
  val stats: StateFlow<TunnelStats> = TunnelStateHolder.stats
  val activeConnections: StateFlow<List<ActiveConnectionInfo>> = TunnelStateHolder.activeConnections
  val logs: StateFlow<List<LogEntry>> = TunnelStateHolder.logs

  private val _probeStatus = MutableStateFlow<ProbeStatus>(ProbeStatus.Idle)
  val probeStatus: StateFlow<ProbeStatus> = _probeStatus.asStateFlow()

  private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
  val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

  private val _appsLoading = MutableStateFlow(false)
  val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

  fun saveConfig(newConfig: TunnelConfig) {
    viewModelScope.launch {
      repository.saveConfig(newConfig)
      TunnelStateHolder.appendLog(LogLevel.INFO, "Configuration saved (${newConfig.workerHost})")
    }
  }

  fun resetConfig() {
    viewModelScope.launch {
      repository.resetToDefault()
      TunnelStateHolder.appendLog(LogLevel.INFO, "Configuration reset to Cl3_04484895 defaults")
    }
  }

  fun runHandshakeProbe() {
    if (_probeStatus.value is ProbeStatus.Running) return
    _probeStatus.value = ProbeStatus.Running
    viewModelScope.launch {
      val cfg = config.value
      val result = WorkerTunnelSession.testHandshakeLatency(cfg, "example.com", 443)
      result.fold(
        onSuccess = { ms ->
          _probeStatus.value = ProbeStatus.Success(ms)
          TunnelStateHolder.appendLog(
            LogLevel.INFO,
            "Handshake probe OK: ${ms}ms -> ${cfg.workerHost}",
          )
        },
        onFailure = { err ->
          val msg = err.message ?: "Handshake failed"
          _probeStatus.value = ProbeStatus.Failure(msg)
          TunnelStateHolder.appendLog(LogLevel.ERROR, "Handshake probe failed: $msg")
        },
      )
    }
  }

  fun clearLogs() {
    TunnelStateHolder.clearLogs()
  }

  fun loadInstalledApps() {
    if (_appsLoading.value || _installedApps.value.isNotEmpty()) return
    _appsLoading.value = true
    viewModelScope.launch {
      _installedApps.value = InstalledAppsProvider.loadLaunchableApps(getApplication())
      _appsLoading.value = false
    }
  }

  fun toggleDisallowedApp(packageName: String) {
    val current = config.value
    val nextSet =
      if (packageName in current.disallowedApps) {
        current.disallowedApps - packageName
      } else {
        current.disallowedApps + packageName
      }
    saveConfig(current.copy(disallowedApps = nextSet))
  }

  fun exportConfigJson(): String {
    val c = config.value
    return JSONObject()
      .apply {
        put("uuid", c.uuid)
        put("workerHost", c.workerHost)
        put("cleanIp", c.cleanIp)
        put("subprotocol", c.subprotocol)
        put("dnsMode", c.dnsMode.key)
        put("dnsServer", c.dnsServer)
        put("ipv6Mode", c.ipv6Mode.key)
        put("bypassLan", c.bypassLan)
        put("disallowedApps", JSONArray(c.disallowedApps.toList()))
      }
      .toString(2)
  }

  fun importConfigJson(rawJson: String): Result<Unit> {
    return try {
      val obj = JSONObject(rawJson.trim())
      val uuid = obj.getString("uuid").trim()
      val host = obj.getString("workerHost").trim()
      if (uuid.isEmpty() || host.isEmpty()) {
        return Result.failure(IllegalArgumentException("UUID یا Worker Host خالی است"))
      }
      val disallowed = mutableSetOf<String>()
      obj.optJSONArray("disallowedApps")?.let { arr ->
        for (i in 0 until arr.length()) {
          arr.optString(i).takeIf { it.isNotBlank() }?.let { disallowed.add(it) }
        }
      }
      val imported =
        TunnelConfig(
          uuid = uuid,
          workerHost = host,
          cleanIp = obj.optString("cleanIp", "").trim(),
          subprotocol = obj.optString("subprotocol", TunnelConfig.DEFAULT_SUBPROTOCOL).trim(),
          dnsMode = com.example.model.DnsMode.fromKey(obj.optString("dnsMode", null)),
          dnsServer = obj.optString("dnsServer", TunnelConfig.DEFAULT_DNS_SERVER).trim(),
          ipv6Mode = com.example.model.Ipv6Mode.fromKey(obj.optString("ipv6Mode", null)),
          bypassLan = obj.optBoolean("bypassLan", true),
          disallowedApps = disallowed,
        )
      saveConfig(imported)
      Result.success(Unit)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
