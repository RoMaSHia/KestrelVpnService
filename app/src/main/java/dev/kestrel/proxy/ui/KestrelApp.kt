package dev.kestrel.proxy.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kestrel.proxy.ui.screens.DashboardScreen
import dev.kestrel.proxy.ui.screens.LogsScreen
import dev.kestrel.proxy.ui.screens.ProxiesScreen
import dev.kestrel.proxy.ui.screens.RoutesScreen
import dev.kestrel.proxy.ui.theme.KestrelTheme

private enum class TopTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DASHBOARD("dashboard", "Главная", Icons.Rounded.Home),
    PROXIES("proxies", "Прокси", Icons.Rounded.Public),
    ROUTES("routes", "Маршруты", Icons.Rounded.AltRoute),
    LOGS("logs", "Логи", Icons.Rounded.ReceiptLong),
}

@Composable
fun KestrelApp(
    viewModel: KestrelViewModel,
    onRequestConnect: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    KestrelTheme(
        themeMode = uiState.settings.themeMode,
        dynamicColor = uiState.settings.dynamicColor,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    TopTab.entries.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTab(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = TopTab.DASHBOARD.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                composable(TopTab.DASHBOARD.route) {
                    DashboardScreen(
                        uiState = uiState,
                        onConnectClick = onRequestConnect,
                        onDisconnectClick = viewModel::stopVpn,
                        onDismissError = viewModel::dismissError,
                        onSelectProxy = viewModel::selectProxy,
                        onPingProxy = viewModel::pingProfile,
                        onOpenProxiesTab = { navigateToTab(TopTab.PROXIES.route) },
                        onOpenRoutesTab = { navigateToTab(TopTab.ROUTES.route) },
                        onToggleBypassLan = viewModel::setBypassLan,
                        onToggleCustomDns = viewModel::setUseCustomDns,
                        onSaveDns = viewModel::setDnsServers,
                        onChangeTheme = viewModel::setThemeMode,
                        onChangeDynamicColor = viewModel::setDynamicColor,
                    )
                }
                composable(TopTab.PROXIES.route) {
                    ProxiesScreen(
                        uiState = uiState,
                        onSelectProxy = viewModel::selectProxy,
                        onSaveProxy = viewModel::saveProxy,
                        onDeleteProxy = viewModel::deleteProxy,
                        onImportUri = viewModel::importProxyFromText,
                        onPingProxy = viewModel::pingProfile,
                        onPingAll = viewModel::pingAllProfiles,
                    )
                }
                composable(TopTab.ROUTES.route) {
                    RoutesScreen(
                        uiState = uiState,
                        onSetAppMode = viewModel::setAppRoutingMode,
                        onTogglePackage = viewModel::togglePackageSelected,
                        onClearSelectedPackages = viewModel::clearSelectedPackages,
                        onApplyAppChanges = viewModel::applyPendingAppChanges,
                        onSaveRule = viewModel::saveRule,
                        onDeleteRule = viewModel::deleteRule,
                        onToggleRule = viewModel::toggleRule,
                        onMoveRule = viewModel::moveRule,
                        onSetDefaultAction = viewModel::setDefaultAction,
                        onImportRules = viewModel::importRulesContent,
                    )
                }
                composable(TopTab.LOGS.route) {
                    LogsScreen(
                        uiState = uiState,
                        onTogglePaused = viewModel::toggleLogsPaused,
                        onClearLogs = viewModel::clearLogs,
                    )
                }
            }
        }
    }
}
