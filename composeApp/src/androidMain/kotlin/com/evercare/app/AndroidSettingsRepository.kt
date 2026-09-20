package com.evercare.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.evercare.app.data.AiMode
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "evercare")

/**
 * Settings persisted with DataStore.
 *
 * The device token lives here too. That is a deliberate trade-off: Keystore-backed
 * storage would be stronger, but the token only ever grants access to this
 * handset's own subscription, and an elderly user locked out of their assistant
 * because a key became unusable after a restore is the worse failure.
 */
class AndroidSettingsRepository(private val context: Context) : SettingsRepository {

    override val settings: Flow<AssistantSettings> =
        context.dataStore.data.map { it.toSettings() }

    override suspend fun current(): AssistantSettings = settings.first()

    override suspend fun update(
        transform: (AssistantSettings) -> AssistantSettings,
    ): AssistantSettings {
        val updated = context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.Mode] = next.mode.name
            prefs[Keys.ServerUrl] = next.everCareServerUrl
            prefs[Keys.DeviceToken] = next.deviceToken
            prefs[Keys.CustomBaseUrl] = next.customBaseUrl
            prefs[Keys.CustomApiKey] = next.customApiKey
            prefs[Keys.CustomModel] = next.customModel
            prefs[Keys.LanguageOverride] = next.languageOverride
        }
        return updated.toSettings()
    }

    private object Keys {
        val Mode = stringPreferencesKey("mode")
        val ServerUrl = stringPreferencesKey("server_url")
        val DeviceToken = stringPreferencesKey("device_token")
        val CustomBaseUrl = stringPreferencesKey("custom_base_url")
        val CustomApiKey = stringPreferencesKey("custom_api_key")
        val CustomModel = stringPreferencesKey("custom_model")
        val LanguageOverride = stringPreferencesKey("language_override")
    }

    private fun Preferences.toSettings() = AssistantSettings(
        mode = this[Keys.Mode]?.let { name ->
            runCatching { AiMode.valueOf(name) }.getOrDefault(AiMode.EverCare)
        } ?: AiMode.EverCare,
        // A platform string rather than the shared constant, so a test build can
        // be pointed at a LAN server without changing common code.
        everCareServerUrl = this[Keys.ServerUrl]
            ?: context.getString(R.string.default_server_url),
        deviceToken = this[Keys.DeviceToken].orEmpty(),
        customBaseUrl = this[Keys.CustomBaseUrl].orEmpty(),
        customApiKey = this[Keys.CustomApiKey].orEmpty(),
        customModel = this[Keys.CustomModel].orEmpty(),
        languageOverride = this[Keys.LanguageOverride].orEmpty(),
    )
}
