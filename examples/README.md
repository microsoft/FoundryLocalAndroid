# Foundry Local for Android — Example Apps

Standalone example apps demonstrating different capabilities of the Foundry Local Android SDK. Each app is a self-contained Gradle project that consumes a distributed SDK AAR.

## Apps

> **Note:** If one of the app directories linked below is missing in your checkout, update to the latest release tag or the `main` branch.

| App | Description | Key SDK Features |
|-----|-------------|-----------------|
| [ApiExplorerAppIPC](ipc/ApiExplorerAppIPC/) | Interactive SDK API reference (modern API) | Full lifecycle: connect, catalog, download, load, chat, unload, cache management |
| [ChatAppEmbedded](embedded/ChatAppEmbedded/) | Chat with voice input using embedded mode ([setup](embedded/ChatAppEmbedded/README.md#prerequisites)) | In-process inference, no service app needed, streaming audio transcription |
| [AudioTranscriptionAppEmbedded](embedded/AudioTranscriptionAppEmbedded/) | Audio transcription using embedded mode | In-process audio transcription, no service app needed |

## Getting Started

Run all commands from the repository root.

### IPC examples

1. Install the [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).
2. Download `foundry-local-ipc-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in the example's `libs/` directory.

### Embedded examples

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in the example's `libs/` directory. No service app is required.

Release AARs are not committed to this repository. Each sample expects the verified AAR in its
`libs/` directory at build time.

See the [Integration Guide](../docs/INTEGRATION_GUIDE.md#prerequisites) for SDK distribution and setup details.

### Build and install

Invoke the app's Gradle module (`<ModuleName>` is the app name, such as
`ApiExplorerAppIPC`):

```bash
./gradlew :<ModuleName>:installDebug
```

## Architecture

Each app is designed for modularity and reuse:

- **ViewModel** — owns all SDK interaction logic; takes SDK objects as constructor params for testability
- **Screen** — standalone Composable functions with no direct SDK dependency
- **Connection helper** — isolated service binding logic, swappable connection implementation

This structure makes it straightforward to lift individual features into a consolidated Gallery App.
