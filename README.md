# EverCare / 长伴

A phone assistant for elderly users, built as a floating helper rather than an app
they have to learn.

One round button sits on top of whatever they are doing. Tapping it offers three
things, each a single tap:

| Button | What it does |
| --- | --- |
| **读给我听** / Read to me | Reads the text on the current screen aloud |
| **这是什么** / What is this? | Asks the assistant to explain the screen in plain words |
| **我要问** / Ask me | Listens to a spoken question and answers it out loud |

There is no home screen to navigate, no chat history to scroll and no settings the
user has to understand. The setup screen exists for whoever sets the phone up,
which is usually a family member.

## Repository layout

```
EverCare/
├── composeApp/          Kotlin Multiplatform + Compose Multiplatform client
│   └── src/
│       ├── commonMain/  Shared UI, domain contracts, chat client, i18n
│       ├── androidMain/ Accessibility service, floating overlay, TTS/STT
│       └── iosMain/     iOS implementations (must be compiled on macOS)
└── server/              Rust service: OpenAI-compatible gateway + subscriptions
```

## How the two AI modes work

Both modes end up as the same three values — a base URL, a bearer token and a
model — so there is exactly one HTTP path in the app:

- **EverCare service.** The handset registers itself on first use, gets a device
  token and a trial, and talks to the Rust server. The server holds the provider
  key and injects the elder-friendly system prompt in the user's language.
- **Your own provider.** The user (or their family) enters a base URL, an API key
  and a model name for any OpenAI-compatible endpoint — DeepSeek, OpenAI, a local
  vLLM or Ollama gateway. Nothing touches EverCare's servers in this mode.

Because the Rust server speaks the same OpenAI protocol it proxies, a third-party
server is a configuration change rather than a second code path.

## Language

One language tag drives four things at once: the UI strings, the AI system
prompt, the text-to-speech voice and the speech recogniser. A phone in Chinese
gets a Chinese-speaking assistant; a phone in Japanese gets a Japanese one. The
UI ships in Chinese, English and Japanese; the server's system prompts add Korean,
Spanish and French.

## Building

### Requirements on this machine

| Tool | Path / version |
| --- | --- |
| Build JDK | `D:\Software\Eclipse Adoptium\jdk-21.0.7.6-hotspot` (JDK 21) |
| Android SDK | `C:\Users\vintces\AppData\Local\Android\Sdk` (platforms 34–36) |
| Rust | 1.97.1 |

`java` on `PATH` is a JRE 8, so `JAVA_HOME` **must** be set explicitly or Gradle
will not start.

### Android

```bash
JAVA_HOME="/d/Software/Eclipse Adoptium/jdk-21.0.7.6-hotspot" ./gradlew :composeApp:assembleDebug
```

The APK lands in `composeApp/build/outputs/apk/debug/`.

`local.properties` must contain the SDK location with **forward slashes** — a
`.properties` file treats `\` as an escape character, so a Windows path with
backslashes silently becomes nonsense:

```properties
sdk.dir=C:/Users/vintces/AppData/Local/Android/Sdk
```

### Dependency mirrors

Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) and `dl.google.com`
are not reliably reachable from this machine, so `settings.gradle.kts` puts the
Aliyun mirrors first and keeps the canonical repositories as fallbacks. On an
unrestricted network the build works either way.

### Server

```bash
cd server
cp .env.example .env     # then set EVERCARE_UPSTREAM_API_KEY
cargo run
```

See [server/README.md](server/README.md) for the API.

### iOS

The iOS source set is written but **cannot be compiled on Windows** — Kotlin/Native
needs macOS for Apple targets, and the Xcode project is not generated. On a Mac,
create an app target that calls `MainViewController()` from the shared framework.

## Platform limits worth knowing before planning iOS work

iOS cannot reproduce the Android experience, and no amount of implementation
effort changes that:

- **No floating overlay.** iOS has no third-party equivalent of Android's
  `TYPE_APPLICATION_OVERLAY`. An app cannot draw over other apps.
- **No cross-app screen reading.** There is no counterpart to
  `AccessibilityService` available to third-party apps.

What iOS can do is the assistant itself — chat, text-to-speech, and (once wired
up) voice input — reached from the app rather than from a floating button. The
realistic substitutes for screen reading are a Share Extension or a screenshot
run through Vision OCR, both of which need the user to act first.

## Status

See the "Status" section of the project notes in `CLAUDE.md`. In short: the
Android app builds and the Rust server builds, runs and passes its tests. The
Android runtime behaviour — the overlay, the accessibility capture, TTS and STT —
has not been exercised on a device or emulator.

## Licence

[GNU Affero General Public License v3.0 or later](LICENSE). If you run a modified
version of the server as a network service, the AGPL requires you to offer its
source to its users.
