package com.evercare.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Which assistant service the app talks to. */
enum class AiMode {
    /** EverCare's own service. Monthly plan, nothing for the user to configure. */
    EverCare,

    /** Any OpenAI-compatible endpoint the user supplies themselves. */
    Custom,
}

/**
 * Everything the app needs to reach an assistant.
 *
 * Both modes end up as the same three values -- a base URL, a bearer token and a
 * model name -- which is why [resolveEndpoint] can hand the HTTP layer one shape
 * and there is no second code path for third-party providers.
 */
@Serializable
data class AssistantSettings(
    val mode: AiMode = AiMode.EverCare,

    /** Where EverCare's own server lives. Self-hosters point this elsewhere. */
    val everCareServerUrl: String = DEFAULT_EVERCARE_SERVER,
    /** Issued by the server at first run; identifies this handset. */
    val deviceToken: String = "",

    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModel: String = "",

    /**
     * Overrides the phone's language for the assistant. Empty means "follow the
     * system", which is what almost everyone wants; the override exists for a
     * phone whose system language is not the one its owner actually speaks --
     * common on handsets set up by a relative.
     */
    val languageOverride: String = "",
) {
    val isConfigured: Boolean
        get() = when (mode) {
            AiMode.EverCare -> everCareServerUrl.isNotBlank() && deviceToken.isNotBlank()
            AiMode.Custom -> customBaseUrl.isNotBlank() && customApiKey.isNotBlank() &&
                customModel.isNotBlank()
        }

    fun resolveEndpoint(): AssistantEndpoint? = when (mode) {
        AiMode.EverCare ->
            if (!isConfigured) null
            else AssistantEndpoint(
                baseUrl = everCareServerUrl.trimEnd('/'),
                bearerToken = deviceToken,
                // The server chooses the model; sending one is pointless because
                // it is deliberately ignored there.
                model = EVERCARE_MANAGED_MODEL,
            )

        AiMode.Custom ->
            if (!isConfigured) null
            else AssistantEndpoint(
                baseUrl = customBaseUrl.trimEnd('/'),
                bearerToken = customApiKey,
                model = customModel,
            )
    }

    companion object {
        const val DEFAULT_EVERCARE_SERVER = "https://api.evercare.app"
        const val EVERCARE_MANAGED_MODEL = "evercare-managed"
    }
}

/** A resolved place to send chat requests. */
data class AssistantEndpoint(
    val baseUrl: String,
    val bearerToken: String,
    val model: String,
)

/**
 * Persistent settings. Implemented per platform (DataStore on Android,
 * NSUserDefaults on iOS) because there is no shared storage worth the
 * dependency for five fields.
 */
interface SettingsRepository {
    val settings: Flow<AssistantSettings>

    suspend fun current(): AssistantSettings

    suspend fun update(transform: (AssistantSettings) -> AssistantSettings): AssistantSettings
}
