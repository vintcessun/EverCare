# EverCare / 长伴

一个面向老年人的 Android / iOS 跨平台辅助 App。

## 产品需求

* 使用 **Kotlin Multiplatform + Compose Multiplatform**。
* 核心交互采用**浮窗**，不设计复杂主界面，老人平时只需要通过浮窗完成主要操作。
* 浮窗操作必须非常简单，尽量做到点击一次即可完成对应功能。
* 能够获取当前手机屏幕上的文字，帮助老人阅读和理解当前页面。
* 支持屏幕文字朗读、文字辅助阅读以及 AI 对当前屏幕内容进行解释。
* 接入 DeepSeek 等大模型 API，提供一个友善、耐心、表达简单的智能管家。
* 支持语音交互，让老人可以直接通过说话向助手提问。
* AI 服务支持两种方式：
  * 使用 EverCare 提供的 AI 服务，按月付费。
  * 用户自行配置 API Provider、Base URL、API Key 和模型。
* **必须一并实现一个用 Rust 写的服务端，并且支持第三方的服务端。**
* 支持完整 **i18n 国际化**。
* 根据当前语言自动切换对应语言的 AI Prompt、语音识别和 TTS 语音助手。
* 中文环境使用中文助手，英文环境使用英文助手，其他语言同样使用对应语言的语音助手。
* Android 和 iOS 都需要实现尽可能一致的体验。
* 整体设计原则是：**老人无需学习复杂操作，通过一个简单浮窗即可使用辅助阅读和 AI 管家功能。**

## 仓库结构

```
EverCare/
├── composeApp/          # KMP + Compose Multiplatform 客户端
│   └── src/
│       ├── commonMain/  # 共享 UI、领域模型、AI 客户端、i18n
│       ├── androidMain/ # 无障碍服务、浮窗、TTS/STT 实现
│       └── iosMain/     # iOS 实现（需在 macOS 上编译）
└── server/              # Rust 服务端（EverCare AI 服务 + 订阅校验）
```

## 构建环境（本机已验证）

* **构建 JDK：JDK 21** — `D:\Software\Eclipse Adoptium\jdk-21.0.7.6-hotspot`
  PATH 上的 `java` 是 JRE 8，因此 **必须** 显式设置 `JAVA_HOME` 才能运行 `gradlew`。
* Android SDK：`C:\Users\vintces\AppData\Local\Android\Sdk`（写在 `local.properties`，不提交）。
* Gradle 9.2.0（wrapper）、AGP 9.3.2、Kotlin 2.4.20、Compose Multiplatform 1.12.0。
* Rust：`cargo 1.97.1`。
* 依赖下载经由本机代理 `127.0.0.1:7890`（已配置在 `~/.gradle/gradle.properties`）。
  依赖解析卡住时，先确认代理是否在运行。

构建 Android Debug APK：

```bash
JAVA_HOME="/d/Software/Eclipse Adoptium/jdk-21.0.7.6-hotspot" ./gradlew :composeApp:assembleDebug
```

## 平台能力边界（重要）

* **iOS 无法做到与 Android 完全一致**：iOS 不允许第三方 App 显示跨应用浮窗，也不提供跨应用读取
  屏幕文字的 API。iOS 侧提供 AI 对话、TTS 朗读与语音输入；"读取当前屏幕"需要改用
  Share Extension 或截图 + Vision OCR 的方案。不要假装这部分能力已经实现。
* Android 侧的屏幕取词依赖 **AccessibilityService**，浮窗依赖 **SYSTEM_ALERT_WINDOW**，
  两者都需要用户手动授权，首次启动需引导。
