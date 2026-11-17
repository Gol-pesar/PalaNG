package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ActiveConnectionInfo
import com.example.model.TunnelConfig
import com.example.model.TunnelState
import com.example.model.TunnelStats
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanStream
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
  config: TunnelConfig,
  tunnelState: TunnelState,
  stats: TunnelStats,
  activeConnections: List<ActiveConnectionInfo>,
  probeStatus: ProbeStatus,
  onToggleVpn: () -> Unit,
  onRunProbe: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(tunnelState) {
    if (tunnelState is TunnelState.Connected) {
      while (true) {
        nowMs = System.currentTimeMillis()
        delay(1000L)
      }
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        HeroTunnelCard(
          config = config,
          tunnelState = tunnelState,
          nowMs = nowMs,
          onToggleVpn = onToggleVpn,
        )
      }

      item {
        ThroughputCard(stats = stats)
      }

      item {
        QuickMetricsRow(stats = stats)
      }

      item {
        ProtocolDiagnosticsCard(
          config = config,
          probeStatus = probeStatus,
          onRunProbe = onRunProbe,
        )
      }

      if (activeConnections.isNotEmpty()) {
        item {
          RecentConnectionsPreview(connections = activeConnections.take(4))
        }
      }

      item { Spacer(modifier = Modifier.height(8.dp)) }
    }
  }
}

@Composable
private fun HeroTunnelCard(
  config: TunnelConfig,
  tunnelState: TunnelState,
  nowMs: Long,
  onToggleVpn: () -> Unit,
) {
  val isConnected = tunnelState is TunnelState.Connected
  val isBusy = tunnelState is TunnelState.Connecting || tunnelState is TunnelState.Disconnecting
  val isError = tunnelState is TunnelState.Error

  val accentColor by
    animateColorAsState(
      targetValue =
        when {
          isConnected -> EmeraldPulse
          isBusy -> AmberWarn
          isError -> CoralError
          else -> CyanStream
        },
      animationSpec = tween(300),
      label = "heroAccent",
    )

  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseProgress by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (isConnected) 2200 else 1200, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "pulseRadius",
    )

  val statusText =
    when (tunnelState) {
      is TunnelState.Connected -> stringResource(R.string.status_connected)
      is TunnelState.Connecting -> stringResource(R.string.status_connecting)
      is TunnelState.Disconnecting -> stringResource(R.string.status_disconnecting)
      is TunnelState.Error -> "${stringResource(R.string.status_error)}: ${tunnelState.message}"
      TunnelState.Disconnected -> stringResource(R.string.status_disconnected)
    }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    border = BorderStroke(1.dp, accentColor.copy(alpha = if (isConnected) 0.55f else 0.25f)),
    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Image(
        painter = painterResource(id = R.drawable.img_tunnel_hero_1794908898148),
        contentDescription = "Tunnel background artwork",
        contentScale = ContentScale.Crop,
        alpha = if (isConnected) 0.42f else 0.22f,
        modifier = Modifier.matchParentSize(),
      )
      Box(
        modifier =
          Modifier.matchParentSize()
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    ObsidianBg.copy(alpha = 0.55f),
                    ObsidianCard.copy(alpha = 0.88f),
                    ObsidianCard.copy(alpha = 0.96f),
                  )
              )
            )
      )

      Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Filled.Shield,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "DirectTunnel",
              fontFamily = SpaceGroteskFontFamily,
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }

          Surface(
            color = accentColor.copy(alpha = 0.14f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
          ) {
            Text(
              text = if (isConnected) "PROTECTED" else if (isBusy) "SYNC" else "STANDBY",
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelSmall,
              color = accentColor,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // Interactive Power Orb with animated rings
        Box(
          modifier = Modifier.size(144.dp),
          contentAlignment = Alignment.Center,
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 48.dp.toPx()
            val maxExtra = 22.dp.toPx()

            if (isConnected || isBusy) {
              val r1 = baseRadius + maxExtra * pulseProgress
              val a1 = (1f - pulseProgress) * 0.45f
              drawCircle(
                color = accentColor.copy(alpha = a1),
                radius = r1,
                center = center,
                style = Stroke(width = 2.5.dp.toPx()),
              )
              val p2 = (pulseProgress + 0.5f) % 1f
              val r2 = baseRadius + maxExtra * p2
              val a2 = (1f - p2) * 0.35f
              drawCircle(
                color = accentColor.copy(alpha = a2),
                radius = r2,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
              )
            } else {
              drawCircle(
                color = ObsidianOutline.copy(alpha = 0.7f),
                radius = baseRadius + 10.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
              )
            }
          }

          Surface(
            modifier =
              Modifier.size(96.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .testTag("vpn_toggle_button")
                .clickable(enabled = !isBusy, onClick = onToggleVpn),
            shape = CircleShape,
            color = if (isConnected) accentColor else ObsidianCardElevated,
            border = BorderStroke(2.dp, accentColor),
            shadowElevation = if (isConnected) 12.dp else 4.dp,
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (isBusy) {
                CircularProgressIndicator(
                  modifier = Modifier.size(34.dp),
                  color = accentColor,
                  strokeWidth = 3.dp,
                )
              } else {
                Icon(
                  imageVector = Icons.Filled.PowerSettingsNew,
                  contentDescription =
                    if (isConnected) stringResource(R.string.action_disconnect)
                    else stringResource(R.string.action_connect),
                  tint = if (isConnected) ObsidianBg else accentColor,
                  modifier = Modifier.size(40.dp),
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
          text = statusText,
          style = MaterialTheme.typography.titleMedium,
          color = accentColor,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
          text =
            if (isConnected) stringResource(R.string.hero_subtitle_connected)
            else stringResource(R.string.hero_subtitle_disconnected),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = tunnelState is TunnelState.Connected) {
          val connected = tunnelState as? TunnelState.Connected
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
              color = ObsidianBg.copy(alpha = 0.7f),
              shape = RoundedCornerShape(12.dp),
              border = BorderStroke(1.dp, ObsidianOutline),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Text(
                  text = Formatters.formatDuration(connected?.connectedSinceMs ?: nowMs, nowMs),
                  fontFamily = JetBrainsMonoFontFamily,
                  style = MaterialTheme.typography.labelLarge,
                  color = EmeraldPulse,
                )
                Text(
                  text = "•",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = connected?.activeEndpoint ?: config.workerHost,
                  fontFamily = JetBrainsMonoFontFamily,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        if (tunnelState !is TunnelState.Connected) {
          Spacer(modifier = Modifier.height(12.dp))
          Surface(
            color = ObsidianBg.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.6f)),
          ) {
            Text(
              text =
                if (config.cleanIp.isNotBlank()) "${config.cleanIp}  ⇄  ${config.workerHost}"
                else config.workerHost,
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ThroughputCard(stats: TunnelStats) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.throughput_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "RX + TX",
          fontFamily = JetBrainsMonoFontFamily,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SpeedStatChip(
          icon = Icons.Filled.ArrowDownward,
          label = stringResource(R.string.download_label),
          speed = Formatters.formatSpeed(stats.rxSpeedBps),
          total = Formatters.formatBytes(stats.rxBytes),
          tint = EmeraldPulse,
          modifier = Modifier.weight(1f),
        )
        SpeedStatChip(
          icon = Icons.Filled.ArrowUpward,
          label = stringResource(R.string.upload_label),
          speed = Formatters.formatSpeed(stats.txSpeedBps),
          total = Formatters.formatBytes(stats.txBytes),
          tint = CyanStream,
          modifier = Modifier.weight(1f),
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Live dual-line sparkline Canvas
      ThroughputSparkline(
        rxHistory = stats.speedHistoryRx,
        txHistory = stats.speedHistoryTx,
        modifier = Modifier.fillMaxWidth().height(72.dp),
      )
    }
  }
}

@Composable
private fun SpeedStatChip(
  icon: ImageVector,
  label: String,
  speed: String,
  total: String,
  tint: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = ObsidianBg.copy(alpha = 0.6f),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.22f)),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = tint,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = label,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = speed,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        color = tint,
      )
      Text(
        text = "مجموع: $total",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ThroughputSparkline(
  rxHistory: List<Long>,
  txHistory: List<Long>,
  modifier: Modifier = Modifier,
) {
  Canvas(
    modifier =
      modifier
        .clip(RoundedCornerShape(12.dp))
        .background(ObsidianBg.copy(alpha = 0.5f))
  ) {
    val maxVal =
      maxOf(
        rxHistory.maxOrNull() ?: 0L,
        txHistory.maxOrNull() ?: 0L,
        1024L,
      ).toFloat()

    // Subtle horizontal grid lines
    for (step in 1..2) {
      val y = size.height * (step / 3f)
      drawLine(
        color = ObsidianOutline.copy(alpha = 0.4f),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
      )
    }

    fun drawSeries(values: List<Long>, color: Color, fillAlpha: Float) {
      if (values.size < 2) return
      val stepX = size.width / (values.size - 1).toFloat()
      val linePath = Path()
      val fillPath = Path()

      values.forEachIndexed { idx, v ->
        val x = idx * stepX
        val norm = (v.toFloat() / maxVal).coerceIn(0f, 1f)
        val y = size.height - (norm * (size.height - 8.dp.toPx())) - 4.dp.toPx()
        if (idx == 0) {
          linePath.moveTo(x, y)
          fillPath.moveTo(x, size.height)
          fillPath.lineTo(x, y)
        } else {
          linePath.lineTo(x, y)
          fillPath.lineTo(x, y)
        }
      }
      fillPath.lineTo(size.width, size.height)
      fillPath.close()

      drawPath(
        path = fillPath,
        brush =
          Brush.verticalGradient(
            colors = listOf(color.copy(alpha = fillAlpha), Color.Transparent),
          ),
      )
      drawPath(
        path = linePath,
        color = color,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
      )
    }

    drawSeries(txHistory, CyanStream, 0.14f)
    drawSeries(rxHistory, EmeraldPulse, 0.24f)
  }
}

@Composable
private fun QuickMetricsRow(stats: TunnelStats) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    MiniMetricCard(
      icon = Icons.Filled.Hub,
      label = stringResource(R.string.active_sessions_label),
      value = stats.activeConnections.toString(),
      accent = EmeraldPulse,
      modifier = Modifier.weight(1f),
    )
    MiniMetricCard(
      icon = Icons.Filled.Dns,
      label = stringResource(R.string.dns_queries_label),
      value = stats.dnsQueries.toString(),
      accent = CyanStream,
      modifier = Modifier.weight(1f),
    )
    MiniMetricCard(
      icon = Icons.Filled.Speed,
      label = stringResource(R.string.total_conns_label),
      value = stats.totalConnections.toString(),
      accent = AmberWarn,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun MiniMetricCard(
  icon: ImageVector,
  label: String,
  value: String,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = accent,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ProtocolDiagnosticsCard(
  config: TunnelConfig,
  probeStatus: ProbeStatus,
  onRunProbe: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.diagnostic_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = stringResource(R.string.diagnostic_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Button(
          onClick = onRunProbe,
          enabled = probeStatus !is ProbeStatus.Running,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("handshake_test_button"),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = CyanStream.copy(alpha = 0.16f),
              contentColor = CyanStream,
            ),
          border = BorderStroke(1.dp, CyanStream.copy(alpha = 0.4f)),
        ) {
          if (probeStatus is ProbeStatus.Running) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              color = CyanStream,
              strokeWidth = 2.dp,
            )
          } else {
            Icon(
              imageVector = Icons.Filled.Bolt,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = stringResource(R.string.diagnostic_run))
          }
        }
      }

      if (probeStatus !is ProbeStatus.Idle) {
        Spacer(modifier = Modifier.height(10.dp))
        val (badgeColor, msg) =
          when (probeStatus) {
            is ProbeStatus.Running -> AmberWarn to "در حال ارسال پکت رمزنگاری‌شده به ${config.workerHost}..."
            is ProbeStatus.Success ->
              EmeraldPulse to "تأییدیه HMAC-SHA256 OK دریافت شد (${probeStatus.rttMs} ms)"
            is ProbeStatus.Failure -> CoralError to "ناموفق: ${probeStatus.error}"
            ProbeStatus.Idle -> CyanStream to ""
          }
        Surface(
          color = badgeColor.copy(alpha = 0.12f),
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
        ) {
          Text(
            text = msg,
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun RecentConnectionsPreview(connections: List<ActiveConnectionInfo>) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(
        text = "نشست‌های فعال اخیر",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(10.dp))
      connections.forEach { conn ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "${conn.destination}:${conn.port}",
              fontFamily = JetBrainsMonoFontFamily,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = conn.state,
              style = MaterialTheme.typography.labelSmall,
              color = EmeraldPulse,
            )
          }
          Text(
            text = "↓${Formatters.formatBytes(conn.rxBytes)}  ↑${Formatters.formatBytes(conn.txBytes)}",
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
