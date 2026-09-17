package com.evercare.app

import com.evercare.app.data.AssistantError
import com.evercare.app.data.AssistantNotConfigured
import com.evercare.app.data.SubscriptionExpired
import com.evercare.app.domain.AiAssistant
import com.evercare.app.domain.ChatMessage
import com.evercare.app.domain.LocaleProvider
import com.evercare.app.domain.ScreenTextReader
import com.evercare.app.domain.SpeechInput
import com.evercare.app.domain.SpeechResult
import com.evercare.app.domain.TextSpeaker
import com.evercare.app.i18n.Strings
import com.evercare.app.i18n.stringsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * What the floating panel is doing right now.
 *
 * The panel shows exactly one of these at a time. There is no scrollback and no
 * history: the user pressed one button and this is the answer to that button.
 */
data class AssistantUiState(
    val phase: Phase = Phase.Idle,
    /** Text being spoken or shown. Grows while the model streams. */
    val message: String = "",
    /** Set when something went wrong, already phrased for an elderly reader. */
    val error: String? = null,
    val canReadScreen: Boolean = false,
    val canListen: Boolean = false,
) {
    enum class Phase { Idle, Listening, Thinking, Speaking }
}

/**
 * Drives the whole assistant: screen capture, the model, and the voice.
 *
 * This lives in commonMain so Android and iOS behave identically. Everything
 * platform-specific arrives as a constructor argument.
 */
class AssistantController(
    private val scope: CoroutineScope,
    private val assistant: AiAssistant,
    private val speaker: TextSpeaker,
    private val speechInput: SpeechInput,
    private val screenReader: ScreenTextReader,
    private val localeProvider: LocaleProvider,
) {
    private val _state = MutableStateFlow(
        AssistantUiState(
            canReadScreen = screenReader.availability != ScreenTextReader.Availability.Unsupported,
            canListen = speechInput.isAvailable,
        )
    )
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    /** Current UI language, re-read every time so a system change takes effect. */
    val strings: Strings get() = stringsFor(language())

    private var activeJob: Job? = null

    private fun language(): String = localeProvider.currentLanguage()

    /**
     * Reads the screen out loud, verbatim. No model involved: this is the
     * "my eyes are tired" case, and it should work even with no subscription and
     * no network.
     */
    fun readScreenAloud() = startExclusive {
        val snapshot = captureScreenOrFail() ?: return@startExclusive

        val text = snapshot.asText()
        _state.value = _state.value.copy(phase = AssistantUiState.Phase.Speaking, message = text)
        speaker.speak(text, language())
        _state.value = _state.value.copy(phase = AssistantUiState.Phase.Idle)
    }

    /** Asks the model to explain the current screen in plain language. */
    fun explainScreen() = startExclusive {
        val snapshot = captureScreenOrFail() ?: return@startExclusive

        val prompt = buildString {
            append(explainInstruction(language()))
            append("\n\n---\n")
            append(snapshot.asText())
            append("\n---")
        }
        streamAnswer(listOf(ChatMessage(ChatMessage.Role.User, prompt)))
    }

    /**
     * Listens, then answers. The transcript is shown while the user speaks so
     * they can see that the phone heard them.
     */
    fun askByVoice() = startExclusive {
        if (!speechInput.isAvailable) {
            fail(strings.errorMicrophoneUnavailable)
            return@startExclusive
        }

        _state.value = _state.value.copy(
            phase = AssistantUiState.Phase.Listening,
            message = "",
            error = null,
        )

        var transcript = ""
        var failure: String? = null
        speechInput.listen(language())
            .onEach { result ->
                when (result) {
                    is SpeechResult.Partial -> {
                        _state.value = _state.value.copy(message = result.text)
                    }

                    is SpeechResult.Final -> transcript = result.text
                    is SpeechResult.Failed -> failure = result.reason
                }
            }
            .catch { failure = it.message ?: strings.errorMicrophoneUnavailable }
            .collectToCompletion()

        if (failure != null) {
            fail(strings.errorMicrophoneUnavailable)
            return@startExclusive
        }
        if (transcript.isBlank()) {
            _state.value = _state.value.copy(phase = AssistantUiState.Phase.Idle)
            return@startExclusive
        }

        // The screen is included as context when it is available: most questions
        // an elderly user asks are about what is in front of them right now
        // ("what does this say?", "is this safe?").
        val screen = runCatching { screenReader.capture() }.getOrNull()
        val messages = buildList {
            if (screen != null && !screen.isEmpty) {
                add(
                    ChatMessage(
                        ChatMessage.Role.User,
                        "${screenContextLabel(language())}\n---\n${screen.asText()}\n---",
                    )
                )
            }
            add(ChatMessage(ChatMessage.Role.User, transcript))
        }
        streamAnswer(messages)
    }

    /** Stops whatever is happening. Wired to the one big "stop" button. */
    fun stop() {
        activeJob?.cancel()
        activeJob = null
        speechInput.cancel()
        speaker.stop()
        _state.value = _state.value.copy(phase = AssistantUiState.Phase.Idle)
    }

    fun refreshCapabilities() {
        _state.value = _state.value.copy(
            canReadScreen = screenReader.availability == ScreenTextReader.Availability.Ready,
            canListen = speechInput.isAvailable,
        )
    }

    fun dispose() {
        stop()
        speaker.release()
    }

    private suspend fun captureScreenOrFail(): com.evercare.app.domain.ScreenSnapshot? {
        when (screenReader.availability) {
            ScreenTextReader.Availability.Unsupported -> {
                fail(strings.errorScreenReadingUnsupported)
                return null
            }

            ScreenTextReader.Availability.NeedsPermission -> {
                fail(strings.errorScreenReadingUnsupported)
                return null
            }

            ScreenTextReader.Availability.Ready -> Unit
        }

        val snapshot = runCatching { screenReader.capture() }.getOrElse {
            fail(strings.errorNoScreenText)
            return null
        }

        if (snapshot.isEmpty) {
            fail(strings.errorNoScreenText)
            return null
        }
        return snapshot
    }

    /**
     * Streams the model's answer and speaks it.
     *
     * Speech starts at the first sentence boundary rather than at the end, so the
     * assistant begins talking while the rest is still arriving. Waiting for the
     * full reply feels broken to someone who cannot see a progress spinner.
     */
    private suspend fun streamAnswer(messages: List<ChatMessage>) {
        _state.value = _state.value.copy(
            phase = AssistantUiState.Phase.Thinking,
            message = "",
            error = null,
        )

        val spoken = StringBuilder()
        val pending = StringBuilder()
        var failure: Throwable? = null

        assistant.ask(messages, language())
            .catch { failure = it }
            .onEach { delta ->
                pending.append(delta)
                spoken.append(delta)
                _state.value = _state.value.copy(
                    phase = AssistantUiState.Phase.Speaking,
                    message = spoken.toString(),
                )

                val sentence = takeCompleteSentence(pending)
                if (sentence != null) {
                    speaker.speak(sentence, language())
                }
            }
            .collectToCompletion()

        if (failure != null) {
            fail(failure.toFriendlyMessage())
            return
        }

        val tail = pending.toString().trim()
        if (tail.isNotEmpty()) {
            speaker.speak(tail, language())
        }
        _state.value = _state.value.copy(phase = AssistantUiState.Phase.Idle)
    }

    private fun Throwable.toFriendlyMessage(): String = when (this) {
        is AssistantNotConfigured -> strings.errorNotConfigured
        is SubscriptionExpired -> strings.subscriptionInactive
        is AssistantError -> strings.errorNetwork
        else -> strings.errorNetwork
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(
            phase = AssistantUiState.Phase.Idle,
            error = message,
        )
    }

    /** Only one thing at a time: a new tap cancels whatever was running. */
    private fun startExclusive(block: suspend () -> Unit) {
        activeJob?.cancel()
        speaker.stop()
        activeJob = scope.launch { block() }
    }

    companion object {
        /** Sentence enders across the languages we ship, including CJK forms. */
        private val SENTENCE_ENDERS = charArrayOf(
            '.', '!', '?', '\n', '。', '！', '？', '；', ';', '，', ',',
        )

        /**
         * Pulls the longest completed sentence out of [buffer], leaving the rest.
         * Returns null while nothing is finished yet.
         */
        internal fun takeCompleteSentence(buffer: StringBuilder): String? {
            val lastEnd = buffer.indexOfLast { it in SENTENCE_ENDERS }
            if (lastEnd < 0) return null

            val sentence = buffer.substring(0, lastEnd + 1).trim()
            buffer.delete(0, lastEnd + 1)
            return sentence.ifEmpty { null }
        }

        private fun explainInstruction(language: String): String =
            when (com.evercare.app.i18n.primarySubtag(language)) {
                "zh" -> "请用最简单的话告诉我，下面这个页面是什么、可以做什么。如果要付钱、要填身份证，或者可能是广告、诈骗，请提醒我。"
                "ja" -> "次の画面が何で、何ができるのかを、やさしい言葉で教えてください。支払いや個人情報の入力、広告や詐欺の可能性があれば教えてください。"
                else -> "Tell me in very simple words what this screen is and what I can do here. Warn me if it asks for money or personal details, or if it looks like an advert or a scam."
            }

        private fun screenContextLabel(language: String): String =
            when (com.evercare.app.i18n.primarySubtag(language)) {
                "zh" -> "这是我现在手机屏幕上的内容："
                "ja" -> "これはいま私のスマホ画面に出ている内容です："
                else -> "This is what is on my phone screen right now:"
            }
    }
}

private inline fun CharSequence.indexOfLast(predicate: (Char) -> Boolean): Int {
    for (index in length - 1 downTo 0) {
        if (predicate(this[index])) return index
    }
    return -1
}

/** Collects a flow, ignoring the values, until it finishes. */
private suspend fun kotlinx.coroutines.flow.Flow<*>.collectToCompletion() {
    collect { }
}
