package com.example.data

import com.example.model.ActiveConnectionInfo
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.TunnelState
import com.example.model.TunnelStats
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TunnelStateHolder {
  private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
  val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

  private val _stats = MutableStateFlow(TunnelStats())
  val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

  private val _activeConnections = MutableStateFlow<List<ActiveConnectionInfo>>(emptyList())
  val activeConnections: StateFlow<List<ActiveConnectionInfo>> = _activeConnections.asStateFlow()

  private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
  val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

  private val nextLogId = AtomicLong(1L)

  fun setState(state: TunnelState) {
    _tunnelState.value = state
    if (state is TunnelState.Disconnected) {
      _activeConnections.value = emptyList()
      _stats.update { it.copy(rxSpeedBps = 0L, txSpeedBps = 0L, activeConnections = 0) }
    }
  }

  fun updateStats(stats: TunnelStats, connections: List<ActiveConnectionInfo>) {
    _stats.value = stats
    _activeConnections.value = connections
  }

  fun appendLog(level: LogLevel, message: String) {
    val entry =
      LogEntry(
        id = nextLogId.getAndIncrement(),
        timestampMs = System.currentTimeMillis(),
        level = level,
        message = message,
      )
    _logs.update { current ->
      val updated = current + entry
      if (updated.size > 250) updated.takeLast(250) else updated
    }
  }

  fun clearLogs() {
    _logs.value = emptyList()
  }

  fun resetSessionStats() {
    _stats.value = TunnelStats()
    _activeConnections.value = emptyList()
  }
}
