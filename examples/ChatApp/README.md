# Chat App

A minimal "Hello World" example demonstrating the Foundry Local Android SDK for on-device AI chat.

## Quick Start

### Prerequisites

1. The [Foundry Local companion app](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app)
   installed on your device
2. `foundry-local-ipc-sdk-{version}.aar` downloaded from
   [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases)
   and placed in the app's `libs/` directory

### Setup Steps

#### Step 1: Build ChatApp

```bash
# Build and install from the repository root
./gradlew :ChatApp:assembleDebug
./gradlew :ChatApp:installDebug
```

## How It Works

This app demonstrates the essential flow for using Foundry Local:

1. **Connect** to the Foundry Local service
2. **Download** the AI model (`qwen2.5-coder-0.5b-instruct-generic-cpu:4`)
3. **Load** the model into memory
4. **Chat** with the AI model

## Voice Input (Speak-to-Chat)

The app supports voice input using the **Nemotron streaming speech model**. Once setup finishes, you can tap the microphone button in the chat input area, speak, and the transcribed text is used as the chat prompt.

### Setting Up Voice Input

1. Complete the normal chat setup flow
2. The app automatically downloads and loads the Nemotron streaming model (`nemotron-speech-streaming-en-0.6b-generic-cpu:3`) in the background
3. Grant microphone permission when prompted
4. The mic icon in the chat input becomes enabled once voice input is ready

### Using Voice Input

1. Tap the **🎤 mic button** to start listening
2. Speak your message — a live transcript appears above the input field
3. Tap the **⏹ stop button** when finished — the final transcript fills the text input
4. Tap **Send** to send the message (or edit the text first)

> **Note:** Voice setup happens automatically after the chat model is ready. The voice model download is approximately 600 MB.

## Project Structure

```
ChatApp/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/microsoft/foundrylocal/chatapp/
│   │   ├── MainActivity.kt         # Entry point
│   │   ├── ChatViewModel.kt        # Business logic & state
│   │   └── ChatScreen.kt           # UI components
│   └── res/
│       └── values/
│           └── styles.xml
├── build.gradle.kts
└── proguard-rules.pro
```
