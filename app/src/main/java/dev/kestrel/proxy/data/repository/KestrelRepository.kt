package dev.kestrel.proxy.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kestrel.proxy.data.model.AppSettings
import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.RoutingRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.kestrelDataStore: DataStore<Preferences> by preferencesDataStore(name = "kestrel_prefs")

class KestrelRepository private constructor(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val profilesKey = stringPreferencesKey("profiles_json")
    private val rulesKey = stringPreferencesKey("rules_json")
    private val settingsKey = stringPreferencesKey("settings_json")

    val profilesFlow: Flow<List<ProxyProfile>> = context.kestrelDataStore.data.map { prefs ->
        prefs[profilesKey]?.let { raw ->
            runCatching { json.decodeFromString<List<ProxyProfile>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    val rulesFlow: Flow<List<RoutingRule>> = context.kestrelDataStore.data.map { prefs ->
        prefs[rulesKey]?.let { raw ->
            runCatching { json.decodeFromString<List<RoutingRule>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    val settingsFlow: Flow<AppSettings> = context.kestrelDataStore.data.map { prefs ->
        prefs[settingsKey]?.let { raw ->
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrNull()
        } ?: AppSettings()
    }

    suspend fun upsertProfile(profile: ProxyProfile) {
        context.kestrelDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[profilesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) {
                current[idx] = profile
            } else {
                current.add(profile)
            }
            prefs[profilesKey] = json.encodeToString(current)

            val settings = decodeSettings(prefs[settingsKey])
            if (settings.activeProfileId == null) {
                prefs[settingsKey] = json.encodeToString(settings.copy(activeProfileId = profile.id))
            }
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.kestrelDataStore.edit { prefs ->
            val updated = decodeProfiles(prefs[profilesKey]).filterNot { it.id == profileId }
            prefs[profilesKey] = json.encodeToString(updated)

            val settings = decodeSettings(prefs[settingsKey])
            if (settings.activeProfileId == profileId) {
                val nextActive = updated.firstOrNull()?.id
                prefs[settingsKey] = json.encodeToString(settings.copy(activeProfileId = nextActive))
            }
        }
    }

    suspend fun updateProfilePing(profileId: String, pingMs: Long?) {
        context.kestrelDataStore.edit { prefs ->
            val current = decodeProfiles(prefs[profilesKey]).map { profile ->
                if (profile.id == profileId) profile.copy(lastPingMs = pingMs) else profile
            }
            prefs[profilesKey] = json.encodeToString(current)
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        updateSettings { it.copy(activeProfileId = profileId) }
    }

    suspend fun upsertRule(rule: RoutingRule) {
        context.kestrelDataStore.edit { prefs ->
            val current = decodeRules(prefs[rulesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == rule.id }
            if (idx >= 0) {
                current[idx] = rule
            } else {
                current.add(rule)
            }
            prefs[rulesKey] = json.encodeToString(current)
        }
    }

    suspend fun deleteRule(ruleId: String) {
        context.kestrelDataStore.edit { prefs ->
            val updated = decodeRules(prefs[rulesKey]).filterNot { it.id == ruleId }
            prefs[rulesKey] = json.encodeToString(updated)
        }
    }

    suspend fun toggleRule(ruleId: String) {
        context.kestrelDataStore.edit { prefs ->
            val updated = decodeRules(prefs[rulesKey]).map { rule ->
                if (rule.id == ruleId) rule.copy(enabled = !rule.enabled) else rule
            }
            prefs[rulesKey] = json.encodeToString(updated)
        }
    }

    suspend fun moveRule(ruleId: String, moveUp: Boolean) {
        context.kestrelDataStore.edit { prefs ->
            val current = decodeRules(prefs[rulesKey]).toMutableList()
            val idx = current.indexOfFirst { it.id == ruleId }
            if (idx < 0) return@edit
            val targetIdx = if (moveUp) idx - 1 else idx + 1
            if (targetIdx !in current.indices) return@edit
            val item = current.removeAt(idx)
            current.add(targetIdx, item)
            prefs[rulesKey] = json.encodeToString(current)
        }
    }

    suspend fun importRules(newRules: List<RoutingRule>, replaceExisting: Boolean) {
        context.kestrelDataStore.edit { prefs ->
            val combined = if (replaceExisting) {
                newRules
            } else {
                decodeRules(prefs[rulesKey]) + newRules
            }
            prefs[rulesKey] = json.encodeToString(combined)
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.kestrelDataStore.edit { prefs ->
            val current = decodeSettings(prefs[settingsKey])
            val updated = transform(current)
            prefs[settingsKey] = json.encodeToString(updated)
        }
    }

    private fun decodeProfiles(raw: String?): List<ProxyProfile> =
        raw?.let { runCatching { json.decodeFromString<List<ProxyProfile>>(it) }.getOrNull() }
            ?: emptyList()

    private fun decodeRules(raw: String?): List<RoutingRule> =
        raw?.let { runCatching { json.decodeFromString<List<RoutingRule>>(it) }.getOrNull() }
            ?: emptyList()

    private fun decodeSettings(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()

    companion object {
        @Volatile
        private var instance: KestrelRepository? = null

        fun getInstance(context: Context): KestrelRepository =
            instance ?: synchronized(this) {
                instance ?: KestrelRepository(context.applicationContext).also { instance = it }
            }
    }
}
