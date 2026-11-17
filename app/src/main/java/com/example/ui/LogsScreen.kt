package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ActiveConnectionInfo
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanStream
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianOutline

@Composable
fun LogsScreen(
  activeConnections: List<ActiveConnectionInfo>,
  logs: List<LogEntry>,
  onClearLogs: () -> Unit,
  onShowMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  val clipboard = LocalClipboardManager.current

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
      modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          icon = { Icon(Icons.Filled.Lan, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
          Text("${stringResource(R.string.tab_active_connections)} (${activeConnections.size})")
        }
        SegmentedButton(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          icon = { Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp)) },
        ) {
          Text("${stringResource(R.string.tab_engine_logs)} (${logs.size})")
        }
      }

      if (selectedTab == 0) {
        if (activeConnections.isEmpty()) {
          EmptyStateBox(
            icon = Icons.Filled.Lan,
            title = "هیچ نشست فعالی وجود ندارد",
            subtitle = "پس از فعال‌سازی تانل، تمام اتصالات TCP گوشی در اینجا نمایش داده می‌شوند.",
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
          ) {
            items(activeConnections, key = { it.id }) { conn ->
              Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                border = BorderStroke(1.dp, ObsidianOutline),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${conn.destination}:${conn.port}",
                      fontFamily = JetBrainsMonoFontFamily,
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Surface(
                        color = EmeraldPulse.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp),
                      ) {
                        Text(
                          text = conn.state,
                          fontFamily = JetBrainsMonoFontFamily,
                          style = MaterialTheme.typography.labelSmall,
                          color = EmeraldPulse,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                      Text(
                        text = Formatters.formatTime(conn.startedAtMs),
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                  Spacer(modifier = Modifier.width(8.dp))
                  Column(horizontalAlignment = Alignment.End) {
                    Text(
                      text = "↓ ${Formatters.formatBytes(conn.rxBytes)}",
                      fontFamily = JetBrainsMonoFontFamily,
                      style = MaterialTheme.typography.labelSmall,
                      color = EmeraldPulse,
                    )
                    Text(
                      text = "↑ ${Formatters.formatBytes(conn.txBytes)}",
                      fontFamily = JetBrainsMonoFontFamily,
                      style = MaterialTheme.typography.labelSmall,
                      color = CyanStream,
                    )
                  }
                }
              }
            }
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedButton(
            onClick = {
              val text =
                logs.joinToString("\n") {
                  "[${Formatters.formatTime(it.timestampMs)}] [${it.level}] ${it.message}"
                }
              clipboard.setText(AnnotatedString(text))
              onShowMessage("لاگ‌ها کپی شدند")
            },
            enabled = logs.isNotEmpty(),
            modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
            border = BorderStroke(1.dp, ObsidianOutline),
          ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.copy_logs))
          }
          OutlinedButton(
            onClick = onClearLogs,
            enabled = logs.isNotEmpty(),
            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("clear_logs_button"),
            border = BorderStroke(1.dp, ObsidianOutline),
          ) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.clear_logs))
          }
        }

        if (logs.isEmpty()) {
          EmptyStateBox(
            icon = Icons.Filled.Terminal,
            title = "لاگی ثبت نشده است",
            subtitle = "رویدادهای دست‌دهی، DNS و وضعیت موتور تانل در اینجا ثبت می‌شوند.",
          )
        } else {
          Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ObsidianBg),
            border = BorderStroke(1.dp, ObsidianOutline),
          ) {
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(12.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(logs.asReversed(), key = { it.id }) { entry ->
                val levelColor =
                  when (entry.level) {
                    LogLevel.INFO -> EmeraldPulse
                    LogLevel.WARN -> AmberWarn
                    LogLevel.ERROR -> CoralError
                  }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = Formatters.formatTime(entry.timestampMs),
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = entry.level.name,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor,
                  )
                  Text(
                    text = entry.message,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyStateBox(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(24.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.size(48.dp),
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
