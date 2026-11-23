package dev.kestrel.proxy.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.ThemeMode
import dev.kestrel.proxy.data.model.VpnStatus
import dev.kestrel.proxy.ui.KestrelUiState
import dev.kestrel.proxy.ui.components.RoutingHelpBottomSheet
import dev.kestrel.proxy.ui.components.formatBytes
import dev.kestrel.proxy.ui.components.formatSpeed
import dev.kestrel.proxy.ui.components.formatUptime
import dev.kestrel.proxy.ui.theme.KestrelColors

@Composable
fun DashboardScreen(
    uiState: KestrelUiState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDismissError: () -> Unit,
    onSelectProxy: (String) -> Unit,
    onPingProxy: (ProxyProfile) -> Unit,
    onOpenProxiesTab: () -> Unit,
    onOpenRoutesTab: () -> Unit,
    onToggleBypassLan: (Boolean) -> Unit,
    onToggleCustomDns: (Boolean) -> Unit,
    onSaveDns: (String, String, Boolean) -> Unit,
    onChangeTheme: (ThemeMode) -> Unit,
    onChangeDynamicColor: (Boolean) -> Unit,
    onToggleIpv6: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showHelpSheet by remember { mutableStateOf(false) }
    var showProxyPicker by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Kestrel",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "Умный прокси и маршрутизация",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { showHelpSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = "Справка по маршрутизации",
                    )
                }
                IconButton(onClick = { showSettingsSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Настройки темы и DNS",
                    )
                }
            }
        }

        // Error banner
        AnimatedVisibility(visible = uiState.vpnError != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = uiState.vpnError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрыть",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // Expressive central connection hero
        ConnectionHeroCard(
            status = uiState.vpnStatus,
            uptimeMs = uiState.stats.uptimeMs,
            activeConnections = uiState.stats.activeConnections,
            onToggle = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                when (uiState.vpnStatus) {
                    VpnStatus.DISCONNECTED -> onConnectClick()
                    VpnStatus.CONNECTING, VpnStatus.CONNECTED -> onDisconnectClick()
                }
            },
        )

        // Speed & Session traffic
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrafficStatCard(
                title = "Загрузка",
                icon = Icons.Rounded.ArrowDownward,
                speed = formatSpeed(uiState.stats.rxSpeedBps),
                total = formatBytes(uiState.stats.rxBytes),
                accentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TrafficStatCard(
                title = "Отдача",
                icon = Icons.Rounded.ArrowUpward,
                speed = formatSpeed(uiState.stats.txSpeedBps),
                total = formatBytes(uiState.stats.txBytes),
                accentColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }

        // Active proxy card
        ActiveProxyCard(
            profile = uiState.activeProfile,
            onClick = {
                if (uiState.profiles.isEmpty()) {
                    onOpenProxiesTab()
                } else {
                    showProxyPicker = true
                }
            },
            onPingClick = {
                uiState.activeProfile?.let(onPingProxy)
            },
        )

        // Routing summary card
        RoutingSummaryCard(
            uiState = uiState,
            onClick = onOpenRoutesTab,
        )

        // One-click preset toggles
        Text(
            text = "Быстрые пресеты",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        PresetToggleCard(
            icon = Icons.Rounded.Lan,
            title = "Обходить локальную сеть и Wi-Fi роутер",
            subtitle = "Трафик к роутеру (192.168.x.x), принтерам и локальным адресам идёт напрямую без прокси.",
            checked = uiState.settings.bypassLan,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggleBypassLan(it)
            },
        )

        PresetToggleCard(
            icon = Icons.Rounded.Dns,
            title = "Использовать безопасный DNS (${uiState.settings.dnsUpstream})",
            subtitle = "Перехватывает DNS-запросы внутри туннеля и защищает от утечек DNS провайдеру.",
            checked = uiState.settings.useCustomDns,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggleCustomDns(it)
            },
        )

        // Battery optimization tip
        if (!uiState.isBatteryOptimizationIgnored) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isSystemInDarkTheme()) {
                    KestrelColors.WarningAmberContainerDark
                } else {
                    KestrelColors.WarningAmberContainerLight
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BatteryAlert,
                        contentDescription = null,
                        tint = if (isSystemInDarkTheme()) {
                            KestrelColors.WarningAmberOnDark
                        } else {
                            KestrelColors.WarningAmber
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Оптимизация батареи активна",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Система может выгружать VPN в фоне. Разрешите работу без ограничений.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                    ) {
                        Text("Настроить")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showHelpSheet) {
        RoutingHelpBottomSheet(onDismiss = { showHelpSheet = false })
    }

    if (showProxyPicker) {
        ProxyQuickPickerSheet(
            profiles = uiState.profiles,
            activeId = uiState.settings.activeProfileId,
            onSelect = { id ->
                onSelectProxy(id)
                showProxyPicker = false
            },
            onManageProxies = {
                showProxyPicker = false
                onOpenProxiesTab()
            },
            onDismiss = { showProxyPicker = false },
        )
    }

    if (showSettingsSheet) {
        AppearanceAndDnsSheet(
            uiState = uiState,
            onChangeTheme = onChangeTheme,
            onChangeDynamicColor = onChangeDynamicColor,
            onToggleIpv6 = onToggleIpv6,
            onSaveDns = onSaveDns,
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun ConnectionHeroCard(
    status: VpnStatus,
    uptimeMs: Long,
    activeConnections: Long,
    onToggle: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vpnPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val buttonColor by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            VpnStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(350),
        label = "buttonColor",
    )

    val iconTint by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimary
            VpnStatus.CONNECTING -> MaterialTheme.colorScheme.onTertiary
            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(350),
        label = "iconTint",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(144.dp),
            ) {
                if (status != VpnStatus.DISCONNECTED) {
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(buttonColor.copy(alpha = 0.22f)),
                    )
                }

                Surface(
                    onClick = onToggle,
                    modifier = Modifier.size(112.dp),
                    shape = CircleShape,
                    color = buttonColor,
                    tonalElevation = 6.dp,
                    shadowElevation = if (status == VpnStatus.CONNECTED) 10.dp else 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = if (status == VpnStatus.DISCONNECTED) {
                                "Подключить VPN"
                            } else {
                                "Отключить VPN"
                            },
                            tint = iconTint,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = status,
                label = "vpnStatusText",
            ) { currentStatus ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = when (currentStatus) {
                            VpnStatus.DISCONNECTED -> "Отключено"
                            VpnStatus.CONNECTING -> "Подключение…"
                            VpnStatus.CONNECTED -> "Подключено"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = when (currentStatus) {
                            VpnStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            VpnStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                            VpnStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = when (currentStatus) {
                            VpnStatus.DISCONNECTED -> "Нажмите, чтобы запустить туннель"
                            VpnStatus.CONNECTING -> "Инициализация сетевого ядра Rust…"
                            VpnStatus.CONNECTED -> "Сессия ${formatUptime(uptimeMs)} • Активных соединений: $activeConnections"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficStatCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    speed: String,
    total: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = speed,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                text = "Всего: $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveProxyCard(
    profile: ProxyProfile?,
    onClick: () -> Unit,
    onPingClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Активный прокси",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (profile != null) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${profile.protocol.displayName} • ${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "Сервер не выбран",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Нажмите, чтобы добавить прокси",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (profile != null) {
                PingChip(pingMs = profile.lastPingMs, onClick = onPingClick)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun PingChip(
    pingMs: Long?,
    onClick: (() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val (bg, fg, label) = when {
        pingMs == null -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Пинг",
        )
        pingMs < 150 -> Triple(
            if (dark) KestrelColors.DirectGreenContainerDark else KestrelColors.DirectGreenContainerLight,
            if (dark) KestrelColors.DirectGreenOnDark else KestrelColors.DirectGreen,
            "$pingMs мс",
        )
        pingMs < 350 -> Triple(
            if (dark) KestrelColors.WarningAmberContainerDark else KestrelColors.WarningAmberContainerLight,
            if (dark) KestrelColors.WarningAmberOnDark else KestrelColors.WarningAmber,
            "$pingMs мс",
        )
        else -> Triple(
            if (dark) KestrelColors.BlockRedContainerDark else KestrelColors.BlockRedContainerLight,
            if (dark) KestrelColors.BlockRedOnDark else KestrelColors.BlockRed,
            "$pingMs мс",
        )
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pingMs == null && onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Проверить задержку",
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = fg,
            )
        }
    }
}

@Composable
private fun RoutingSummaryCard(
    uiState: KestrelUiState,
    onClick: () -> Unit,
) {
    val appCount = uiState.settings.selectedPackages.size
    val appPart = when (uiState.settings.appRoutingMode) {
        AppRoutingMode.ALL -> "Все приложения"
        AppRoutingMode.ONLY_SELECTED -> "Только выбранные ($appCount прил.)"
        AppRoutingMode.BYPASS_SELECTED -> "Обходить выбранные ($appCount прил.)"
    }
    val enabledRules = uiState.rules.count { it.enabled }
    val summary = if (enabledRules > 0) {
        "$appPart + $enabledRules прав."
    } else {
        "$appPart • По умолчанию: ${uiState.settings.defaultAction.label}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AltRoute,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Режим маршрутизации",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Нажмите для настройки приложений и правил",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetToggleCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyQuickPickerSheet(
    profiles: List<ProxyProfile>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onManageProxies: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Выбор прокси-сервера",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onManageProxies) {
                    Text("Все серверы")
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val isSelected = profile.id == activeId
                    Surface(
                        onClick = { onSelect(profile.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelect(profile.id) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${profile.protocol.displayName} • ${profile.host}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            PingChip(pingMs = profile.lastPingMs)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceAndDnsSheet(
    uiState: KestrelUiState,
    onChangeTheme: (ThemeMode) -> Unit,
    onChangeDynamicColor: (Boolean) -> Unit,
    onToggleIpv6: (Boolean) -> Unit,
    onSaveDns: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dnsPrimary by remember(uiState.settings.dnsUpstream) {
        mutableStateOf(uiState.settings.dnsUpstream)
    }
    var dnsFallback by remember(uiState.settings.dnsFallback) {
        mutableStateOf(uiState.settings.dnsFallback)
    }
    var routeDns by remember(uiState.settings.routeDns) {
        mutableStateOf(uiState.settings.routeDns)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Внешний вид и DNS",
                style = MaterialTheme.typography.headlineSmall,
            )

            Text(
                text = "Тема оформления",
                style = MaterialTheme.typography.titleSmall,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    ThemeMode.SYSTEM to "Система",
                    ThemeMode.DARK to "AMOLED",
                    ThemeMode.LIGHT to "Светлая",
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = uiState.settings.themeMode == mode,
                        onClick = { onChangeTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(label)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Material You (Dynamic Color)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Подстраивает акценты под цвета обоев Android 12+",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = uiState.settings.dynamicColor,
                        onCheckedChange = onChangeDynamicColor,
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "Серверы DNS",
                style = MaterialTheme.typography.titleSmall,
            )

            OutlinedTextField(
                value = dnsPrimary,
                onValueChange = { dnsPrimary = it },
                label = { Text("Основной DNS") },
                placeholder = { Text("1.1.1.1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = dnsFallback,
                onValueChange = { dnsFallback = it },
                label = { Text("Резервный DNS") },
                placeholder = { Text("8.8.8.8") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Маршрутизировать DNS по правилам",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Доменные правила применяются и к DNS-запросам (блокировка и прокси без утечек).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = routeDns,
                    onCheckedChange = { routeDns = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Маршрутизация IPv6",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "По умолчанию выключено, чтобы IPv4-прокси не обрывали Telegram и сайты с IPv6.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.settings.enableIpv6,
                    onCheckedChange = onToggleIpv6,
                )
            }

            Button(
                onClick = {
                    onSaveDns(dnsPrimary, dnsFallback, routeDns)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Сохранить параметры DNS")
            }

            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (uiState.isBatteryOptimizationIgnored) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Оптимизация батареи уже отключена")
                } else {
                    Text("Отключить оптимизацию батареи")
                }
            }
        }
    }
}
