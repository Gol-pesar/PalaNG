package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DnsMode
import com.example.model.InstalledAppInfo
import com.example.model.Ipv6Mode
import com.example.model.TunnelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.tunnelDataStore: DataStore<Preferences> by preferencesDataStore(name = "direct_tunnel_prefs")

class ConfigRepository(private val context: Context) {

  private object Keys {
    val UUID = stringPreferencesKey("uuid")
    val WORKER_HOST = stringPreferencesKey("worker_host")
    val CLEAN_IP = stringPreferencesKey("clean_ip")
    val SUBPROTOCOL = stringPreferencesKey("subprotocol")
    val DNS_MODE = stringPreferencesKey("dns_mode")
    val DNS_SERVER = stringPreferencesKey("dns_server")
    val IPV6_MODE = stringPreferencesKey("ipv6_mode")
    val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
    val DISALLOWED_APPS = stringSetPreferencesKey("disallowed_apps")
  }

  val configFlow: Flow<TunnelConfig> =
    context.tunnelDataStore.data.map { prefs ->
      TunnelConfig(
        uuid = prefs[Keys.UUID] ?: TunnelConfig.DEFAULT_UUID,
        workerHost = prefs[Keys.WORKER_HOST] ?: TunnelConfig.DEFAULT_WORKER_HOST,
        cleanIp = prefs[Keys.CLEAN_IP] ?: TunnelConfig.DEFAULT_CLEAN_IP,
        subprotocol = prefs[Keys.SUBPROTOCOL] ?: TunnelConfig.DEFAULT_SUBPROTOCOL,
        dnsMode = DnsMode.fromKey(prefs[Keys.DNS_MODE]),
        dnsServer = prefs[Keys.DNS_SERVER] ?: TunnelConfig.DEFAULT_DNS_SERVER,
        ipv6Mode = Ipv6Mode.fromKey(prefs[Keys.IPV6_MODE]),
        bypassLan = prefs[Keys.BYPASS_LAN] ?: true,
        disallowedApps = prefs[Keys.DISALLOWED_APPS] ?: emptySet(),
      )
    }

  suspend fun getCurrentConfig(): TunnelConfig = configFlow.first()

  suspend fun saveConfig(config: TunnelConfig) {
    context.tunnelDataStore.edit { prefs ->
      prefs[Keys.UUID] = config.uuid.trim()
      prefs[Keys.WORKER_HOST] = config.workerHost.trim()
      prefs[Keys.CLEAN_IP] = config.cleanIp.trim()
      prefs[Keys.SUBPROTOCOL] = config.subprotocol.trim()
      prefs[Keys.DNS_MODE] = config.dnsMode.key
      prefs[Keys.DNS_SERVER] = config.dnsServer.trim().ifEmpty { TunnelConfig.DEFAULT_DNS_SERVER }
      prefs[Keys.IPV6_MODE] = config.ipv6Mode.key
      prefs[Keys.BYPASS_LAN] = config.bypassLan
      prefs[Keys.DISALLOWED_APPS] = config.disallowedApps
    }
  }

  suspend fun resetToDefault() {
    saveConfig(TunnelConfig())
  }
}

object InstalledAppsProvider {
  suspend fun loadLaunchableApps(context: Context): List<InstalledAppInfo> =
    withContext(Dispatchers.IO) {
      val pm = context.packageManager
      val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
      val ownPkg = context.packageName
      resolved
        .mapNotNull { info ->
          val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
          if (pkg == ownPkg) return@mapNotNull null
          val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
          InstalledAppInfo(packageName = pkg, label = label)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
    }
}
