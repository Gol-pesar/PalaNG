package com.example.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {
  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
  }

  fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

  fun formatDuration(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val totalSec = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
  }

  fun formatTime(timestampMs: Long): String = timeFormat.format(Date(timestampMs))
}
