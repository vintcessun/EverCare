package com.evercare.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Text scraped from whatever the user is looking at right now.
 *
 * [lines] keeps the on-screen reading order rather than being joined up front:
 * the reading feature highlights one line at a time, and the AI feature wants
 * the whole thing.
 */
data class ScreenSnapshot(
    val appLabel: String?,
    val lines: List<String>,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    fun asText(): String = lines.joinToString("\n")

    companion object {
        val Empty = ScreenSnapshot(appLabel = null, lines = emptyList())
    }
}

/**
 * Reads the text currently on screen.
 *
 * Android implements this with an AccessibilityService. iOS has no equivalent
 * cross-app API, so its implementation reports [Availability.Unsupported] and
 * the UI hides the feature instead of offering a button that does nothing.
 */
interface ScreenTextReader {
    val availability: Availability

    suspend fun capture(): ScreenSnapshot

    enum class Availability {
        /** Ready to use. */
        Ready,

        /** Supported, but the user has not granted the permission yet. */
        NeedsPermission,

        /** The platform cannot do this at all. */
        Unsupported,
    }
}

/** Speaks text aloud in the user's language. */
interface TextSpeaker {
    val isSpeaking: Flow<Boolean>

    /** @param language BCP-47 tag, e.g. `zh-CN`. Picks the matching voice. */
    suspend fun speak(text: String, language: String)

    fun stop()

    fun release()
}

/** One-shot voice input: the elderly user holds a button and talks. */
interface SpeechInput {
    val isAvailable: Boolean

    /**
     * Emits partial transcripts as they arrive and completes with the final one.
     * Partials matter here: seeing their own words appear tells the user the
     * phone is listening, which is the single most common point of confusion.
     */
    fun listen(language: String): Flow<SpeechResult>

    fun cancel()
}

sealed interface SpeechResult {
    data class Partial(val text: String) : SpeechResult
    data class Final(val text: String) : SpeechResult
    data class Failed(val reason: String) : SpeechResult
}

/** A chat turn, in the shape every OpenAI-compatible provider expects. */
data class ChatMessage(
    val role: Role,
    val content: String,
) {
    enum class Role { System, User, Assistant }
}

/**
 * The assistant itself. One interface, two deployments: EverCare's own Rust
 * service and any third-party OpenAI-compatible endpoint the user configures.
 */
interface AiAssistant {
    /** Streams the reply token by token so speech can start before it finishes. */
    fun ask(
        messages: List<ChatMessage>,
        language: String,
    ): Flow<String>
}

/** Where the app gets its current language from. */
interface LocaleProvider {
    /** BCP-47 tag of the phone's current language, e.g. `zh-CN`, `en-US`. */
    fun currentLanguage(): String
}
