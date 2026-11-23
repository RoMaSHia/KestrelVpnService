package dev.kestrel.proxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.kestrel.proxy.service.KestrelTileService
import dev.kestrel.proxy.ui.KestrelApp
import dev.kestrel.proxy.ui.KestrelViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: KestrelViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startVpn()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        initiateVpnConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.getBooleanExtra(KestrelTileService.EXTRA_AUTO_CONNECT, false) == true) {
            requestConnectWithPermissions()
        }

        setContent {
            KestrelApp(
                viewModel = viewModel,
                onRequestConnect = ::requestConnectWithPermissions,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(KestrelTileService.EXTRA_AUTO_CONNECT, false)) {
            requestConnectWithPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBatteryStatus()
        viewModel.retryPendingInstallIfPermitted()
    }

    private fun requestConnectWithPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        initiateVpnConnection()
    }

    private fun initiateVpnConnection() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn()
        }
    }
}
