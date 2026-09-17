package com.evercare.app

import com.evercare.app.data.AiMode
import com.evercare.app.data.AssistantSettings
import com.evercare.app.i18n.ChineseStrings
import com.evercare.app.i18n.EnglishStrings
import com.evercare.app.i18n.JapaneseStrings
import com.evercare.app.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun everCareModeIsOnlyUsableOnceTheHandsetHasRegistered() {
        val unregistered = AssistantSettings(mode = AiMode.EverCare, deviceToken = "")
        assertTrue(!unregistered.isConfigured)
        assertNull(unregistered.resolveEndpoint())

        val registered = unregistered.copy(deviceToken = "dev_abc.secret")
        assertTrue(registered.isConfigured)
        assertEquals("dev_abc.secret", registered.resolveEndpoint()?.bearerToken)
    }

    @Test
    fun aCustomProviderNeedsAllThreeFields() {
        val partial = AssistantSettings(
            mode = AiMode.Custom,
            customBaseUrl = "https://api.deepseek.com",
            customApiKey = "sk-test",
        )
        assertTrue(!partial.isConfigured, "a missing model must not resolve")

        val complete = partial.copy(customModel = "deepseek-chat")
        val endpoint = complete.resolveEndpoint()
        assertEquals("https://api.deepseek.com", endpoint?.baseUrl)
        assertEquals("sk-test", endpoint?.bearerToken)
        assertEquals("deepseek-chat", endpoint?.model)
    }

    @Test
    fun trailingSlashesDoNotProduceDoubleSlashedUrls() {
        val settings = AssistantSettings(
            mode = AiMode.Custom,
            customBaseUrl = "https://api.deepseek.com/",
            customApiKey = "sk-test",
            customModel = "deepseek-chat",
        )
        assertEquals("https://api.deepseek.com", settings.resolveEndpoint()?.baseUrl)
    }

    @Test
    fun switchingModesKeepsBothConfigurations() {
        // A user trying their own provider must not lose their EverCare token,
        // or switching back would silently re-register the device.
        val settings = AssistantSettings(
            mode = AiMode.EverCare,
            deviceToken = "dev_abc.secret",
            customBaseUrl = "https://api.deepseek.com",
            customApiKey = "sk-test",
            customModel = "deepseek-chat",
        )

        val custom = settings.copy(mode = AiMode.Custom)
        assertEquals("sk-test", custom.resolveEndpoint()?.bearerToken)
        assertEquals("dev_abc.secret", custom.copy(mode = AiMode.EverCare).deviceToken)
    }
}

class I18nTest {

    @Test
    fun theAssistantLanguageFollowsTheRegionalVariant() {
        assertSame(ChineseStrings, stringsFor("zh-CN"))
        assertSame(ChineseStrings, stringsFor("zh-Hant-TW"))
        assertSame(ChineseStrings, stringsFor("zh_TW"))
        assertSame(JapaneseStrings, stringsFor("ja-JP"))
        assertSame(EnglishStrings, stringsFor("en-GB"))
    }

    @Test
    fun anUnsupportedLanguageFallsBackToEnglishRatherThanCrashing() {
        assertSame(EnglishStrings, stringsFor("sw-KE"))
        assertSame(EnglishStrings, stringsFor(""))
        assertSame(EnglishStrings, stringsFor("   "))
    }

    @Test
    fun everyLanguageTranslatesEveryUserFacingString() {
        // A half-translated language is worse than none: the user would meet
        // English error text spoken by a Chinese voice.
        listOf(ChineseStrings, EnglishStrings, JapaneseStrings).forEach { strings ->
            val values = listOf(
                strings.appName, strings.tagline,
                strings.readScreen, strings.explainScreen, strings.askQuestion,
                strings.stopSpeaking, strings.closePanel,
                strings.listening, strings.thinking, strings.reading,
                strings.setupTitle,
                strings.permissionOverlayTitle, strings.permissionOverlayBody,
                strings.permissionAccessibilityTitle, strings.permissionAccessibilityBody,
                strings.permissionMicrophoneTitle, strings.permissionMicrophoneBody,
                strings.grant, strings.granted, strings.startHelper, strings.stopHelper,
                strings.settings, strings.aiServiceTitle,
                strings.useEverCareService, strings.useEverCareServiceBody,
                strings.useOwnProvider, strings.useOwnProviderBody,
                strings.serverUrl, strings.baseUrl, strings.apiKey, strings.model,
                strings.save, strings.saved,
                strings.subscriptionActiveUntil, strings.subscriptionInactive,
                strings.trialDaysLeft,
                strings.errorNoScreenText, strings.errorNetwork,
                strings.errorNotConfigured, strings.errorScreenReadingUnsupported,
                strings.errorMicrophoneUnavailable,
            )
            values.forEach { value ->
                assertTrue(value.isNotBlank(), "${strings.languageTag} has a blank string")
            }
        }
    }
}
