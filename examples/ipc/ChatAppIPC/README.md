# ChatAppIPC

Foundry Local runs chat and speech models directly on Android devices. `ChatAppIPC` demonstrates
streaming chat and live voice input while inference runs in the Foundry Local service app.

## What It Demonstrates

1. Connect to the service with `FoundryLocalManager.create(...)`.
2. Download and load a chat model through the shared coroutine API.
3. Stream chat tokens with `completeChatStreaming(...)`.
4. Download and load the Nemotron speech model.
5. Capture microphone audio and transcribe it with an `AudioStreamSession`.
6. Clear stale model and client handles when the service disconnects.

> **Good to know:** This sample uses only `com.microsoft.foundrylocal.api.*`. It does not use the
> older IPC callback API, `FLResult`, or AIDL callback stubs.

## Build and Run

1. Download `foundry-local-ipc-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in:

   ```text
   examples/ipc/ChatAppIPC/libs/
   ```

2. Install the
   [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).

3. Build the app from the repository root:

   ```bash
   ./gradlew :ChatAppIPC:assembleDebug
   ```

4. Connect an Android device and install the app:

   ```bash
   ./gradlew :ChatAppIPC:installDebug
   ```

5. Open **Chat** from the device launcher.

> **Good to know:** Keep only one IPC AAR in the sample's `libs/` directory. Gradle loads every AAR
> in that directory, and multiple versions cause duplicate-class errors.

## Use Voice Input

1. Complete the chat model setup.
2. Wait for the Nemotron speech model to download and load.
3. Grant microphone permission.
4. Tap the microphone button and speak.
5. Tap stop to copy the final transcript into the chat input.

The chat model is `qwen2.5-coder-0.5b-instruct-generic-cpu:4`. Voice input uses
`nemotron-speech-streaming-en-0.6b-generic-cpu:3`, which requires an additional model download.
