package com.example.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DnsMode
import com.example.model.InstalledAppInfo
import com.example.model.Ipv6Mode
import com.example.model.TunnelConfig
import com.example.ui.theme.CyanStream
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianOutline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
  config: TunnelConfig,
  installedApps: List<InstalledAppInfo>,
  appsLoading: Boolean,
  isTunnelActive: Boolean,
  onSaveConfig: (TunnelConfig) -> Unit,
  onResetConfig: () -> Unit,
  onLoadApps: () -> Unit,
  onToggleDisallowedApp: (String) -> Unit,
  onExportJson: () -> String,
  onImportJson: (String) -> Result<Unit>,
  onShowMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var uuid by remember(config.uuid) { mutableStateOf(config.uuid) }
  var workerHost by remember(config.workerHost) { mutableStateOf(config.workerHost) }
  var cleanIp by remember(config.cleanIp) { mutableStateOf(config.cleanIp) }
  var subprotocol by remember(config.subprotocol) { mutableStateOf(config.subprotocol) }
  var dnsMode by remember(config.dnsMode) { mutableStateOf(config.dnsMode) }
  var dnsServer by remember(config.dnsServer) { mutableStateOf(config.dnsServer) }
  var ipv6Mode by remember(config.ipv6Mode) { mutableStateOf(config.ipv6Mode) }
  var bypassLan by remember(config.bypassLan) { mutableStateOf(config.bypassLan) }

  var showAppsSheet by remember { mutableStateOf(false) }
  var showImportDialog by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (isTunnelActive) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = CyanStream.copy(alpha = 0.12f)),
            border = BorderStroke(1.dp, CyanStream.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(14.dp),
          ) {
            Text(
              text = "برای اعمال تغییرات جدید در تانل فعال، پس از ذخیره یک‌بار اتصال VPN را قطع و وصل کنید.",
              style = MaterialTheme.typography.bodyMedium,
              color = CyanStream,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }

      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = ObsidianCard),
          border = BorderStroke(1.dp, ObsidianOutline),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.config_protocol_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            MonoConfigField(
              value = uuid,
              onValueChange = { uuid = it },
              label = "UUID",
              testTag = "input_uuid",
            )
            MonoConfigField(
              value = workerHost,
              onValueChange = { workerHost = it },
              label = "Worker Host",
              testTag = "input_worker_host",
            )
            MonoConfigField(
              value = cleanIp,
              onValueChange = { cleanIp = it },
              label = "Clean IP (اختیاری)",
              testTag = "input_clean_ip",
            )
            MonoConfigField(
              value = subprotocol,
              onValueChange = { subprotocol = it },
              label = "WebSocket Subprotocol",
              testTag = "input_subproto",
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Button(
                onClick = {
                  onSaveConfig(
                    config.copy(
                      uuid = uuid.trim(),
                      workerHost = workerHost.trim(),
                      cleanIp = cleanIp.trim(),
                      subprotocol = subprotocol.trim(),
                      dnsMode = dnsMode,
                      dnsServer = dnsServer.trim(),
                      ipv6Mode = ipv6Mode,
                      bypassLan = bypassLan,
                    )
                  )
                  onShowMessage("تنظیمات با موفقیت ذخیره شد")
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_config_button"),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPulse, contentColor = ObsidianBg),
              ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.save_changes))
              }

              OutlinedButton(
                onClick = {
                  onResetConfig()
                  onShowMessage("تنظیمات به مقادیر پیش‌فرض بازنشانی شد")
                },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("reset_config_button"),
                border = BorderStroke(1.dp, ObsidianOutline),
              ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.reset_defaults))
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              OutlinedButton(
                onClick = {
                  clipboard.setText(AnnotatedString(onExportJson()))
                  onShowMessage("پیکربندی JSON در کلیپ‌بورد کپی شد")
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                border = BorderStroke(1.dp, ObsidianOutline),
              ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("کپی JSON")
              }
              OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                border = BorderStroke(1.dp, ObsidianOutline),
              ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ورود JSON")
              }
            }
          }
        }
      }

      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = ObsidianCard),
          border = BorderStroke(1.dp, ObsidianOutline),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.config_routing_title),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
              text = "حالت حل‌کننده DNS درون تانل",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = dnsMode == DnsMode.DOH_OVER_TUNNEL,
                onClick = {
                  dnsMode = DnsMode.DOH_OVER_TUNNEL
                  onSaveConfig(config.copy(dnsMode = DnsMode.DOH_OVER_TUNNEL))
                },
                label = { Text("DoH over Tunnel") },
                leadingIcon =
                  if (dnsMode == DnsMode.DOH_OVER_TUNNEL) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                  } else null,
              )
              FilterChip(
                selected = dnsMode == DnsMode.TCP_OVER_TUNNEL,
                onClick = {
                  dnsMode = DnsMode.TCP_OVER_TUNNEL
                  onSaveConfig(config.copy(dnsMode = DnsMode.TCP_OVER_TUNNEL))
                },
                label = { Text("DNS TCP :53") },
                leadingIcon =
                  if (dnsMode == DnsMode.TCP_OVER_TUNNEL) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                  } else null,
              )
            }

            if (dnsMode == DnsMode.TCP_OVER_TUNNEL) {
              MonoConfigField(
                value = dnsServer,
                onValueChange = { dnsServer = it },
                label = "سرور DNS TCP",
                testTag = "input_dns_server",
              )
            }

            Text(
              text = "مدیریت IPv6",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = ipv6Mode == Ipv6Mode.FORCE_IPV4_NO_LEAK,
                onClick = {
                  ipv6Mode = Ipv6Mode.FORCE_IPV4_NO_LEAK
                  onSaveConfig(config.copy(ipv6Mode = Ipv6Mode.FORCE_IPV4_NO_LEAK))
                },
                label = { Text("Force IPv4 (بدون نشت)") },
              )
              FilterChip(
                selected = ipv6Mode == Ipv6Mode.TUNNEL_DUAL_STACK,
                onClick = {
                  ipv6Mode = Ipv6Mode.TUNNEL_DUAL_STACK
                  onSaveConfig(config.copy(ipv6Mode = Ipv6Mode.TUNNEL_DUAL_STACK))
                },
                label = { Text("Dual-Stack IPv6") },
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "عبور مستقیم شبکه محلی (Bypass LAN)",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "دسترسی به روتر و دستگاه‌های شبکه داخلی بدون ورود به تانل",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Switch(
                checked = bypassLan,
                onCheckedChange = { checked ->
                  bypassLan = checked
                  onSaveConfig(config.copy(bypassLan = checked))
                },
              )
            }
          }
        }
      }

      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = ObsidianCard),
          border = BorderStroke(1.dp, ObsidianOutline),
          modifier =
            Modifier.fillMaxWidth().clickable {
              onLoadApps()
              showAppsSheet = true
            },
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = null,
                tint = CyanStream,
                modifier = Modifier.size(24.dp),
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text(
                  text = stringResource(R.string.config_split_tunnel_title),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text =
                    if (config.disallowedApps.isEmpty()) "همه برنامه‌ها از تانل عبور می‌کنند"
                    else "${config.disallowedApps.size} برنامه مستثنا شده است",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }

  if (showAppsSheet) {
    LaunchedEffect(Unit) { onLoadApps() }
    var search by remember { mutableStateOf("") }
    ModalBottomSheet(
      onDismissRequest = { showAppsSheet = false },
      containerColor = ObsidianCard,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = "انتخاب برنامه‌های خارج از تانل",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
          value = search,
          onValueChange = { search = it },
          placeholder = { Text("جستجوی نام برنامه...") },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        if (appsLoading) {
          Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EmeraldPulse)
          }
        } else {
          val filtered =
            remember(installedApps, search) {
              if (search.isBlank()) installedApps
              else installedApps.filter {
                it.label.contains(search, ignoreCase = true) ||
                  it.packageName.contains(search, ignoreCase = true)
              }
            }
          LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
            items(filtered, key = { it.packageName }) { app ->
              val excluded = app.packageName in config.disallowedApps
              Row(
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { onToggleDisallowedApp(app.packageName) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = excluded,
                  onCheckedChange = { onToggleDisallowedApp(app.packageName) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    text = app.packageName,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }

  if (showImportDialog) {
    var jsonText by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showImportDialog = false },
      containerColor = ObsidianCard,
      title = { Text("ورود پیکربندی JSON") },
      text = {
        OutlinedTextField(
          value = jsonText,
          onValueChange = { jsonText = it },
          placeholder = { Text("{\n  \"uuid\": \"...\",\n  \"workerHost\": \"...\"\n}") },
          minLines = 5,
          maxLines = 10,
          textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily),
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        Button(
          onClick = {
            onImportJson(jsonText)
              .onSuccess {
                showImportDialog = false
                onShowMessage("پیکربندی وارد و ذخیره شد")
              }
              .onFailure { err -> onShowMessage("خطا در JSON: ${err.message}") }
          }
        ) {
          Text("اعمال")
        }
      },
      dismissButton = {
        TextButton(onClick = { showImportDialog = false }) { Text("انصراف") }
      },
    )
  }
}

@Composable
private fun MonoConfigField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  testTag: String,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    singleLine = true,
    textStyle = TextStyle(fontFamily = JetBrainsMonoFontFamily),
    modifier = Modifier.fillMaxWidth().testTag(testTag),
  )
}
