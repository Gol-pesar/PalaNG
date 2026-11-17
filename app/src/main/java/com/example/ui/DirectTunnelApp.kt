package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.model.TunnelState
import com.example.service.TunnelVpnService
import com.example.ui.theme.EmeraldPulse
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_CONFIG = "config"
const val ROUTE_LOGS = "logs"

private data class NavDestination(
  val route: String,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

private val destinations =
  listOf(
    NavDestination(
      route = ROUTE_DASHBOARD,
      labelRes = R.string.nav_dashboard,
      selectedIcon = Icons.Filled.Shield,
      unselectedIcon = Icons.Outlined.Shield,
      testTag = "nav_dashboard",
    ),
    NavDestination(
      route = ROUTE_CONFIG,
      labelRes = R.string.nav_config,
      selectedIcon = Icons.Filled.Tune,
      unselectedIcon = Icons.Outlined.Tune,
      testTag = "nav_config",
    ),
    NavDestination(
      route = ROUTE_LOGS,
      labelRes = R.string.nav_logs,
      selectedIcon = Icons.Filled.Terminal,
      unselectedIcon = Icons.Outlined.Terminal,
      testTag = "nav_logs",
    ),
  )

@Composable
fun DirectTunnelApp(viewModel: MainViewModel = viewModel()) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val config by viewModel.config.collectAsStateWithLifecycle()
  val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
  val stats by viewModel.stats.collectAsStateWithLifecycle()
  val activeConnections by viewModel.activeConnections.collectAsStateWithLifecycle()
  val logs by viewModel.logs.collectAsStateWithLifecycle()
  val probeStatus by viewModel.probeStatus.collectAsStateWithLifecycle()
  val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
  val appsLoading by viewModel.appsLoading.collectAsStateWithLifecycle()

  val vpnConsentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
      if (res.resultCode == Activity.RESULT_OK) {
        TunnelVpnService.start(context)
      } else {
        scope.launch {
          snackbarHostState.showSnackbar("اجازه دسترسی VPN برای راه‌اندازی تانل ضروری است")
        }
      }
    }

  val startVpnFlow: () -> Unit = {
    val prepareIntent = VpnService.prepare(context)
    if (prepareIntent != null) {
      vpnConsentLauncher.launch(prepareIntent)
    } else {
      TunnelVpnService.start(context)
    }
  }

  val notifPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      startVpnFlow()
    }

  val onToggleVpn: () -> Unit = {
    if (tunnelState is TunnelState.Connected || tunnelState is TunnelState.Connecting) {
      TunnelVpnService.stop(context)
    } else {
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
      ) {
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        startVpnFlow()
      }
    }
  }

  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route ?: ROUTE_DASHBOARD

  val navigateTo: (String) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useRail = maxWidth >= 600.dp

      Scaffold(
        containerColor = ObsidianBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
          if (!useRail) {
            NavigationBar(
              containerColor = ObsidianSurface,
              windowInsets = WindowInsets.navigationBars,
            ) {
              destinations.forEach { dest ->
                val selected = currentRoute == dest.route
                val label = stringResource(dest.labelRes)
                NavigationBarItem(
                  selected = selected,
                  onClick = { navigateTo(dest.route) },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = label,
                    )
                  },
                  label = { Text(label) },
                  modifier = Modifier.testTag(dest.testTag),
                  colors =
                    NavigationBarItemDefaults.colors(
                      selectedIconColor = EmeraldPulse,
                      selectedTextColor = EmeraldPulse,
                      indicatorColor = EmeraldPulse.copy(alpha = 0.16f),
                      unselectedIconColor = TextSecondary,
                      unselectedTextColor = TextSecondary,
                    ),
                )
              }
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useRail) {
            NavigationRail(
              containerColor = ObsidianSurface,
              modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            ) {
              destinations.forEach { dest ->
                val selected = currentRoute == dest.route
                val label = stringResource(dest.labelRes)
                NavigationRailItem(
                  selected = selected,
                  onClick = { navigateTo(dest.route) },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = label,
                    )
                  },
                  label = { Text(label) },
                  modifier = Modifier.testTag(dest.testTag),
                  colors =
                    NavigationRailItemDefaults.colors(
                      selectedIconColor = EmeraldPulse,
                      selectedTextColor = EmeraldPulse,
                      indicatorColor = ObsidianCard,
                      unselectedIconColor = TextSecondary,
                      unselectedTextColor = TextSecondary,
                    ),
                )
              }
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
              composable(ROUTE_DASHBOARD) {
                DashboardScreen(
                  config = config,
                  tunnelState = tunnelState,
                  stats = stats,
                  activeConnections = activeConnections,
                  probeStatus = probeStatus,
                  onToggleVpn = onToggleVpn,
                  onRunProbe = viewModel::runHandshakeProbe,
                )
              }
              composable(ROUTE_CONFIG) {
                ConfigScreen(
                  config = config,
                  installedApps = installedApps,
                  appsLoading = appsLoading,
                  isTunnelActive = tunnelState is TunnelState.Connected,
                  onSaveConfig = viewModel::saveConfig,
                  onResetConfig = viewModel::resetConfig,
                  onLoadApps = viewModel::loadInstalledApps,
                  onToggleDisallowedApp = viewModel::toggleDisallowedApp,
                  onExportJson = viewModel::exportConfigJson,
                  onImportJson = viewModel::importConfigJson,
                  onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
              }
              composable(ROUTE_LOGS) {
                LogsScreen(
                  activeConnections = activeConnections,
                  logs = logs,
                  onClearLogs = viewModel::clearLogs,
                  onShowMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
              }
            }
          }
        }
      }
    }
  }
}
