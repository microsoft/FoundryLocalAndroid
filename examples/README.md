# Foundry Local for Android — Example Apps

Standalone example apps demonstrating different capabilities of the Foundry Local Android SDK. Each app is a self-contained Gradle project that consumes the SDK via the fat AAR.

## Apps

> **Note:** If one of the app directories linked below is missing in your checkout, update to the latest release tag or the `main` branch.

| App | Description | Key SDK Features |
|-----|-------------|-----------------|
| [ApiExplorer2App](ApiExplorer2App/) | Interactive SDK API reference (modern API) | Full lifecycle: connect, catalog, download, load, chat, unload, cache management |
| [ChatApp](ChatApp/) | Polished chat interface with optional voice input | Chat completion (sync + streaming), real-time audio transcription for speak-to-chat |
| [AudioTranscriptionApp](AudioTranscriptionApp/) | File and real-time audio transcription | Whisper batch transcription, Nemotron streaming transcription, push-stream audio |
| [EmbeddedChatApp](EmbeddedChatApp/) | Chat with voice input using embedded mode ([setup](EmbeddedChatApp/README.md#prerequisites)) | In-process inference, no service app needed, streaming audio transcription |
| [ApiExplorerApp](ApiExplorerApp/) *(deprecated)* | Interactive SDK API reference (legacy API) | Callback-based lifecycle demo — will be removed in a future release |

## Getting Started

All example apps follow the same setup pattern:

### Prerequisites

1. **FoundryLocal service app** installed on device:
   ```bash
   # From the repo root
   ./gradlew :FoundryLocalApp:installDebug
   ```

2. **Fat AAR** built and copied to the app's `libs/` directory:
   ```bash
   ./gradlew :FoundryLocalIPCSDK:createFatAarDebug
   mkdir -p examples/<AppName>/libs
   cp ipc-service/FoundryLocalIPCSDK/build/outputs/aar/FoundryLocalIPCSDK-debug-fat.aar examples/<AppName>/libs/
   ```

### Build & Install

```bash
cd examples/<AppName>
./gradlew installDebug
```

> **⚠️ Warning:** For local development, you may need to add `-PskipCertSecurityCheck=true` to the `./gradlew` commands above. This flag disables certificate verification and **must only be used for local development builds**. Never use it in production, CI, or distributable builds.

## Architecture

Each app is designed for modularity and reuse:

- **ViewModel** — owns all SDK interaction logic; takes SDK objects as constructor params for testability
- **Screen** — standalone Composable functions with no direct SDK dependency
- **Connection helper** — isolated service binding logic, swappable connection implementation

This structure makes it straightforward to lift individual features into a consolidated Gallery App.

## E2E Testing

E2E tests live separately in [`testing/ipc-e2e/`](../testing/ipc-e2e/) (Gradle module `:ExampleApp`). These tests exercise the SDK directly and do not depend on any example app UI. See the [testing README](../testing/ipc-e2e/README.md) for details.
