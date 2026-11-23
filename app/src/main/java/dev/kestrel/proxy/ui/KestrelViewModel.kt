package dev.kestrel.proxy.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.PowerManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kestrel.proxy.BuildConfig
import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.AppSettings
import dev.kestrel.proxy.data.model.ConnectionLogEntry
import dev.kestrel.proxy.data.model.InstalledAppInfo
import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.RoutingRule
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.data.model.ThemeMode
import dev.kestrel.proxy.data.model.TrafficStats
import dev.kestrel.proxy.data.model.VpnStatus
import dev.kestrel.proxy.data.repository.KestrelRepository
import dev.kestrel.proxy.data.util.ProxyPinger
import dev.kestrel.proxy.data.util.ProxyUriParser
import dev.kestrel.proxy.data.util.RuleSerializer
import dev.kestrel.proxy.data.util.UpdateCheckResult
import dev.kestrel.proxy.data.util.UpdateChecker
import dev.kestrel.proxy.service.KestrelVpnService
import dev.kestrel.proxy.service.VpnStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersion: String) : UpdateUiState
    data class Available(
        val latestVersion: String,
        val downloadUrl: String,
        val releasePageUrl: String,
        val releaseNotes: String,
    ) : UpdateUiState

    data class Error(
        val message: String,
        val releasesUrl: String = UpdateChecker.RELEASES_URL,
    ) : UpdateUiState
}

data class KestrelUiState(
    val profiles: List<ProxyProfile> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val vpnStatus: VpnStatus = VpnStatus.DISCONNECTED,
    val vpnError: String? = null,
    val stats: TrafficStats = TrafficStats(),
    val logs: List<ConnectionLogEntry> = emptyList(),
    val logsPaused: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val hasPendingAppChanges: Boolean = false,
    val isPinging: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = true,
    val updateState: UpdateUiState = UpdateUiState.Idle,
) {
    val activeProfile: ProxyProfile?
        get() = profiles.firstOrNull { it.id == settings.activeProfileId }
}

class KestrelViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = KestrelRepository.getInstance(application)
    private val installedAppsFlow = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val isLoadingAppsFlow = MutableStateFlow(true)
    private val isPingingFlow = MutableStateFlow(false)
    private val batteryIgnoredFlow = MutableStateFlow(checkBatteryIgnored())
    private val updateStateFlow = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    private data class CoreConfigFingerprint(
        val activeProfileId: String?,
        val profiles: List<ProxyProfile>,
        val rules: List<RoutingRule>,
        val defaultAction: RuleAction,
        val bypassLan: Boolean,
        val useCustomDns: Boolean,
        val dnsUpstream: String,
        val dnsFallback: String,
        val routeDns: Boolean,
    )

    private var lastAppliedCoreFingerprint: CoreConfigFingerprint? = null

    private val repoCombined = combine(
        repo.profilesFlow,
        repo.rulesFlow,
        repo.settingsFlow,
    ) { profiles, rules, settings ->
        Triple(profiles, rules, settings)
    }

    private val vpnCombined = combine(
        VpnStateHolder.status,
        VpnStateHolder.errorMessage,
        VpnStateHolder.stats,
        VpnStateHolder.logs,
        VpnStateHolder.logsPaused,
    ) { status, error, stats, logs, paused ->
        VpnBundle(status, error, stats, logs, paused)
    }

    private val appApplyCombined = combine(
        VpnStateHolder.appliedAppMode,
        VpnStateHolder.appliedPackages,
        installedAppsFlow,
        isLoadingAppsFlow,
        isPingingFlow,
    ) { appliedMode, appliedPkgs, apps, loadingApps, pinging ->
        MetaBundle(appliedMode, appliedPkgs, apps, loadingApps, pinging)
    }

    val uiState: StateFlow<KestrelUiState> = combine(
        repoCombined,
        vpnCombined,
        appApplyCombined,
        batteryIgnoredFlow,
        updateStateFlow,
    ) { (profiles, rules, settings), vpn, meta, batteryIgnored, updateState ->
        val pendingAppChanges = vpn.status == VpnStatus.CONNECTED &&
            (settings.appRoutingMode != meta.appliedMode || settings.selectedPackages != meta.appliedPkgs)

        KestrelUiState(
            profiles = profiles,
            rules = rules,
            settings = settings,
            vpnStatus = vpn.status,
            vpnError = vpn.error,
            stats = vpn.stats,
            logs = vpn.logs,
            logsPaused = vpn.logsPaused,
            installedApps = meta.apps,
            isLoadingApps = meta.loadingApps,
            hasPendingAppChanges = pendingAppChanges,
            isPinging = meta.isPinging,
            isBatteryOptimizationIgnored = batteryIgnored,
            updateState = updateState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = KestrelUiState(),
    )

    init {
        loadInstalledApps()
        observeHotRuleUpdates()
    }

    private fun observeHotRuleUpdates() {
        viewModelScope.launch {
            combine(
                repo.profilesFlow,
                repo.rulesFlow,
                repo.settingsFlow,
                VpnStateHolder.status,
            ) { profiles, rules, settings, status ->
                val fp = CoreConfigFingerprint(
                    activeProfileId = settings.activeProfileId,
                    profiles = profiles.map { it.copy(lastPingMs = null) },
                    rules = rules,
                    defaultAction = settings.defaultAction,
                    bypassLan = settings.bypassLan,
                    useCustomDns = settings.useCustomDns,
                    dnsUpstream = settings.dnsUpstream,
                    dnsFallback = settings.dnsFallback,
                    routeDns = settings.routeDns,
                )
                status to fp
            }.collect { (status, fp) ->
                if (status == VpnStatus.CONNECTED) {
                    if (lastAppliedCoreFingerprint != null && lastAppliedCoreFingerprint != fp) {
                        KestrelVpnService.updateRules(getApplication())
                    }
                    lastAppliedCoreFingerprint = fp
                } else {
                    lastAppliedCoreFingerprint = null
                }
            }
        }
    }

    fun refreshBatteryStatus() {
        batteryIgnoredFlow.value = checkBatteryIgnored()
    }

    private fun checkBatteryIgnored(): Boolean {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            isLoadingAppsFlow.value = true
            val loaded = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val pm = ctx.packageManager
                val selfPkg = ctx.packageName
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != selfPkg }
                    .map { appInfo ->
                        val label = runCatching {
                            pm.getApplicationLabel(appInfo).toString()
                        }.getOrDefault(appInfo.packageName)
                        val isSystem = (appInfo.flags and (
                            ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                            )) != 0
                        val icon = runCatching {
                            pm.getApplicationIcon(appInfo).toScaledImageBitmap()
                        }.getOrNull()
                        InstalledAppInfo(
                            packageName = appInfo.packageName,
                            label = label,
                            isSystem = isSystem,
                            icon = icon,
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
            installedAppsFlow.value = loaded
            isLoadingAppsFlow.value = false
        }
    }

    fun startVpn() {
        VpnStateHolder.clearError()
        KestrelVpnService.start(getApplication())
    }

    fun stopVpn() {
        KestrelVpnService.stop(getApplication())
    }

    fun applyPendingAppChanges() {
        KestrelVpnService.restart(getApplication())
    }

    fun dismissError() {
        VpnStateHolder.clearError()
    }

    // --- Proxies ---

    fun saveProxy(profile: ProxyProfile) {
        viewModelScope.launch {
            repo.upsertProfile(profile)
            pingSingleInternal(profile)
        }
    }

    fun deleteProxy(profileId: String) {
        viewModelScope.launch {
            repo.deleteProfile(profileId)
        }
    }

    fun selectProxy(profileId: String) {
        viewModelScope.launch {
            repo.setActiveProfile(profileId)
        }
    }

    fun importProxyFromText(raw: String): Result<ProxyProfile> {
        val parsed = ProxyUriParser.parseUri(raw)
        parsed.onSuccess { profile ->
            saveProxy(profile)
        }
        return parsed
    }

    fun pingProfile(profile: ProxyProfile) {
        viewModelScope.launch {
            isPingingFlow.value = true
            pingSingleInternal(profile)
            isPingingFlow.value = false
        }
    }

    fun pingAllProfiles() {
        val list = uiState.value.profiles
        if (list.isEmpty()) return
        viewModelScope.launch {
            isPingingFlow.value = true
            list.map { profile ->
                async { pingSingleInternal(profile) }
            }.awaitAll()
            isPingingFlow.value = false
        }
    }

    private suspend fun pingSingleInternal(profile: ProxyProfile) {
        val latency = ProxyPinger.measureLatencyMs(profile)
        repo.updateProfilePing(profile.id, latency)
    }

    // --- App Split Tunneling ---

    fun setAppRoutingMode(mode: AppRoutingMode) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(appRoutingMode = mode) }
        }
    }

    fun togglePackageSelected(packageName: String) {
        viewModelScope.launch {
            repo.updateSettings { settings ->
                val next = settings.selectedPackages.toMutableSet()
                if (!next.add(packageName)) {
                    next.remove(packageName)
                }
                settings.copy(selectedPackages = next)
            }
        }
    }

    fun clearSelectedPackages() {
        viewModelScope.launch {
            repo.updateSettings { it.copy(selectedPackages = emptySet()) }
        }
    }

    // --- Rules & Presets ---

    fun saveRule(rule: RoutingRule) {
        viewModelScope.launch {
            repo.upsertRule(rule)
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            repo.deleteRule(ruleId)
        }
    }

    fun toggleRule(ruleId: String) {
        viewModelScope.launch {
            repo.toggleRule(ruleId)
        }
    }

    fun moveRule(ruleId: String, moveUp: Boolean) {
        viewModelScope.launch {
            repo.moveRule(ruleId, moveUp)
        }
    }

    fun setDefaultAction(action: RuleAction) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(defaultAction = action) }
        }
    }

    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(bypassLan = enabled) }
        }
    }

    fun setUseCustomDns(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(useCustomDns = enabled) }
        }
    }

    fun setDnsServers(upstream: String, fallback: String, routeDns: Boolean) {
        viewModelScope.launch {
            repo.updateSettings {
                it.copy(
                    dnsUpstream = upstream.trim().ifEmpty { "1.1.1.1" },
                    dnsFallback = fallback.trim().ifEmpty { "8.8.8.8" },
                    routeDns = routeDns,
                )
            }
        }
    }

    fun importRulesContent(raw: String, replaceExisting: Boolean): Result<Int> {
        val jsonAttempt = RuleSerializer.importJson(raw)
        val parsed = jsonAttempt.getOrElse {
            RuleSerializer.importText(raw)
        }
        if (parsed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Не найдено корректных правил"))
        }
        viewModelScope.launch {
            repo.importRules(parsed, replaceExisting)
        }
        return Result.success(parsed.size)
    }

    // --- Logs & Theme ---

    fun toggleLogsPaused() {
        VpnStateHolder.setLogsPaused(!VpnStateHolder.logsPaused.value)
    }

    fun clearLogs() {
        VpnStateHolder.clearLogs()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(themeMode = mode) }
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(dynamicColor = enabled) }
        }
    }

    fun setEnableIpv6(enabled: Boolean) {
        viewModelScope.launch {
            repo.updateSettings { it.copy(enableIpv6 = enabled) }
            if (VpnStateHolder.status.value == VpnStatus.CONNECTED) {
                KestrelVpnService.restart(getApplication())
            }
        }
    }

    // --- In-app Updates ---

    fun checkForUpdates() {
        if (updateStateFlow.value is UpdateUiState.Checking) {
            return
        }
        viewModelScope.launch {
            updateStateFlow.value = UpdateUiState.Checking
            when (val res = UpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpToDate -> {
                    updateStateFlow.value = UpdateUiState.UpToDate(res.currentVersion)
                }
                is UpdateCheckResult.UpdateAvailable -> {
                    updateStateFlow.value = UpdateUiState.Available(
                        latestVersion = res.latestVersion,
                        downloadUrl = res.downloadUrl,
                        releasePageUrl = res.releasePageUrl,
                        releaseNotes = res.releaseNotes,
                    )
                }
                is UpdateCheckResult.Unavailable -> {
                    updateStateFlow.value = UpdateUiState.Error(
                        message = res.message,
                        releasesUrl = res.releasesUrl,
                    )
                }
            }
        }
    }

    private data class VpnBundle(
        val status: VpnStatus,
        val error: String?,
        val stats: TrafficStats,
        val logs: List<ConnectionLogEntry>,
        val logsPaused: Boolean,
    )

    private data class MetaBundle(
        val appliedMode: AppRoutingMode,
        val appliedPkgs: Set<String>,
        val apps: List<InstalledAppInfo>,
        val loadingApps: Boolean,
        val isPinging: Boolean,
    )
}

private fun Drawable.toScaledImageBitmap(sizePx: Int = 96): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
