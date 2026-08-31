# ChatAppEmbedded

Foundry Local runs chat and speech models directly on Android devices. This sample runs entirely
inside the application process using embedded mode, so no service app is required.

## What It Demonstrates

- **Embedded mode initialization** — model loading and inference without the Foundry Local service app
- **Chat completion** — synchronous and streaming responses
- **Voice input** — real-time audio transcription via microphone using the Nemotron model
- **Model lifecycle** — download, load, unload within a single process

## Deployment Characteristics

- Inference and native libraries run inside the application process.
- The app uses the shared `com.microsoft.foundrylocal.api.*` API.
- No service installation or service binding is required.

## Build and Run

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in:

   ```text
   examples/embedded/ChatAppEmbedded/libs/
   ```

2. Build the app from the repository root:

   ```bash
   ./gradlew :ChatAppEmbedded:assembleDebug
   ```

3. Connect an Android device and install the app:

   ```bash
   ./gradlew :ChatAppEmbedded:installDebug
   ```

4. Open **Embedded Chat** from the device launcher.

> **Good to know:** Release AARs are not committed to this repository. Place the verified
> embedded AAR in this sample's `libs/` directory before building, and keep only one AAR there.

## Permissions

- `RECORD_AUDIO` — for voice input (requested at runtime)
- `INTERNET` — for model catalog and download
