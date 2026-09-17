package com.evercare.app.i18n

/**
 * UI strings.
 *
 * This is a plain Kotlin lookup rather than platform resource files on purpose:
 * one language tag has to drive four things at once -- the UI, the AI system
 * prompt, the text-to-speech voice and the speech recogniser. Keeping a single
 * source of truth means a language can never end up half-supported, with
 * translated buttons but an English-speaking assistant.
 *
 * Adding a language: implement [Strings], add it to [forLanguage], and add the
 * matching system prompt to the server's `prompts.rs`.
 */
interface Strings {
    val languageTag: String

    val appName: String
    val tagline: String

    // Floating panel. Every one of these is reachable in a single tap.
    val readScreen: String
    val explainScreen: String
    val askQuestion: String
    val stopSpeaking: String
    val closePanel: String
    val listening: String
    val thinking: String
    val reading: String

    // Onboarding and permissions
    val setupTitle: String
    val permissionOverlayTitle: String
    val permissionOverlayBody: String
    val permissionAccessibilityTitle: String
    val permissionAccessibilityBody: String
    val permissionMicrophoneTitle: String
    val permissionMicrophoneBody: String
    val grant: String
    val granted: String
    val startHelper: String
    val stopHelper: String

    // Settings
    val settings: String
    val aiServiceTitle: String
    val useEverCareService: String
    val useEverCareServiceBody: String
    val useOwnProvider: String
    val useOwnProviderBody: String
    val serverUrl: String
    val baseUrl: String
    val apiKey: String
    val model: String
    val save: String
    val saved: String
    val subscriptionActiveUntil: String
    val subscriptionInactive: String
    val trialDaysLeft: String

    // Errors, worded so that an eighty-year-old is not frightened by them.
    val errorNoScreenText: String
    val errorNetwork: String
    val errorNotConfigured: String
    val errorScreenReadingUnsupported: String
    val errorMicrophoneUnavailable: String
}

/** Picks the [Strings] for a BCP-47 tag such as `zh-CN`, falling back to English. */
fun stringsFor(languageTag: String): Strings = forLanguage(primarySubtag(languageTag))

private fun forLanguage(subtag: String): Strings = when (subtag) {
    "zh" -> ChineseStrings
    "ja" -> JapaneseStrings
    else -> EnglishStrings
}

/** `zh-Hans-CN` -> `zh`. Region and script never change which assistant we use. */
fun primarySubtag(tag: String): String {
    val trimmed = tag.trim()
    val end = trimmed.indexOfFirst { it == '-' || it == '_' }
    return (if (end == -1) trimmed else trimmed.substring(0, end)).lowercase()
}

object ChineseStrings : Strings {
    override val languageTag = "zh-CN"
    override val appName = "长伴"
    override val tagline = "手机上的贴心帮手"

    override val readScreen = "读给我听"
    override val explainScreen = "这是什么"
    override val askQuestion = "我要问"
    override val stopSpeaking = "停一下"
    override val closePanel = "收起来"
    override val listening = "我在听……"
    override val thinking = "让我想想……"
    override val reading = "正在读……"

    override val setupTitle = "第一次使用，请先开启这三项"
    override val permissionOverlayTitle = "显示小圆点"
    override val permissionOverlayBody = "让帮手一直待在屏幕上，随时能点"
    override val permissionAccessibilityTitle = "读取屏幕文字"
    override val permissionAccessibilityBody = "这样我才能把屏幕上的字念给您听"
    override val permissionMicrophoneTitle = "使用麦克风"
    override val permissionMicrophoneBody = "这样您可以直接说话问我"
    override val grant = "去开启"
    override val granted = "已开启"
    override val startHelper = "打开帮手"
    override val stopHelper = "关闭帮手"

    override val settings = "设置"
    override val aiServiceTitle = "智能助手"
    override val useEverCareService = "使用长伴服务"
    override val useEverCareServiceBody = "按月付费，不用自己设置"
    override val useOwnProvider = "使用自己的接口"
    override val useOwnProviderBody = "自己填写服务地址、密钥和模型"
    override val serverUrl = "长伴服务器地址"
    override val baseUrl = "服务地址"
    override val apiKey = "密钥"
    override val model = "模型"
    override val save = "保存"
    override val saved = "已保存"
    override val subscriptionActiveUntil = "服务有效期至"
    override val subscriptionInactive = "服务已到期"
    override val trialDaysLeft = "试用还剩"

    override val errorNoScreenText = "这个页面上没有找到文字"
    override val errorNetwork = "网络好像不太好，待会儿再试试"
    override val errorNotConfigured = "还没设置好助手，请先在设置里选一个"
    override val errorScreenReadingUnsupported = "这部手机不能读取屏幕上的文字"
    override val errorMicrophoneUnavailable = "麦克风现在用不了"
}

object EnglishStrings : Strings {
    override val languageTag = "en-US"
    override val appName = "EverCare"
    override val tagline = "A patient helper on your phone"

    override val readScreen = "Read to me"
    override val explainScreen = "What is this?"
    override val askQuestion = "Ask me"
    override val stopSpeaking = "Stop"
    override val closePanel = "Hide"
    override val listening = "I am listening..."
    override val thinking = "Let me think..."
    override val reading = "Reading..."

    override val setupTitle = "First time here. Please turn on these three."
    override val permissionOverlayTitle = "Show the helper button"
    override val permissionOverlayBody = "Keeps the little helper button on your screen"
    override val permissionAccessibilityTitle = "Read the screen"
    override val permissionAccessibilityBody = "So I can read out what is on your screen"
    override val permissionMicrophoneTitle = "Use the microphone"
    override val permissionMicrophoneBody = "So you can simply talk to me"
    override val grant = "Turn on"
    override val granted = "On"
    override val startHelper = "Start helper"
    override val stopHelper = "Stop helper"

    override val settings = "Settings"
    override val aiServiceTitle = "Assistant"
    override val useEverCareService = "Use the EverCare service"
    override val useEverCareServiceBody = "Monthly plan, nothing to set up"
    override val useOwnProvider = "Use my own provider"
    override val useOwnProviderBody = "Enter your own address, key and model"
    override val serverUrl = "EverCare server address"
    override val baseUrl = "Base URL"
    override val apiKey = "API key"
    override val model = "Model"
    override val save = "Save"
    override val saved = "Saved"
    override val subscriptionActiveUntil = "Active until"
    override val subscriptionInactive = "Your plan has ended"
    override val trialDaysLeft = "Trial days left"

    override val errorNoScreenText = "I could not find any text on this screen"
    override val errorNetwork = "The connection seems slow. Let us try again in a moment"
    override val errorNotConfigured = "The assistant is not set up yet. Please choose one in Settings"
    override val errorScreenReadingUnsupported = "This phone cannot read the screen text"
    override val errorMicrophoneUnavailable = "The microphone is not available right now"
}

object JapaneseStrings : Strings {
    override val languageTag = "ja-JP"
    override val appName = "長伴"
    override val tagline = "スマホのやさしい相棒"

    override val readScreen = "読んで"
    override val explainScreen = "これは何？"
    override val askQuestion = "聞きたい"
    override val stopSpeaking = "とめる"
    override val closePanel = "しまう"
    override val listening = "聞いています……"
    override val thinking = "考えています……"
    override val reading = "読んでいます……"

    override val setupTitle = "はじめに、3つの設定をオンにしてください"
    override val permissionOverlayTitle = "ボタンを表示する"
    override val permissionOverlayBody = "画面にいつでも押せる小さなボタンを出します"
    override val permissionAccessibilityTitle = "画面の文字を読む"
    override val permissionAccessibilityBody = "画面の文字を読み上げるために使います"
    override val permissionMicrophoneTitle = "マイクを使う"
    override val permissionMicrophoneBody = "話しかけて質問できるようになります"
    override val grant = "オンにする"
    override val granted = "オン"
    override val startHelper = "相棒を出す"
    override val stopHelper = "相棒をしまう"

    override val settings = "設定"
    override val aiServiceTitle = "アシスタント"
    override val useEverCareService = "長伴のサービスを使う"
    override val useEverCareServiceBody = "月額制。設定は要りません"
    override val useOwnProvider = "自分の接続先を使う"
    override val useOwnProviderBody = "アドレス・キー・モデルを入力します"
    override val serverUrl = "長伴サーバーのアドレス"
    override val baseUrl = "アドレス"
    override val apiKey = "キー"
    override val model = "モデル"
    override val save = "保存"
    override val saved = "保存しました"
    override val subscriptionActiveUntil = "有効期限"
    override val subscriptionInactive = "期限が切れました"
    override val trialDaysLeft = "お試しの残り"

    override val errorNoScreenText = "この画面には文字が見つかりませんでした"
    override val errorNetwork = "つながりにくいようです。少ししてからもう一度どうぞ"
    override val errorNotConfigured = "アシスタントが未設定です。設定から選んでください"
    override val errorScreenReadingUnsupported = "このスマホでは画面の文字を読めません"
    override val errorMicrophoneUnavailable = "いまマイクが使えません"
}
