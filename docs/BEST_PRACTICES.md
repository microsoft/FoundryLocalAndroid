# Best Practices

Foundry Local runs inference on the Android device, so application lifecycle, coroutine ownership,
memory, and storage directly affect reliability.

## Use lifecycle-owned coroutines

Call suspend functions from a scope that has a clear owner, such as `viewModelScope`:

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    try {
        val response = chatClient.completeChat(request)
        withContext(Dispatchers.Main) {
            render(response.message?.content.orEmpty())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: FoundryLocalException) {
        withContext(Dispatchers.Main) {
            showError(error.message ?: "Inference failed")
        }
    }
}
```

Avoid unmanaged application-wide scopes for UI work. Keep a process-level scope only for an
operation that intentionally outlives an Activity, and expose its state back to the UI.

## Preserve coroutine cancellation

Downloads and streaming inference support coroutine cancellation. Always rethrow
`CancellationException` before handling other failures:

```kotlin
suspend fun collectResponse(
    chatClient: ChatClient,
    request: ChatCompletionRequest
) {
    try {
        chatClient.completeChatStreaming(request).collect(::appendChunk)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: FoundryLocalException) {
        report(error)
    }
}
```

Store the `Job` when the user needs a Cancel or Stop action.

## Follow the model lifecycle

Use the same sequence in IPC and embedded modes:

1. Discover a model with `catalog.listModels()`.
2. Get its handle with `catalog.getModel(info.alias)`.
3. Download it if `isCached()` is false.
4. Load it if `isLoaded()` is false.
5. Create and reuse the appropriate client.
6. Cancel active work before unloading.
7. Remove cached files only after unloading.

```kotlin
suspend fun prepareChatModel(catalog: Catalog, alias: String): Pair<Model, ChatClient> {
    val model = catalog.getModel(alias)
    if (!model.isCached()) {
        model.download()
    }
    if (!model.isLoaded()) {
        model.load()
    }
    return model to model.createChatClient()
}
```

Do not repeatedly create clients for the same loaded model. Reuse the client until the model is
unloaded or the IPC connection is replaced.

## Validate the selected model

Treat the model alias as application configuration and verify it against the current catalog:

```kotlin
suspend fun validateModel(catalog: Catalog, modelAlias: String): ModelInfo {
    return catalog.getModelInfo(modelAlias)
}
```

Handle a missing or incompatible configured model explicitly.

## Handle IPC disconnections

IPC mode can lose its service process independently of your app. Register `onDisconnected`, move
the UI into a disconnected state, and reconnect from a coroutine:

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

After `manager.reconnect()`:

1. Call `manager.getCatalog()` again.
2. Reacquire the model by alias.
3. Recheck cached and loaded state.
4. Recreate chat or audio clients.

Do not continue using handles acquired before the disconnection.

Embedded mode has no service connection. Its failures occur in your app process and cannot be
recovered through `reconnect()`.

## Keep UI work on the main thread

Run model operations away from the main thread. Progress callbacks and the IPC disconnection
callback are not guaranteed to execute on the main thread, so switch to `Dispatchers.Main` before
updating views or Compose state.

Avoid logging prompts, responses, transcripts, or raw audio. Log operation names, aliases, durations,
counts, and payload sizes instead.

## Manage memory deliberately

Loaded models consume device memory until unloaded:

- load only the models needed for the current experience;
- cancel inference before unloading its model;
- unload models when their owning feature is finished;
- avoid loading multiple large models unless the device has been tested for that workload; and
- in embedded mode, test alongside the rest of the app's memory-intensive features.

Do not use a fixed free-memory threshold copied from another app. Android memory pressure varies by
device, process state, model, and workload.

## Manage conversation history

Send only history needed for the current response:

```kotlin
val request = ChatCompletionRequest(
    messages = conversation.takeLast(maxMessages)
)
```

Context limits differ by model. Follow the selected model's documentation rather than using a
universal token limit. Preserve system instructions when trimming and avoid splitting a logical
user/assistant turn.

## Validate request parameters

Start with model defaults. Set sampling controls only when the selected model documents them and
your application needs them. Validate empty input before starting inference.

## Keep one deployment mode per app

Package either the IPC release AAR or the embedded release AAR. Do not include both unless the
specific release documents that combination as supported.

When switching modes, retest:

- initialization and cleanup;
- model cache location and disk use;
- memory pressure and process recovery;
- IPC installation and reconnection, when applicable; and
- final APK or App Bundle packaging.

## Close resources in dependency order

Cancel active jobs before releasing their dependencies:

```kotlin
suspend fun releaseResources(
    manager: FoundryLocalManager,
    model: Model,
    audioSession: AudioStreamSession?,
    streamingJob: Job?
) {
    streamingJob?.cancelAndJoin()
    audioSession?.stop()
    if (model.isLoaded()) {
        model.unload()
    }
    manager.close()
}
```

Run suspend cleanup from an appropriate coroutine. Do not issue new operations after `close()`.

## Network, privacy, and telemetry

Inference executes locally, but catalog access, model downloads, service-app installation or updates,
and telemetry can use the network. Do not promise that the application never communicates externally
unless the complete release configuration and application behavior have been verified.

Avoid putting secrets or user content in `additionalSettings`, logs, analytics, or exception
messages.

## See also

- [Integration Guide](INTEGRATION_GUIDE.md)
- [API Reference](API_REFERENCE.md)
- [Examples](EXAMPLES.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [IPC and embedded deployment modes](IPC_AND_EMBEDDED_MODES.md)
