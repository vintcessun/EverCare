package com.evercare.app

import com.evercare.app.domain.AiAssistant
import com.evercare.app.domain.ChatMessage
import com.evercare.app.domain.LocaleProvider
import com.evercare.app.domain.ScreenSnapshot
import com.evercare.app.domain.ScreenTextReader
import com.evercare.app.domain.SpeechInput
import com.evercare.app.domain.SpeechResult
import com.evercare.app.domain.TextSpeaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantControllerTest {

    @Test
    fun readingTheScreenSpeaksItVerbatimWithoutCallingTheModel() = runTest {
        val speaker = RecordingSpeaker()
        val assistant = RecordingAssistant(listOf("should not be used"))
        val controller = controller(
            scope = this,
            assistant = assistant,
            speaker = speaker,
            screenReader = FakeScreenReader(lines = listOf("余额", "100 元")),
        )

        controller.readScreenAloud()
        advanceUntilIdle()

        assertEquals(listOf("余额\n100 元"), speaker.spoken)
        assertTrue(assistant.calls.isEmpty(), "reading aloud must not need the network")
    }

    @Test
    fun explainingTheScreenSendsTheScreenTextToTheModel() = runTest {
        val assistant = RecordingAssistant(listOf("这是一个付款页面。"))
        val controller = controller(
            scope = this,
            assistant = assistant,
            screenReader = FakeScreenReader(lines = listOf("确认支付", "￥88.00")),
        )

        controller.explainScreen()
        advanceUntilIdle()

        assertEquals(1, assistant.calls.size)
        val prompt = assistant.calls.single().first.single().content
        assertContains(prompt, "确认支付")
        assertContains(prompt, "￥88.00")
    }

    @Test
    fun anEmptyScreenProducesAGentleErrorRatherThanAnEmptyRequest() = runTest {
        val assistant = RecordingAssistant(emptyList())
        val controller = controller(
            scope = this,
            assistant = assistant,
            screenReader = FakeScreenReader(lines = emptyList()),
        )

        controller.explainScreen()
        advanceUntilIdle()

        assertTrue(assistant.calls.isEmpty())
        assertEquals(controller.strings.errorNoScreenText, controller.state.value.error)
    }

    @Test
    fun screenFeaturesAreHiddenWhenThePlatformCannotReadTheScreen() = runTest {
        val controller = controller(
            scope = this,
            screenReader = FakeScreenReader(
                lines = emptyList(),
                availability = ScreenTextReader.Availability.Unsupported,
            ),
        )

        assertTrue(!controller.state.value.canReadScreen)
    }

    @Test
    fun theAnswerIsSpokenWhileItStreams() = runTest {
        val speaker = RecordingSpeaker()
        // Three deltas that complete two sentences plus a trailing fragment.
        val assistant = RecordingAssistant(listOf("您好。", "这是付款页面，", "请小心。"))
        val controller = controller(
            scope = this,
            assistant = assistant,
            speaker = speaker,
            screenReader = FakeScreenReader(lines = listOf("付款")),
        )

        controller.explainScreen()
        advanceUntilIdle()

        // Speech must start before the final delta arrived, not after.
        assertTrue(speaker.spoken.size > 1, "expected incremental speech, got ${speaker.spoken}")
        assertEquals("您好。这是付款页面，请小心。", speaker.spoken.joinToString(""))
    }

    @Test
    fun aVoiceQuestionIncludesTheScreenAsContext() = runTest {
        val assistant = RecordingAssistant(listOf("这是水费账单。"))
        val controller = controller(
            scope = this,
            assistant = assistant,
            speechInput = FakeSpeechInput(
                listOf(SpeechResult.Partial("这是"), SpeechResult.Final("这是什么账单")),
            ),
            screenReader = FakeScreenReader(lines = listOf("水费", "缴费")),
        )

        controller.askByVoice()
        advanceUntilIdle()

        val messages = assistant.calls.single().first
        assertEquals(2, messages.size, "screen context plus the question")
        assertContains(messages[0].content, "水费")
        assertEquals("这是什么账单", messages[1].content)
    }

    @Test
    fun theLanguageOfTheRequestFollowsThePhone() = runTest {
        val assistant = RecordingAssistant(listOf("This is a payment screen."))
        val controller = controller(
            scope = this,
            assistant = assistant,
            screenReader = FakeScreenReader(lines = listOf("Pay now")),
            locale = FakeLocale("en-GB"),
        )

        controller.explainScreen()
        advanceUntilIdle()

        assertEquals("en-GB", assistant.calls.single().second)
        assertEquals("EverCare", controller.strings.appName)
    }

    @Test
    fun sentencesAreSplitOnBothWesternAndCjkPunctuation() {
        val buffer = StringBuilder("您好，这是付款页面。剩下")
        val sentence = AssistantController.takeCompleteSentence(buffer)

        assertEquals("您好，这是付款页面。", sentence)
        assertEquals("剩下", buffer.toString())
    }

    @Test
    fun anUnfinishedSentenceIsHeldBackUntilItEnds() {
        val buffer = StringBuilder("这是一个还没有说完的")
        assertEquals(null, AssistantController.takeCompleteSentence(buffer))
        assertEquals("这是一个还没有说完的", buffer.toString())
    }

    private fun controller(
        scope: TestScope,
        assistant: AiAssistant = RecordingAssistant(emptyList()),
        speaker: TextSpeaker = RecordingSpeaker(),
        speechInput: SpeechInput = FakeSpeechInput(emptyList()),
        screenReader: ScreenTextReader = FakeScreenReader(emptyList()),
        locale: LocaleProvider = FakeLocale("zh-CN"),
    ) = AssistantController(
        scope = scope,
        assistant = assistant,
        speaker = speaker,
        speechInput = speechInput,
        screenReader = screenReader,
        localeProvider = locale,
    )
}

private class RecordingSpeaker : TextSpeaker {
    val spoken = mutableListOf<String>()
    override val isSpeaking = MutableStateFlow(false)

    override suspend fun speak(text: String, language: String) {
        spoken += text
    }

    override fun stop() = Unit
    override fun release() = Unit
}

private class RecordingAssistant(private val deltas: List<String>) : AiAssistant {
    val calls = mutableListOf<Pair<List<ChatMessage>, String>>()

    override fun ask(messages: List<ChatMessage>, language: String): Flow<String> {
        calls += messages to language
        return deltas.asFlow()
    }
}

private class FakeScreenReader(
    private val lines: List<String>,
    override val availability: ScreenTextReader.Availability =
        ScreenTextReader.Availability.Ready,
) : ScreenTextReader {
    override suspend fun capture() = ScreenSnapshot(appLabel = "Test", lines = lines)
}

private class FakeSpeechInput(private val results: List<SpeechResult>) : SpeechInput {
    override val isAvailable = true
    override fun listen(language: String): Flow<SpeechResult> = results.asFlow()
    override fun cancel() = Unit
}

private class FakeLocale(private val tag: String) : LocaleProvider {
    override fun currentLanguage() = tag
}
