# Embedded Audio Transcription App

Foundry Local transcribes audio directly on an Android device; this sample runs file and microphone
transcription inside the application process using embedded mode.

## Quick Start

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in this sample's `libs/` directory.

2. Build and install the app from the repository root:

   ```bash
   ./gradlew :AudioTranscriptionAppEmbedded:assembleDebug
   ./gradlew :AudioTranscriptionAppEmbedded:installDebug
   ```

3. Launch **Embedded Audio**. No Foundry Local service app is required.

> **Good to know:** Release AARs are not committed to this repository. The app cannot build until the
> verified embedded AAR is present in `libs/`.

## What It Demonstrates

- One-shot and streaming transcription of a selected audio file
- A bundled **Use sample audio** flow
- Real-time microphone transcription
- Embedded model download, load, use, and cleanup
- The shared `com.microsoft.foundrylocal.api.*` coroutine API

## Models

| Model | Model ID | Use case |
|---|---|---|
| OpenAI Whisper Tiny | `openai-whisper-tiny-generic-cpu:4` | File and bundled-sample transcription |
| Nemotron Speech Streaming | `nemotron-speech-streaming-en-0.6b-generic-cpu:3` | Real-time microphone transcription |

The first run downloads the selected model. Keep the device online until preparation completes.

## Permissions

- `INTERNET` — retrieves the model catalog and downloads models
- `RECORD_AUDIO` — captures microphone audio for real-time transcription and is requested at runtime

File transcription uses Android's system file picker and does not require storage permission.

## Test the Sample

1. Open the **File** tab and prepare the Whisper model.
2. Tap **Use sample audio**, then run both **Transcribe** and **Streaming**.
3. Open the **Mic (Nemotron)** tab and prepare the Nemotron model.
4. Grant microphone permission, tap **Start listening**, speak, and tap **Stop**.
5. Confirm the final transcript appears after each flow completes.
