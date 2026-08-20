# Troubleshooting

Foundry Local reports shared-API failures through `FoundryLocalException`. Start with the operation
that failed, confirm the selected deployment mode, and preserve the exception message and error code
in diagnostic logs.

## Manager creation fails

### "No Foundry Local SDK implementation found"

The application did not package a supported implementation.

1. Confirm that exactly one release AAR is in the app module's `libs/` directory.
2. Confirm the Gradle dependency points to that exact filename.
3. Remove stale AARs from previous builds.
4. Clean and rebuild the application.

Use one of:

```text
foundry-local-ipc-sdk-<version>.aar
foundry-local-embedded-sdk-<version>.aar
```

Do not package both unless the release explicitly supports that configuration.

### IPC manager cannot connect

IPC mode requires the Foundry Local service app.

1. Install or update the
   [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).
2. Confirm the SDK AAR and service app come from compatible releases.
3. Retry `FoundryLocalManager.create(...)`.
4. If a previously working connection was lost, use `manager.reconnect()` instead of creating
   duplicate managers.

After reconnecting, reacquire the catalog, model, and inference clients.

### Compatibility check reports false

```kotlin
suspend fun checkCompatibility(manager: FoundryLocalManager) {
    val compatibility = manager.checkCompatibility()
    if (!compatibility.isCompatible) {
        showUpdateMessage(compatibility.message)
    }
}
```

Update the IPC service app or use the SDK artifact required by the release. Do not ignore an
incompatible result and continue with model operations.

## Catalog is empty or unavailable

1. Confirm the app declares internet permission:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

2. Confirm the device has network access.
3. Retry `manager.getCatalog().listModels()`.
4. Log the exception type, message, and `errorCode` without logging prompts or user content.

Do not fall back to a hard-coded model alias when catalog discovery fails.

## Model alias is not found

Model aliases can change between releases. Query the current catalog:

```kotlin
suspend fun logAvailableModels(manager: FoundryLocalManager) {
    manager.getCatalog().listModels().forEach { info ->
        Log.d(TAG, "Available model: ${info.alias}, task=${info.task}")
    }
}
```

Use an alias from this result. If a previously saved alias is absent, prompt the user to select an
available model.

## Download fails or stalls

1. Confirm the model is not already being downloaded:

   ```kotlin
   suspend fun startDownload(model: Model) {
       if (!model.isDownloading()) {
           model.download(progress = ::updateProgress)
       }
   }
   ```

2. Confirm network connectivity and available storage.
3. Keep the download coroutine alive for the intended operation lifetime.
4. If using a timeout, increase it for slower networks or pass `0` to disable the stalled-download
   timeout.
5. Retry only after the previous download coroutine has completed or been cancelled.

Coroutine cancellation stops the download. Treat `CancellationException` as cancellation, not as a
download failure.

## Model does not load

Check lifecycle state in order:

```kotlin
suspend fun prepareModel(model: Model) {
    if (!model.isCached()) {
        model.download()
    }
    if (!model.isLoaded()) {
        model.load()
    }
}
```

If loading still fails:

1. Unload models that are no longer needed.
2. Release other memory-intensive resources owned by your app.
3. Preserve the `FoundryLocalException` details for support.

Avoid fixed universal memory thresholds; the required memory depends on the model and workload.

## Chat client creation fails

`createChatClient()` requires a loaded model that supports chat.

1. Verify `model.isLoaded()` is true.
2. Confirm the selected model is intended for chat.
3. Reacquire the model and client after an IPC reconnection.
4. Do not reuse a client after unloading its model.

The same rules apply to `createAudioClient()` with an audio-capable model.

## Chat response is empty

1. Confirm the request contains at least one non-empty user message.
2. Inspect `response.message` safely:

   ```kotlin
   val text = response.message?.content.orEmpty()
   if (text.isBlank()) {
       showError("The model returned an empty response")
   }
   ```

3. Retry with the default request options to isolate optional sampling settings.
4. Reduce conversation history according to the selected model's documented context limit.
5. Confirm streaming callers append `chunk.delta`, not the entire chunk object.

## Streaming stops unexpectedly

Streaming is tied to the collecting coroutine:

```kotlin
streamingJob = viewModelScope.launch(Dispatchers.IO) {
    chatClient.completeChatStreaming(request).collect { chunk ->
        withContext(Dispatchers.Main) {
            appendText(chunk.delta)
        }
    }
}
```

Check whether the scope or `Job` was cancelled by navigation, lifecycle destruction, or a user
action. In IPC mode, also check `manager.isConnected`.

If disconnected:

1. cancel the old streaming job;
2. reconnect the manager;
3. reacquire the catalog, model, and chat client; and
4. start a new request.

## Audio file cannot be transcribed

`AudioTranscriptionRequest.filePath` must identify a file readable by the calling app.

1. Confirm the file exists and is not empty.
2. Confirm its format is supported by the selected model.
3. Use the file-selection and storage flow demonstrated by the release sample application.
4. Close open writers before starting transcription.

In IPC mode, the SDK opens the file in the calling app and passes a file descriptor to the service.
The service does not need direct access to the app's filesystem path.

For real-time transcription, ensure audio chunks match the configured sample rate, channel count,
and bits per sample.

## IPC connection is lost

Register `onDisconnected` when creating the manager:

```kotlin
suspend fun createIpcManager(context: Context): FoundryLocalManager {
    return FoundryLocalManager.create(
        context,
        Configuration(appName = "MyApp"),
        onDisconnected = {
            mainHandler.post { showReconnectAction() }
        }
    )
}
```

Then reconnect from a coroutine:

```kotlin
suspend fun reconnect(
    manager: FoundryLocalManager,
    modelAlias: String
): Model {
    manager.reconnect()
    return manager.getCatalog().getModel(modelAlias)
}
```

Do not keep using handles acquired before reconnecting.

## Embedded app fails during startup

1. Confirm the embedded release AAR is packaged in the app.
2. Inspect the final APK or App Bundle to confirm native libraries from the AAR are present.
3. Confirm the device configuration is supported by the release.

Do not add separate native runtime libraries alongside the embedded AAR unless the release
instructions explicitly require them.

## Collect useful diagnostics

Record:

- deployment mode;
- SDK artifact version and SHA-256;
- service-app version for IPC mode;
- Android version and device model;
- operation name and model alias;
- exception type, message, and `errorCode`; and
- whether the failure reproduces after a clean restart.

Do not include prompts, generated responses, transcripts, raw audio, credentials, or user
identifiers in logs or support reports.

## See also

- [Integration Guide](INTEGRATION_GUIDE.md)
- [API Reference](API_REFERENCE.md)
- [Examples](EXAMPLES.md)
- [Best Practices](BEST_PRACTICES.md)
- [IPC and embedded deployment modes](IPC_AND_EMBEDDED_MODES.md)
