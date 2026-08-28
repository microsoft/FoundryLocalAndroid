<div align="center">
  <img alt="Foundry Local Android" src="assets/ic_launcher.png" height="100">

  <h1>Foundry Local for Android</h1>
  <p><strong>Run generative AI models directly on Android devices — no cloud, no cost per request, fully private.</strong></p>

  <p>
    <a href="#quick-start">Quick Start</a> •
    <a href="#examples">Examples</a> •
    <a href="#documentation">Docs</a>
  </p>
</div>

---

## Quick Start

```kotlin
suspend fun runChat(context: Context, modelAlias: String) {
    val config = Configuration(appName = "my-app")
    val manager = FoundryLocalManager.create(context, config)

    val model = manager.getCatalog().getModel(modelAlias)
    model.download(progress = { progress -> Log.d("DL", "$progress%") })
    model.load()

    val chat = model.createChatClient()
    chat.completeChatStreaming(
        ChatCompletionRequest(messages = listOf(ChatMessage.user("Hello!")))
    ).collect { chunk ->
        print(chunk.delta)
    }
}
```

That's it — model downloaded, loaded, and generating text on-device.

The application supplies `modelAlias` from its model selection or configuration.

> **Running on Windows or macOS?** See [Foundry Local](https://github.com/microsoft/Foundry-Local).

---

## How It Works

Foundry Local runs AI models on the Android device. Choose one deployment mode:
- **IPC mode** — Your app includes a thin SDK library (no native code). Inference runs in the Foundry Local service app, a separate process. This mode keeps your APK small, provided the [Foundry Local App](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app) has been downloaded. Preferred if you have strict APK size limitations.
- **Embedded mode** — Your app bundles the full inference engine including native libraries. No Foundry Local service app needed. Fully self-contained. Preferred if your app must work without depending on other installed apps

Both modes expose the same Kotlin API. Their installation, packaging, process, storage, and lifecycle
behavior differ.

---

## Setup

Foundry Local distributes each deployment mode as a separate AAR. Add exactly one to your
application.

### IPC Mode

1. Download `foundry-local-ipc-sdk-<version>.aar` from the matching release.
2. Place it in your app module's `libs/` directory.
3. Add the required dependencies:

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/foundry-local-ipc-sdk-<version>.aar"))
       implementation("androidx.core:core-ktx:1.12.0")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<coroutines-version>")
   }
   ```

   AndroidX Core supplies the notification and foreground-service classes used during model
   downloads; those classes are not packaged in the IPC AAR.

4. Add internet permission for catalog access and model downloads:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

5. Install the
   [Foundry Local App](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app)
   on the device.

### Embedded Mode

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching release.
2. Place it in your app module's `libs/` directory.
3. Add the dependency:

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/foundry-local-embedded-sdk-<version>.aar"))
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<coroutines-version>")
   }
   ```

4. Add the same internet permission used for catalog access and model downloads.

No service app is required. Inference runs in your application process.

For detailed setup instructions, see the [Integration Guide](docs/INTEGRATION_GUIDE.md).

---

## Features

- Chat completions with streaming support
- Audio transcription for files and live audio
- Multi-turn conversations
- Model download, load, unload, and cache management
- Download progress and coroutine cancellation
- Optional chat request controls, including temperature, top-k, top-p, and maximum tokens

---

## Examples

### IPC mode

- [ApiExplorerAppIPC](examples/ipc/ApiExplorerAppIPC/) — connection, catalog, model lifecycle, chat,
  streaming, reconnection, and cache removal using the shared API
- [AudioTranscriptionAppIPC](examples/ipc/AudioTranscriptionAppIPC/) — file, streaming, and live audio
  transcription through the service app

### Embedded mode

- [ChatAppEmbedded](examples/embedded/ChatAppEmbedded/) — chat, streaming responses, and live voice input
- [AudioTranscriptionAppEmbedded](examples/embedded/AudioTranscriptionAppEmbedded/) — file, streaming, and
  live audio transcription

See the [Examples guide](docs/EXAMPLES.md) for focused code recipes.

---

## Documentation

- **[Integration Guide](docs/INTEGRATION_GUIDE.md)** — Step-by-step setup and first inference
- **[API Reference](docs/API_REFERENCE.md)** — Classes, methods, and data types
- **[Examples](docs/EXAMPLES.md)** — Shared-API recipes and runnable samples
- **[IPC and embedded deployment modes](docs/IPC_AND_EMBEDDED_MODES.md)** — Compare how each mode
  operates
- **[Best Practices](docs/BEST_PRACTICES.md)** — Coroutines, lifecycle, memory, and cleanup
- **[Troubleshooting](docs/TROUBLESHOOTING.md)** — Common integration and runtime problems

---

## Get the SDK

Download the versioned AAR for your deployment mode from
[GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases):

- `foundry-local-ipc-sdk-<version>.aar`
- `foundry-local-embedded-sdk-<version>.aar`

Verify the artifact SHA-256 published with the release before adding it to your application.

---

## Licensing

The repository's MIT license covers its documentation, sample applications, and repository tooling.
The SDK AARs are distributed under the license and notices included with those artifacts and are not
granted under the repository's MIT license.

---

## Data Collection

The software may collect information about you and your use of the software and send it to Microsoft.
Microsoft may use this information to provide services and improve our products and services. You may
turn off the telemetry as described below. There are also some features in the software that may
enable you and Microsoft to collect data from users of your applications. If you use these features,
you must comply with applicable law, including providing appropriate notices to users of your
applications together with a copy of Microsoft's privacy statement. Our privacy statement is
available at [Microsoft Privacy Statement](https://go.microsoft.com/fwlink/?LinkID=824704).

```kotlin
val config = Configuration(appName = "my-app", disableTelemetry = true)
```

---

## Trademarks

This project may contain trademarks or logos for projects, products, or services. Authorized use of
Microsoft trademarks or logos is subject to and must follow
[Microsoft's Trademark & Brand Guidelines](https://www.microsoft.com/en-us/legal/intellectualproperty/trademarks/usage/general).
Use of Microsoft trademarks or logos in modified versions of this project must not cause confusion
or imply Microsoft sponsorship. Third-party trademarks and logos are subject to their respective
policies.
