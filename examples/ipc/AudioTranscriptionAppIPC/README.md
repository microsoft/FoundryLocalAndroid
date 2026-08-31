# Audio Transcription App

Foundry Local transcribes audio directly on Android devices. This IPC sample demonstrates:

- **File-based transcription** using the Whisper model (sync + streaming)
- **Real-time microphone transcription** using the Nemotron streaming model

## Build and Run

1. Download `foundry-local-ipc-sdk-<version>.aar` from the matching GitHub Release and verify its
   published SHA-256 hash.

2. Place the verified AAR in:

   ```text
   examples/ipc/AudioTranscriptionAppIPC/libs/
   ```

3. Install the
   [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).

4. Build the app from the repository root:

   ```bash
   ./gradlew :AudioTranscriptionAppIPC:assembleDebug
   ```

5. Connect an Android device and install the app:

   ```bash
   ./gradlew :AudioTranscriptionAppIPC:installDebug
   ```

6. Open **Audio Transcription** from the device launcher.

> **Good to know:** Keep only one IPC AAR in the sample's `libs/` directory. Gradle loads every AAR
> in that directory, and multiple versions cause duplicate-class errors.

## How It Works

### File Transcription (Whisper)

1. Connect to the Foundry Local service
2. Download and load the `openai-whisper-tiny-generic-cpu:4` model
3. Pick an audio file from your device
4. Choose **Transcribe** (full result) or **Stream** (incremental partial results)

### Real-Time Transcription (Nemotron)

1. Connect to the Foundry Local service
2. Download and load the `nemotron-speech-streaming-en-0.6b-generic-cpu:3` model
3. Grant microphone permission
4. Tap **Start Recording** — speak and see live transcription
5. Tap **Stop Recording** to get the final transcript

## Models

| Model | Alias | Use Case |
|-------|-------|----------|
| OpenAI Whisper Tiny | `openai-whisper-tiny-generic-cpu:4` | File-based transcription (batch) |
| Nemotron Speech Streaming | `nemotron-speech-streaming-en-0.6b-generic-cpu:3` | Real-time microphone streaming |

## Project Structure

```
AudioTranscriptionAppIPC/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/microsoft/foundrylocal/audiotranscription/
│   │   ├── MainActivity.kt                  # Entry point
│   │   ├── AudioTranscriptionViewModel.kt   # Business logic & state
│   │   └── AudioTranscriptionScreen.kt      # Compose UI
│   └── res/
│       └── values/
│           └── styles.xml
├── build.gradle.kts
├── libs/                                     # Place SDK AAR here
└── proguard-rules.pro
```

## Permissions

- `INTERNET` — Required for model downloads
- `RECORD_AUDIO` — Required for real-time microphone transcription
- `POST_NOTIFICATIONS` — Required for displaying download/loading progress notifications
