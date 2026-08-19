# EmbeddedAudioTranscriptionApp

An Embedded-mode example for file transcription with Whisper and real-time
microphone transcription with Nemotron. Inference runs inside the application
process and does not require the Foundry Local companion app.

## What It Demonstrates

- Embedded SDK initialization
- File selection and Whisper transcription
- Real-time microphone streaming with Nemotron
- Model download, load, status, and cleanup
- Coroutine and `Flow` integration

## Prerequisites

1. Download `foundry-local-embedded-sdk-{version}.aar` from
   [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases).
2. Place it in `examples/EmbeddedAudioTranscriptionApp/libs/`.
3. Build and install:

   ```bash
   ./gradlew :EmbeddedAudioTranscriptionApp:installDebug
   ```

The Embedded SDK implementation is not part of this repository. This sample
contains only customer integration code.

## Permissions

- `INTERNET` - model catalog access and downloads
- `RECORD_AUDIO` - real-time microphone transcription
