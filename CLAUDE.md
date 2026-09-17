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

## 已确定的设计决定（不要推翻，除非用户另行要求）

* **订阅绑定设备 ID，不做账号系统。** 没有账号、密码、邮箱或手机号。App 首次启动自行
  注册设备并拿到 token，这个 token 就是全部身份。换手机时用
  `/v1/evercare/subscription/transfer` 把剩余天数迁移过去。
  （曾短暂实现过账号体系，已按用户要求移除，请勿再加回来。）
* **协议是 AGPLv3**（`LICENSE`）。服务端以网络服务形式提供修改版时必须开放源码。
* **两种 AI 模式共用一条代码路径。** 服务端自己就说 OpenAI 协议，所以"用自己的接口"
  只是换 base URL / token / model，不是第二套实现。
* **i18n 用 Kotlin 单一数据源**（`i18n/Strings.kt`），不用平台资源文件：同一个语言标签
  必须同时决定界面文字、AI system prompt、TTS 音色和语音识别语言，分开存放迟早会出现
  "界面中文、助手英文"。仅系统自身显示的字符串（launcher 名称、无障碍服务说明、通知
  渠道名）放在 `androidMain/res/values*/strings.xml`。

## 仓库结构

```
EverCare/
├── composeApp/          # KMP + Compose Multiplatform 客户端
│   └── src/
│       ├── commonMain/  # 共享 UI、领域接口、AI 客户端、i18n
│       ├── commonTest/  # 共享逻辑的单元测试
│       ├── androidMain/ # 无障碍服务、浮窗、TTS/STT 实现
│       └── iosMain/     # iOS 实现（需在 macOS 上编译）
└── server/              # Rust 服务端（OpenAI 兼容网关 + 订阅）
```

## 构建环境（本机已验证）

| 项目 | 版本 / 路径 |
| --- | --- |
| 构建 JDK | JDK 21 — `D:\Software\Eclipse Adoptium\jdk-21.0.7.6-hotspot` |
| Android SDK | `C:\Users\vintces\AppData\Local\Android\Sdk`（platforms 34–36） |
| Gradle | 9.2.0（wrapper） |
| AGP | **8.13.2** |
| Kotlin | 2.4.20 |
| Compose Multiplatform | **1.11.1** |
| ktor | **3.1.0** |
| Rust | 1.97.1 |

PATH 上的 `java` 是 JRE 8，**必须**显式设置 `JAVA_HOME` 才能运行 `gradlew`：

```bash
JAVA_HOME="/d/Software/Eclipse Adoptium/jdk-21.0.7.6-hotspot" ./gradlew :composeApp:assembleDebug
```

### 版本为什么卡在这几个数字上（不要随手往上升）

* **CMP 1.12.0 / AGP 9.x 升不上去**：CMP 1.12 依赖的 androidx.compose 1.12 要求
  `compileSdk 37` 且 AGP ≥ 9.1；AGP 9.x 又要求比本机缓存的 Gradle 9.2.0 更新的 Gradle
  （报 `NoClassDefFoundError: ProjectTypeBinding`）。本机只装了 platforms 34–36。
* **ktor 3.6.0 升不上去**：它带的 okhttp 5.5.0 同样要求 `compileSdk 37`。
* **iosX64 已移除**：CMP 1.11 不再发布该平台的构件。

### 依赖镜像（重要）

本机访问不到 Maven Central（`repo.maven.apache.org` / `repo1.maven.org`）和
`dl.google.com`，`settings.gradle.kts` 因此把**阿里云镜像放在最前面**，官方仓库留作
回退。不要删掉这些镜像配置，否则在这台机器上无法解析依赖。依赖下载经由本机代理
`127.0.0.1:7890`；卡住时先确认代理是否在运行。

### local.properties

必须使用**正斜杠**。`.properties` 文件里 `\` 是转义符，Windows 反斜杠路径会被悄悄吃掉，
报错是难以定位的 `IOException: 文件名、目录名或卷标语法不正确`：

```properties
sdk.dir=C:/Users/vintces/AppData/Local/Android/Sdk
```

## 平台能力边界（重要）

* **iOS 无法做到与 Android 完全一致**：iOS 不允许第三方 App 显示跨应用浮窗，也不提供
  跨应用读取屏幕文字的 API。iOS 侧提供 AI 对话与 TTS；"读取当前屏幕"需要改用
  Share Extension 或截图 + Vision OCR。**不要假装这部分能力已经实现。**
* Android 侧屏幕取词依赖 **AccessibilityService**，浮窗依赖 **SYSTEM_ALERT_WINDOW**，
  两者都需要用户手动授权，首次启动需引导。
* 浮窗里的语音输入需要前台服务类型包含 `microphone`，否则在别的 App 挡在前面时
  （也就是浮窗唯一的使用场景）拿不到麦克风。

## 当前状态

**已验证：**

* Android debug APK 能构建，装到模拟器（API 36，google_apis_playstore x86_64）上能启动，
  无崩溃；无障碍服务能被系统正常绑定（`dumpsys accessibility` 显示 Bound services）。
* Rust 服务端：23 个测试通过（12 单元 + 11 个走真实 HTTP 的集成测试），编译出的二进制
  实跑过注册设备 / 订阅状态 / 402 拦截 / 兑换 / 落盘。
* 共享 Kotlin 逻辑：16 个单元测试通过。

**未验证：**

* TTS 实际发声、语音识别、真实 DeepSeek 调用（模拟器无识别引擎、无真实 API key）。
* iOS 全部代码（Windows 上无法编译 Apple 目标，也没有生成 Xcode 工程）。

**已知待办：**

* `server/src/routes/subscription.rs` 的 `redeem` **没有校验支付凭据**，任何人 POST 都能
  白拿订阅。上线前必须接 App Store / Play / 微信支付的收据校验。
* 客户端 `explainScreen` 自己拼 prompt，没有调用服务端的 `/v1/evercare/explain`，所以
  "改服务端就能改措辞"目前还不成立。

## 测试

```bash
# 共享 Kotlin 逻辑
JAVA_HOME="/d/Software/Eclipse Adoptium/jdk-21.0.7.6-hotspot" ./gradlew :composeApp:testDebugUnitTest

# 服务端
cd server && cargo test
```
