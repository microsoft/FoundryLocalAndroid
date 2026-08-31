# Embedded Audio Transcription App

Foundry Local transcribes audio directly on an Android device; this sample runs file and microphone
transcription inside the application process using embedded mode.

## Build and Run

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching GitHub Release and verify
   its published SHA-256 hash.

2. Place the verified AAR in:

   ```text
   examples/embedded/AudioTranscriptionAppEmbedded/libs/
   ```

3. Build the app from the repository root:

   ```bash
   ./gradlew :AudioTranscriptionAppEmbedded:assembleDebug
   ```

4. Connect an Android device and install the app:

   ```bash
   ./gradlew :AudioTranscriptionAppEmbedded:installDebug
   ```

5. Open **Embedded Audio** from the device launcher. No Foundry Local service app is required.

> **Good to know:** Release AARs are not committed to this repository. The app cannot build until the
> verified embedded AAR is present in `libs/`, and only one AAR should be present there.

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
