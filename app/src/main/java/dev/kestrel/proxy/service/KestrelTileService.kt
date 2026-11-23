package dev.kestrel.proxy.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kestrel.proxy.MainActivity
import dev.kestrel.proxy.data.model.VpnStatus
import dev.kestrel.proxy.data.repository.KestrelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KestrelTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStateHolder.status.value) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> {
                KestrelVpnService.stop(this)
                refreshTileState()
            }

            VpnStatus.DISCONNECTED -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent == null) {
                    KestrelVpnService.start(this)
                    refreshTileState()
                } else {
                    val launchIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(EXTRA_AUTO_CONNECT, true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pending = PendingIntent.getActivity(
                            this,
                            101,
                            launchIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                        startActivityAndCollapse(pending)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(launchIntent)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val status = VpnStateHolder.status.value
        tile.label = "Kestrel"
        tile.state = when (status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> Tile.STATE_ACTIVE
            VpnStatus.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tileScope.launch {
                val subtitle = when (status) {
                    VpnStatus.CONNECTING -> "Подключение…"
                    VpnStatus.DISCONNECTED -> "Отключено"
                    VpnStatus.CONNECTED -> {
                        val repo = KestrelRepository.getInstance(applicationContext)
                        val settings = repo.settingsFlow.first()
                        val profiles = repo.profilesFlow.first()
                        profiles.firstOrNull { it.id == settings.activeProfileId }?.name
                            ?: "Подключено"
                    }
                }
                qsTile?.let { latestTile ->
                    latestTile.subtitle = subtitle
                    latestTile.updateTile()
                }
            }
        }
        tile.updateTile()
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "extra_auto_connect"

        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, KestrelTileService::class.java),
                )
            }
        }
    }
}
