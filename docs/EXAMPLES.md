# Examples

Foundry Local uses the same coroutine-based model and inference APIs in IPC and embedded modes.

> **Preview:** Foundry Local for Android is under active development. The API surface and supported
> capabilities continue to grow and may change between preview releases. Pin the AAR version used by
> your app and review the release notes before upgrading.

The selected release AAR determines where inference runs. The examples below import only
`com.microsoft.foundrylocal.api.*`.

## Runnable sample apps

The repository includes complete applications that use the shared API:

### IPC mode

- [ApiExplorerAppIPC](../examples/ipc/ApiExplorerAppIPC) — connection, catalog, model download and
  cancellation, load and unload, chat, streaming, reconnection, and cache removal

### Embedded mode

- [ChatAppEmbedded](../examples/embedded/ChatAppEmbedded) — chat, streaming responses, and live voice input
- [AudioTranscriptionAppEmbedded](../examples/embedded/AudioTranscriptionAppEmbedded) — file, streaming,
  and live audio transcription

Use the sample application closest to your feature, then refer to the focused recipes below.

## Initialize Foundry Local

```kotlin
import android.content.Context
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager

suspend fun createManager(context: Context): FoundryLocalManager {
    return FoundryLocalManager.create(
        context = context,
        config = Configuration(appName = "MyApp")
    )
}
```

IPC callers can also observe connection loss:

```kotlin
suspend fun createIpcManager(context: Context): FoundryLocalManager {
    return FoundryLocalManager.create(
        context = context,
        config = Configuration(appName = "MyApp"),
        onDisconnected = {
            mainHandler.post { showReconnectAction() }
        }
    )
}
```

Embedded mode does not invoke `onDisconnected`.

## Get the model selected by your app

Pass the selected catalog alias into the model lifecycle:

```kotlin
suspend fun getSelectedModel(
    manager: FoundryLocalManager,
    modelAlias: String
): Model {
    return manager.getCatalog().getModel(modelAlias)
}
```

## Download with progress

```kotlin
suspend fun downloadModel(model: Model) {
    if (!model.isCached()) {
        model.download(progress = { progress ->
            viewModelScope.launch(Dispatchers.Main) {
                progressBar.progress = progress.toInt()
            }
        })
    }
}
```

Run the download in a lifecycle-owned coroutine. Cancel that coroutine to cancel the download.

## Load and create a chat client

```kotlin
suspend fun createChatClient(model: Model): ChatClient {
    if (!model.isLoaded()) {
        model.load()
    }

    return model.createChatClient()
}
```

Create the client once and reuse it until the model is unloaded or an IPC connection is replaced.

## Complete a chat request

```kotlin
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage

suspend fun completeChat(chatClient: ChatClient): String {
    val response = chatClient.completeChat(
        ChatCompletionRequest(
            messages = listOf(
                ChatMessage.system("Answer in one sentence."),
                ChatMessage.user("What is on-device inference?")
            )
        )
    )

    return response.message?.content.orEmpty()
}
```

## Stream a chat response

```kotlin
val streamingJob = viewModelScope.launch(Dispatchers.IO) {
    chatClient.completeChatStreaming(request).collect { chunk ->
        withContext(Dispatchers.Main) {
            appendAnswer(chunk.delta)
        }
    }
}
```

Stop generation by cancelling the collecting job:

```kotlin
streamingJob.cancel()
```

## Maintain conversation history

```kotlin
val conversation = mutableListOf<ChatMessage>()

suspend fun sendMessage(text: String): String {
    conversation += ChatMessage.user(text)

    val response = chatClient.completeChat(
        ChatCompletionRequest(messages = conversation)
    )

    val assistant = response.message
        ?: error("The model returned no message")

    conversation += assistant
    return assistant.content
}
```

Trim older turns according to the selected model's context limits.

## Handle errors and cancellation

```kotlin
suspend fun loadModel(model: Model) {
    try {
        model.load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: FoundryLocalException) {
        Log.e(TAG, "Model load failed: code=${error.errorCode}", error)
        showError(error.message ?: "Model load failed")
    }
}
```

Do not log prompts, responses, transcripts, or raw audio.

## Reconnect in IPC mode

```kotlin
suspend fun reconnect(savedAlias: String) {
    manager.reconnect()

    val catalog = manager.getCatalog()
    val model = catalog.getModel(savedAlias)

    if (model.isLoaded()) {
        chatClient = model.createChatClient()
    }
}
```

Reacquire all catalog, model, chat, and audio handles after reconnecting. Handles created before the
disconnection may be stale.

## Transcribe an audio file

```kotlin
import com.microsoft.foundrylocal.api.AudioTranscriptionRequest

suspend fun transcribeAudio(
    catalog: Catalog,
    modelAlias: String,
    audioFile: File
): String {
    val audioModel = catalog.getModel(modelAlias)
    if (!audioModel.isCached()) {
        audioModel.download()
    }
    if (!audioModel.isLoaded()) {
        audioModel.load()
    }

    val audioClient = audioModel.createAudioClient()
    val transcription = audioClient.transcribe(
        AudioTranscriptionRequest(filePath = audioFile.absolutePath)
    )

    return transcription.text
}
```

The calling app must be able to read the file. In IPC mode, the SDK opens it and passes a file
descriptor to the service.

## Stream file transcription

```kotlin
suspend fun streamTranscription(audioClient: AudioClient, audioFile: File) {
    audioClient.transcribeStreaming(
        AudioTranscriptionRequest(filePath = audioFile.absolutePath)
    ).collect { event ->
        updateTranscript(event.text, event.isFinal)
    }
}
```

Cancel the collecting coroutine to stop the operation.

## Transcribe live audio

```kotlin
class LiveTranscriber private constructor(
    private val session: AudioStreamSession
) {
    suspend fun push(audioBytes: ByteArray): AudioStreamResult {
        return session.pushAudioChunk(audioBytes)
    }

    suspend fun stop(): AudioStreamResult {
        return session.stop()
    }

    companion object {
        suspend fun start(audioClient: AudioClient): LiveTranscriber {
            val session = audioClient.createStreamSession(
                AudioStreamSettings(
                    sampleRate = 16000,
                    channels = 1,
                    bitsPerSample = 16
                )
            )
            return LiveTranscriber(session)
        }
    }
}
```

Call `LiveTranscriber.start(...)` once when capture begins, call `push(...)` for each microphone
buffer, and call `stop()` once when capture ends. Audio chunks must match the session settings.

## Release resources

Cancel active jobs before unloading their models:

```kotlin
suspend fun releaseResources(
    manager: FoundryLocalManager,
    model: Model,
    audioModel: Model,
    streamingJob: Job?
) {
    streamingJob?.cancelAndJoin()

    if (model.isLoaded()) {
        model.unload()
    }
    if (audioModel.isLoaded()) {
        audioModel.unload()
    }

    manager.close()
}
```

Run suspend cleanup in a coroutine and do not use the manager after closing it.

## See also

- [Integration Guide](INTEGRATION_GUIDE.md)
- [API Reference](API_REFERENCE.md)
- [Best Practices](BEST_PRACTICES.md)
- [Troubleshooting](TROUBLESHOOTING.md)
