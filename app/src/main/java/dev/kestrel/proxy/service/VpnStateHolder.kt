package dev.kestrel.proxy.service

import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.ConnectionLogEntry
import dev.kestrel.proxy.data.model.TrafficStats
import dev.kestrel.proxy.data.model.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object VpnStateHolder {

    private const val MAX_UI_LOGS = 500

    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _appliedAppMode = MutableStateFlow(AppRoutingMode.ALL)
    val appliedAppMode: StateFlow<AppRoutingMode> = _appliedAppMode.asStateFlow()

    private val _appliedPackages = MutableStateFlow<Set<String>>(emptySet())
    val appliedPackages: StateFlow<Set<String>> = _appliedPackages.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    val logs: StateFlow<List<ConnectionLogEntry>> = _logs.asStateFlow()

    private val _logsPaused = MutableStateFlow(false)
    val logsPaused: StateFlow<Boolean> = _logsPaused.asStateFlow()

    @Volatile
    var lastPolledLogId: Long = 0L
        private set

    fun setConnecting() {
        _errorMessage.value = null
        _status.value = VpnStatus.CONNECTING
    }

    fun setConnected(appMode: AppRoutingMode, packages: Set<String>) {
        _errorMessage.value = null
        _appliedAppMode.value = appMode
        _appliedPackages.value = packages
        _status.value = VpnStatus.CONNECTED
    }

    fun setDisconnected(error: String? = null) {
        _status.value = VpnStatus.DISCONNECTED
        _stats.value = TrafficStats()
        if (error != null) {
            _errorMessage.value = error
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun updateStats(newStats: TrafficStats) {
        _stats.value = newStats
    }

    fun appendLogs(entries: List<ConnectionLogEntry>) {
        if (entries.isEmpty()) return
        lastPolledLogId = maxOf(lastPolledLogId, entries.maxOf { it.id })
        if (_logsPaused.value) return

        _logs.update { existing ->
            val combined = entries.asReversed() + existing
            if (combined.size > MAX_UI_LOGS) combined.take(MAX_UI_LOGS) else combined
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun setLogsPaused(paused: Boolean) {
        _logsPaused.value = paused
    }

    fun resetLogCursor() {
        lastPolledLogId = 0L
    }
}
