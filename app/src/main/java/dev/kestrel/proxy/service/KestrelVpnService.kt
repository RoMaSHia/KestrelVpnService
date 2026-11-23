package dev.kestrel.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.kestrel.proxy.MainActivity
import dev.kestrel.proxy.R
import dev.kestrel.proxy.core.NativeBridge
import dev.kestrel.proxy.core.SocketProtector
import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.data.model.TrafficStats
import dev.kestrel.proxy.data.model.VpnStatus
import dev.kestrel.proxy.data.model.encodeCoreConfigJson
import dev.kestrel.proxy.data.repository.KestrelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KestrelVpnService : VpnService(), SocketProtector {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var telemetryJob: Job? = null
    private var activeProfileLabel: String = "Kestrel"

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    stopTunnelLocked(error = null)
                }
                return START_NOT_STICKY
            }

            ACTION_UPDATE_RULES -> {
                serviceScope.launch {
                    updateRulesInPlace()
                }
                return START_STICKY
            }

            ACTION_START, ACTION_RESTART, null -> {
                VpnStateHolder.setConnecting()
                startForegroundCompat(buildNotification(activeProfileLabel, TrafficStats()))
                serviceScope.launch {
                    startOrRestartTunnelLocked()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        serviceScope.launch {
            stopTunnelLocked(error = "VPN-разрешение было отозвано системой")
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        telemetryJob?.cancel()
        NativeBridge.stop()
        if (VpnStateHolder.status.value != VpnStatus.DISCONNECTED) {
            VpnStateHolder.setDisconnected()
        }
        KestrelTileService.requestTileUpdate(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun protect(fd: Int): Boolean = super.protect(fd)

    private suspend fun startOrRestartTunnelLocked() = lifecycleMutex.withLock {
        val repo = KestrelRepository.getInstance(this)
        val profiles = repo.profilesFlow.first()
        val rules = repo.rulesFlow.first()
        val settings = repo.settingsFlow.first()

        val activeProfile = profiles.firstOrNull { it.id == settings.activeProfileId }
        if (activeProfile == null && settings.defaultAction == RuleAction.PROXY) {
            stopTunnelInternal("Добавьте и выберите прокси-сервер на вкладке «Прокси»")
            return@withLock
        }

        if (settings.appRoutingMode == AppRoutingMode.ONLY_SELECTED &&
            settings.selectedPackages.none { it != packageName }
        ) {
            stopTunnelInternal("Выберите хотя бы одно приложение для режима «Только выбранные»")
            return@withLock
        }

        activeProfileLabel = activeProfile?.let { "${it.name} (${it.protocol.displayName})" }
            ?: "Прямое подключение"

        // Stop previous engine instance cleanly before rebuilding TUN interface
        telemetryJob?.cancel()
        NativeBridge.stop()
        VpnStateHolder.resetLogCursor()

        val builder = Builder()
            .setSession("Kestrel")
            .setMtu(settings.mtu.coerceIn(1280, 1500))
            .addAddress("10.10.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:10::2", 64)
            .addRoute("::", 0)

        val dnsIp = (if (settings.useCustomDns) settings.dnsUpstream else "8.8.8.8")
            .trim()
            .removePrefix("[")
            .substringBefore(']')
            .substringBefore(':')
            .ifBlank { "1.1.1.1" }
        runCatching { builder.addDnsServer(dnsIp) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applySplitTunneling(builder, settings.appRoutingMode, settings.selectedPackages)

        val pfd = builder.establish()
        if (pfd == null) {
            stopTunnelInternal("Система не выдала разрешение на VPN-интерфейс")
            return@withLock
        }

        val tunFd = pfd.detachFd()
        val configJson = encodeCoreConfigJson(activeProfile, settings, rules)
        val startResult = NativeBridge.start(tunFd, configJson, this)
        if (startResult.isFailure) {
            stopTunnelInternal(
                startResult.exceptionOrNull()?.message ?: "Ошибка запуска нативного ядра",
            )
            return@withLock
        }

        VpnStateHolder.setConnected(settings.appRoutingMode, settings.selectedPackages)
        KestrelTileService.requestTileUpdate(this)
        updateNotification(activeProfileLabel, TrafficStats())
        startTelemetryPolling()
    }

    private fun applySplitTunneling(
        builder: Builder,
        mode: AppRoutingMode,
        selectedPackages: Set<String>,
    ) {
        when (mode) {
            AppRoutingMode.ALL -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            AppRoutingMode.ONLY_SELECTED -> {
                for (pkg in selectedPackages) {
                    if (pkg != packageName) {
                        runCatching { builder.addAllowedApplication(pkg) }
                    }
                }
            }

            AppRoutingMode.BYPASS_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                for (pkg in selectedPackages) {
                    if (pkg != packageName) {
                        runCatching { builder.addDisallowedApplication(pkg) }
                    }
                }
            }
        }
    }

    private suspend fun updateRulesInPlace() = lifecycleMutex.withLock {
        if (VpnStateHolder.status.value != VpnStatus.CONNECTED) return@withLock
        val repo = KestrelRepository.getInstance(this)
        val profiles = repo.profilesFlow.first()
        val rules = repo.rulesFlow.first()
        val settings = repo.settingsFlow.first()
        val activeProfile = profiles.firstOrNull { it.id == settings.activeProfileId }
        val configJson = encodeCoreConfigJson(activeProfile, settings, rules)
        NativeBridge.updateConfig(configJson)
    }

    private suspend fun stopTunnelLocked(error: String?) = lifecycleMutex.withLock {
        stopTunnelInternal(error)
    }

    private fun stopTunnelInternal(error: String?) {
        telemetryJob?.cancel()
        telemetryJob = null
        NativeBridge.stop()
        VpnStateHolder.setDisconnected(error)
        KestrelTileService.requestTileUpdate(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            while (isActive && VpnStateHolder.status.value == VpnStatus.CONNECTED) {
                NativeBridge.getStats().onSuccess { stats ->
                    VpnStateHolder.updateStats(stats)
                    updateNotification(activeProfileLabel, stats)
                }
                NativeBridge.pollLogs(VpnStateHolder.lastPolledLogId, 100).onSuccess { newLogs ->
                    VpnStateHolder.appendLogs(newLogs)
                }
                delay(1000L)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Статус VPN-подключения",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Отображает активный прокси-профиль и скорость трафика"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(profileLabel: String, stats: TrafficStats) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(profileLabel, stats))
    }

    private fun buildNotification(profileLabel: String, stats: TrafficStats): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, KestrelVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val speedLine = "↓ ${formatRate(stats.rxSpeedBps)}   ↑ ${formatRate(stats.txSpeedBps)}"
        val statusText = when (VpnStateHolder.status.value) {
            VpnStatus.CONNECTING -> "Подключение к $profileLabel…"
            VpnStatus.CONNECTED -> "$profileLabel • $speedLine"
            VpnStatus.DISCONNECTED -> "Отключено"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle("Kestrel VPN")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Отключить", stopIntent)
            .build()
    }

    private fun formatRate(bps: Long): String = when {
        bps >= 1_048_576 -> String.format("%.1f МБ/с", bps / 1_048_576.0)
        bps >= 1024 -> String.format("%.1f КБ/с", bps / 1024.0)
        else -> "$bps Б/с"
    }

    companion object {
        private const val CHANNEL_ID = "kestrel_vpn_channel"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "dev.kestrel.proxy.ACTION_START"
        const val ACTION_STOP = "dev.kestrel.proxy.ACTION_STOP"
        const val ACTION_RESTART = "dev.kestrel.proxy.ACTION_RESTART"
        const val ACTION_UPDATE_RULES = "dev.kestrel.proxy.ACTION_UPDATE_RULES"

        fun start(context: Context) {
            val intent = Intent(context, KestrelVpnService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KestrelVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, KestrelVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateRules(context: Context) {
            if (VpnStateHolder.status.value != VpnStatus.CONNECTED) return
            val intent = Intent(context, KestrelVpnService::class.java).apply {
                action = ACTION_UPDATE_RULES
            }
            context.startService(intent)
        }
    }
}
