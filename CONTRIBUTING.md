# Contributing to Foundry Local for Android

Thank you for your interest in contributing. This repository accepts
documentation fixes and improvements to the example integration applications.
The IPC SDK, Embedded SDK, native runtime, and companion app implementations
are not part of this repository.

## Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK 36
- Git
- The appropriate SDK AAR from
  [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases)

## Project Structure

| Path | Purpose |
|---|---|
| `docs/` | Integration, API, architecture, and troubleshooting documentation |
| `examples/` | IPC and Embedded customer integration samples |

## Building Examples

Place `foundry-local-ipc-sdk-{version}.aar` in the `libs/` directory of each
IPC example. Place `foundry-local-embedded-sdk-{version}.aar` in
the `libs/` directory of each Embedded example.

```bash
./gradlew :ApiExplorerAppIPC:assembleDebug
./gradlew :ChatAppEmbedded:assembleDebug
./gradlew :AudioTranscriptionAppEmbedded:assembleDebug
```

SDK AARs and other generated binaries must not be committed.

## Pull Requests

- Keep changes focused on documentation or customer-facing sample code.
- Update documentation when a sample integration pattern changes.
- Build every affected example against the applicable release AAR.
- Do not add SDK implementation, AIDL service implementation, native runtime,
  companion-app code, internal infrastructure, credentials, or release binaries.
- Include the SDK version and Android version used for validation.

## Reporting Issues

Use GitHub Issues with clear reproduction steps. Include the deployment mode,
SDK version, Android version, device model, and relevant logs. Do not include
credentials, private model data, or other sensitive information.
