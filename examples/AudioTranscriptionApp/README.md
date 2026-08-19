# Audio Transcription App

An example app demonstrating the Foundry Local Android SDK's audio transcription APIs with two modes:

- **File-based transcription** using the Whisper model (sync + streaming)
- **Real-time microphone transcription** using the Nemotron streaming model

## Quick Start

### Prerequisites

1. The [Foundry Local companion app](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app)
   installed on your device
2. `foundry-local-ipc-sdk-{version}.aar` downloaded from
   [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases)
   and placed in the app's `libs/` directory

### Build and Install

From the repository root:

```bash
./gradlew :AudioTranscriptionApp:assembleDebug
./gradlew :AudioTranscriptionApp:installDebug
```

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
AudioTranscriptionApp/
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
