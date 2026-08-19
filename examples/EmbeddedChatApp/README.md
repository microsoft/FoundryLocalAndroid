# EmbeddedChatApp

Chat interface running entirely in-process using the Foundry Local embedded SDK — no service app required.

## What It Demonstrates

- **Embedded mode initialization** — model loading and inference without the Foundry Local service app
- **Chat completion** — synchronous and streaming responses
- **Voice input** — real-time audio transcription via microphone using the Nemotron model
- **Model lifecycle** — download, load, unload within a single process

## How It Differs from ChatApp

| | ChatApp | EmbeddedChatApp |
|---|---------|-----------------|
| Deployment mode | IPC (service app required) | Embedded (self-contained) |
| Native libraries | In service app | Bundled in APK |
| SDK import | `com.microsoft.foundrylocal.*` (IPC) | `com.microsoft.foundrylocal.api.*` (shared API) |
| Service binding | Yes | No |

## Prerequisites

1. Download `foundry-local-embedded-sdk-{version}.aar` from
   [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases)
   and place it in `examples/EmbeddedChatApp/libs/`:
   ```bash
   mkdir -p examples/EmbeddedChatApp/libs
   cp foundry-local-embedded-sdk-{version}.aar examples/EmbeddedChatApp/libs/
   ```

2. Build and install:
   ```bash
   ./gradlew :EmbeddedChatApp:installDebug
   ```

## Permissions

- `RECORD_AUDIO` — for voice input (requested at runtime)
- `INTERNET` — for model catalog and download

The Embedded SDK implementation is not part of this repository. This sample
contains only customer integration code.
