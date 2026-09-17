package com.evercare.app

import com.evercare.app.i18n.ChineseStrings
import com.evercare.app.i18n.EnglishStrings
import com.evercare.app.i18n.FrenchStrings
import com.evercare.app.i18n.JapaneseStrings
import com.evercare.app.i18n.KoreanStrings
import com.evercare.app.i18n.SpanishStrings
import com.evercare.app.i18n.SupportedStrings
import com.evercare.app.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exhaustive i18n checks.
 *
 * These matter more than they look: the language tag also picks the AI system
 * prompt, the TTS voice and the speech recogniser, so a gap here does not
 * produce a cosmetic bug -- it produces a Korean-speaking user whose assistant
 * answers in English.
 */
class I18nCoverageTest {

    /** Every value on the interface, for one language. */
    private fun allValues(s: com.evercare.app.i18n.Strings) = listOf(
        "languageTag" to s.languageTag,
        "appName" to s.appName,
        "tagline" to s.tagline,
        "readScreen" to s.readScreen,
        "explainScreen" to s.explainScreen,
        "askQuestion" to s.askQuestion,
        "stopSpeaking" to s.stopSpeaking,
        "closePanel" to s.closePanel,
        "listening" to s.listening,
        "thinking" to s.thinking,
        "reading" to s.reading,
        "setupTitle" to s.setupTitle,
        "permissionOverlayTitle" to s.permissionOverlayTitle,
        "permissionOverlayBody" to s.permissionOverlayBody,
        "permissionAccessibilityTitle" to s.permissionAccessibilityTitle,
        "permissionAccessibilityBody" to s.permissionAccessibilityBody,
        "permissionMicrophoneTitle" to s.permissionMicrophoneTitle,
        "permissionMicrophoneBody" to s.permissionMicrophoneBody,
        "permissionNotificationTitle" to s.permissionNotificationTitle,
        "permissionNotificationBody" to s.permissionNotificationBody,
        "grant" to s.grant,
        "granted" to s.granted,
        "startHelper" to s.startHelper,
        "stopHelper" to s.stopHelper,
        "settings" to s.settings,
        "aiServiceTitle" to s.aiServiceTitle,
        "useEverCareService" to s.useEverCareService,
        "useEverCareServiceBody" to s.useEverCareServiceBody,
        "useOwnProvider" to s.useOwnProvider,
        "useOwnProviderBody" to s.useOwnProviderBody,
        "serverUrl" to s.serverUrl,
        "baseUrl" to s.baseUrl,
        "apiKey" to s.apiKey,
        "model" to s.model,
        "save" to s.save,
        "saved" to s.saved,
        "subscriptionActiveUntil" to s.subscriptionActiveUntil,
        "subscriptionInactive" to s.subscriptionInactive,
        "trialDaysLeft" to s.trialDaysLeft,
        "errorNoScreenText" to s.errorNoScreenText,
        "errorNetwork" to s.errorNetwork,
        "errorNotConfigured" to s.errorNotConfigured,
        "errorScreenReadingUnsupported" to s.errorScreenReadingUnsupported,
        "errorScreenReadingNotEnabled" to s.errorScreenReadingNotEnabled,
        "errorMicrophoneUnavailable" to s.errorMicrophoneUnavailable,
    )

    @Test
    fun allSixLanguagesAreReachableFromTheirTags() {
        assertSame(ChineseStrings, stringsFor("zh-CN"))
        assertSame(ChineseStrings, stringsFor("zh-Hans-CN"))
        assertSame(EnglishStrings, stringsFor("en-US"))
        assertSame(JapaneseStrings, stringsFor("ja-JP"))
        assertSame(KoreanStrings, stringsFor("ko-KR"))
        assertSame(SpanishStrings, stringsFor("es-MX"))
        assertSame(FrenchStrings, stringsFor("fr-CA"))
    }

    @Test
    fun everySupportedLanguageFillsEveryField() {
        SupportedStrings.forEach { strings ->
            allValues(strings).forEach { (name, value) ->
                assertTrue(
                    value.isNotBlank(),
                    "${strings.languageTag} leaves $name blank",
                )
            }
        }
    }

    @Test
    fun noLanguageSilentlyFallsBackToEnglishText() {
        // Catches the copy-paste mistake of declaring a new language and leaving
        // English strings in it. App names may legitimately match (EverCare is a
        // brand), so those are skipped.
        val english = allValues(EnglishStrings).toMap()
        val skip = setOf("languageTag", "appName", "model", "baseUrl", "apiKey")

        SupportedStrings.filter { it !== EnglishStrings }.forEach { strings ->
            val shared = allValues(strings)
                .filter { (name, value) -> name !in skip && english[name] == value }
                .map { it.first }

            // Latin-script languages share a few short words legitimately
            // ("Assistant" in French, "Model"/"Modelo"); allow a small overlap
            // but not wholesale duplication.
            assertTrue(
                shared.size <= 3,
                "${strings.languageTag} still uses English for: $shared",
            )
        }
    }

    @Test
    fun theUiAndTheServerAgreeOnWhichLanguagesExist() {
        // The server's prompts.rs matches on these primary subtags. If a
        // language is added here without adding a prompt there, the user gets a
        // translated UI and an English-speaking assistant.
        val serverPromptLanguages = setOf("zh", "en", "ja", "ko", "es", "fr")
        val uiLanguages = SupportedStrings
            .map { com.evercare.app.i18n.primarySubtag(it.languageTag) }
            .toSet()

        assertEquals(serverPromptLanguages, uiLanguages)
    }

    @Test
    fun unknownLanguagesFallBackToEnglishRatherThanCrashing() {
        listOf("sw-KE", "th-TH", "", "   ", "xx").forEach { tag ->
            assertSame(EnglishStrings, stringsFor(tag), "unexpected match for '$tag'")
        }
    }

    @Test
    fun eachLanguageDeclaresATagThatResolvesBackToItself() {
        // A typo like `languageTag = "jp-JP"` would send TTS looking for a voice
        // that does not exist, so the tag has to round-trip.
        SupportedStrings.forEach { strings ->
            assertSame(
                strings,
                stringsFor(strings.languageTag),
                "${strings.languageTag} does not resolve back to itself",
            )
        }
    }
}
