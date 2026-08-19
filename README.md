<div align="center">
  <img alt="Foundry Local Android" src="assets/ic_launcher.png" height="100">

  <h1>Foundry Local for Android</h1>
  <p><strong>Run generative AI models directly on Android devices — no cloud, no cost per request, fully private.</strong></p>

  <p>
    <a href="#quick-start">Quick Start</a> •
    <a href="#examples">Examples</a> •
    <a href="#documentation">Docs</a> •
    <a href="#contributing">Contributing</a>
  </p>
</div>

---

## Quick Start

```kotlin
// Inside a CoroutineScope (e.g., viewModelScope, lifecycleScope)
val config = Configuration(appName = "my-app")
val manager = FoundryLocalManager.create(context, config)

val catalog = manager.getCatalog()
val model = catalog.getModel("phi-4-mini")
model.download { progress -> Log.d("DL", "$progress%") }
model.load()

val chat = model.createChatClient()
chat.completeChatStreaming(
    ChatCompletionRequest(messages = listOf(ChatMessage.user("Hello!")))
).collect { chunk ->
    print(chunk.delta)
}
```

That's it — model downloaded, loaded, and generating text on-device.

> **💻 Running on Windows or macOS?** See [Foundry Local](https://github.com/microsoft/Foundry-Local).

---

## How It Works

Foundry Local runs AI models entirely on-device. You choose where inference happens:

- **IPC mode** — Your app includes a thin SDK library (no native code). Inference runs in the Foundry Local service app, a separate process. This mode keeps your APK small and enables model sharing across apps, provided the [Foundry Local App](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app) has been downloaded. Preferred if you have strict APK size limitations.
- **Embedded mode** — Your app bundles the full inference engine including native libraries. No Foundry Local service app needed. Fully self-contained. Preferred if your app must work without depending on other installed apps.

> **Good to know:** Both modes share the same API. The code above works identically regardless of which mode you choose. Only initialization differs.

---

## Setup

### IPC Mode

1. Add the SDK AAR to your project's `libs/` folder
2. Add the dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation(fileTree("libs") { include("*.aar") })
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

3. Add `INTERNET` permission to your `AndroidManifest.xml` (required for model downloads):

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

4. Install the [Foundry Local App](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app) on the device

### Embedded Mode

1. Add the embedded SDK AAR to your project's `libs/` folder
2. Add the dependency and `INTERNET` permission (same as above)
3. No service app needed — inference runs in your process

For detailed setup instructions, see the [Integration Guide](docs/INTEGRATION_GUIDE.md).

---

## Features

- **Chat completions** with streaming support
- **Audio transcription** — batch (Whisper) and real-time (Nemotron)
- **Multi-turn conversations** with history
- **Model management** — download, load, unload, cache control
- **Progress tracking** for downloads
- **Configurable inference** — temperature, top-k, top-p, max tokens

---

## Examples

| App | Description |
|-----|-------------|
| [ApiExplorer2App](examples/ApiExplorer2App/) | Full SDK lifecycle using modern API — connect, catalog, download, load, chat, cleanup |
| [ChatApp](examples/ChatApp/) | Chat UI with optional voice input (speak-to-chat via Nemotron) |
| [AudioTranscriptionApp](examples/AudioTranscriptionApp/) | Batch transcription (Whisper) and real-time mic transcription (Nemotron) |
| [EmbeddedChatApp](examples/EmbeddedChatApp/) | Chat with voice input using embedded mode — no service app needed |
| [ApiExplorerApp](examples/ApiExplorerApp/) *(deprecated)* | Legacy callback-based SDK lifecycle demo |

Each app is self-contained. See the [examples README](examples/README.md) for setup instructions.

---

## Documentation

- **[Integration Guide](docs/INTEGRATION_GUIDE.md)** — Step-by-step setup and first inference
- **[API Reference](docs/API_REFERENCE.md)** — Complete class and method documentation
- **[IPC vs Embedded](docs/IPC_VS_EMBEDDED.md)** — Choosing a deployment mode
- **[Best Practices](docs/BEST_PRACTICES.md)** — Error handling, lifecycle, performance
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** — Common issues and solutions

---

## Building from Source

```bash
# Build everything
./gradlew assembleDebug -PskipCertSecurityCheck=true

# Run unit tests
./gradlew testDebugUnitTest -PskipCertSecurityCheck=true
```

> **Good to know:** The `-PskipCertSecurityCheck=true` flag bypasses caller certificate verification for local development. Production builds use a caller allowlist for security.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## Data Collection

The software may collect information about you and your use of the software and send it to Microsoft. Microsoft may use this information to provide services and improve our products and services. You may turn off the telemetry as described in the repository. There are also some features in the software that may enable you and Microsoft to collect data from users of your applications. If you use these features, you must comply with applicable law, including providing appropriate notices to users of your applications together with a copy of Microsoft's privacy statement. Our privacy statement is located at https://go.microsoft.com/fwlink/?LinkID=824704. You can learn more about data collection and use in the help documentation and our privacy statement. Your use of the software operates as your consent to these practices.

### How to Disable Telemetry

```kotlin
val config = Configuration(appName = "my-app", disableTelemetry = true)
```

---

## Trademarks

This project may contain trademarks or logos for projects, products, or services. Authorized use of Microsoft trademarks or logos is subject to and must follow [Microsoft's Trademark & Brand Guidelines](https://www.microsoft.com/en-us/legal/intellectualproperty/trademarks/usage/general). Use of Microsoft trademarks or logos in modified versions of this project must not cause confusion or imply Microsoft sponsorship. Any use of third-party trademarks or logos are subject to those third-party's policies.