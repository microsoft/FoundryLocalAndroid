# ChatAppEmbedded

Chat interface running entirely in-process using Foundry Local embedded mode — no service app required.

## What It Demonstrates

- **Embedded mode initialization** — model loading and inference without the Foundry Local service app
- **Chat completion** — synchronous and streaming responses
- **Voice input** — real-time audio transcription via microphone using the Nemotron model
- **Model lifecycle** — download, load, unload within a single process

## Deployment Characteristics

- Inference and native libraries run inside the application process.
- The app uses the shared `com.microsoft.foundrylocal.api.*` API.
- No service installation or service binding is required.

## Prerequisites

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in `libs/`.

2. Build and install:
   ```bash
   ./gradlew :ChatAppEmbedded:installDebug
   ```

> **Good to know:** Release AARs are not committed to the public repository. Place the verified
> embedded AAR in this sample's `libs/` directory before building.

## Permissions

- `RECORD_AUDIO` — for voice input (requested at runtime)
- `INTERNET` — for model catalog and download
