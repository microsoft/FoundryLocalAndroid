# API Reference

Foundry Local exposes one coroutine-based Kotlin API for IPC and embedded deployment modes.

> **Preview:** Foundry Local for Android is under active development. The API surface and supported
> capabilities continue to grow and may change between preview releases. Pin the AAR version used by
> your app and review the release notes before upgrading.

Import API types from:

```kotlin
import com.microsoft.foundrylocal.api.*
```

Suspend functions return values directly and throw `FoundryLocalException` on failure. Streaming
operations return Kotlin `Flow`.

## FoundryLocalManager

`FoundryLocalManager` initializes Foundry Local and provides access to the model catalog.

### Initialize the runtime

```kotlin
suspend fun createManager(context: Context): FoundryLocalManager {
    return FoundryLocalManager.create(
        context = context,
        config = Configuration(appName = "MyApp")
    )
}
```

### Access models and runtime information

```kotlin
suspend fun getCatalog(): Catalog
suspend fun getVersionInfo(): VersionInfo
suspend fun checkCompatibility(): CompatibilityInfo
suspend fun getAPIVersion(): String
```

### Close the manager

```kotlin
fun close()
```

`close()` releases manager resources. Do not use the manager after closing it.

### IPC connection management

The following members support service connection state in IPC mode. Embedded applications do not
need connection-management logic.

Register a disconnection callback during initialization:

```kotlin
suspend fun createIpcManager(context: Context): FoundryLocalManager {
    return FoundryLocalManager.create(
        context = context,
        config = Configuration(appName = "MyApp"),
        onDisconnected = {
            // Post to the main thread before updating UI.
        }
    )
}
```

Connection members:

```kotlin
val isConnected: Boolean
suspend fun reconnect()
suspend fun isServiceRunning(): Boolean
```

- `isConnected` reports the current service-binding state.
- `reconnect()` re-establishes the service connection.
- `isServiceRunning()` checks whether the service runtime is reachable.

> **Good to know:** Reacquire `Catalog`, `Model`, `ChatClient`, and `AudioClient` handles after an
> IPC reconnection.

## Configuration

```kotlin
data class Configuration(
    val appName: String,
    val modelCacheDir: String? = null,
    val logLevel: String = "Information",
    val additionalSettings: Map<String, String>? = null,
    val azureCatalogFilter: String? = null,
    val disableTelemetry: Boolean = false
)
```

| Property | Description |
|---|---|
| `appName` | Application name used to identify the client. |
| `modelCacheDir` | Optional model-cache directory. `null` uses the runtime default. |
| `logLevel` | Runtime log level. |
| `additionalSettings` | Optional runtime settings. Use only documented keys. |
| `azureCatalogFilter` | Optional catalog filter. Use only when documented for the release. |
| `disableTelemetry` | Disables telemetry collection when `true`; defaults to `false`. |

## Catalog

The catalog lists models available to the selected runtime and returns model handles.

```kotlin
suspend fun listModels(): List<ModelInfo>
suspend fun getModel(modelAlias: String): Model
suspend fun getModelInfo(modelAlias: String): ModelInfo
suspend fun getCachedModels(): List<ModelInfo>
suspend fun getLoadedModels(): List<ModelInfo>
suspend fun getCacheLocation(): String
suspend fun setCacheLocation(directory: String)
```

Pass the alias selected by your application:

```kotlin
suspend fun getSelectedModel(
    manager: FoundryLocalManager,
    modelAlias: String
): Model {
    return manager.getCatalog().getModel(modelAlias)
}
```

> **Good to know:** Catalog contents and aliases can change between releases. Do not treat an alias
> copied from a guide as a stable identifier.

## Model

A `Model` represents one catalog model and controls its local lifecycle.

```kotlin
val info: ModelInfo

suspend fun download(
    progress: ((Float) -> Unit)? = null,
    contentIntent: PendingIntent? = null,
    timeoutMinutes: Int = 0
)

suspend fun load()
suspend fun unload()
suspend fun isDownloading(): Boolean
suspend fun isCached(): Boolean
suspend fun isLoaded(): Boolean
suspend fun removeFromCache()
suspend fun createChatClient(): ChatClient
suspend fun createAudioClient(): AudioClient
```

The required order is:

1. Get the model from the catalog.
2. Download it if it is not cached.
3. Load it.
4. Create a client supported by the model.
5. Unload it when inference is complete.
6. Remove it from the cache only after unloading.

`download()` supports coroutine cancellation. Its optional progress callback reports values from
0 to 100. A timeout of `0` disables the stalled-download timeout.

## ChatClient

Create a chat client from a loaded chat model:

```kotlin
suspend fun createChatClient(model: Model): ChatClient {
    return model.createChatClient()
}
```

### One-shot completion

```kotlin
suspend fun completeChat(request: ChatCompletionRequest): ChatCompletion
```

```kotlin
suspend fun completeChat(chatClient: ChatClient): String {
    val response = chatClient.completeChat(
        ChatCompletionRequest(
            messages = listOf(ChatMessage.user("Hello"))
        )
    )

    return response.message?.content.orEmpty()
}
```

### Streaming completion

```kotlin
fun completeChatStreaming(
    request: ChatCompletionRequest
): Flow<ChatCompletionChunk>
```

```kotlin
suspend fun streamChat(chatClient: ChatClient, request: ChatCompletionRequest) {
    chatClient.completeChatStreaming(request).collect { chunk ->
        appendText(chunk.delta)
    }
}
```

Cancel the collecting coroutine to stop generation.

## Chat data types

### ChatCompletionRequest

```kotlin
data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val stop: List<String>? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null
)
```

Support and valid ranges can vary by model. Start with defaults and set an option only when the
selected model documents it.

### ChatMessage

```kotlin
data class ChatMessage(
    val role: String,
    val content: String
)
```

Use the helpers:

```kotlin
ChatMessage.system("Answer concisely.")
ChatMessage.user("What is on-device inference?")
ChatMessage.assistant("Inference executed on the device.")
```

Role constants are `ROLE_SYSTEM`, `ROLE_USER`, and `ROLE_ASSISTANT`.

### ChatCompletion

```kotlin
data class ChatCompletion(
    val id: String,
    val modelAlias: String? = null,
    val created: Long = 0,
    val message: ChatMessage? = null
)
```

### ChatCompletionChunk

```kotlin
data class ChatCompletionChunk(
    val id: String,
    val modelAlias: String? = null,
    val created: Long = 0,
    val delta: String = "",
    val role: String? = null
)
```

Concatenate `delta` values to assemble the streamed response.

## AudioClient

Create an audio client from a loaded audio model:

```kotlin
suspend fun createAudioClient(model: Model): AudioClient {
    return model.createAudioClient()
}
```

### File transcription

```kotlin
suspend fun transcribe(
    request: AudioTranscriptionRequest
): AudioTranscriptionResponse

fun transcribeStreaming(
    request: AudioTranscriptionRequest
): Flow<AudioTranscriptionEvent>
```

```kotlin
suspend fun transcribe(audioClient: AudioClient, audioFile: File): String {
    val response = audioClient.transcribe(
        AudioTranscriptionRequest(
            filePath = audioFile.absolutePath,
            language = "en"
        )
    )
    return response.text
}
```

The calling app must be able to read the supplied file path. In IPC mode, the SDK opens the file and
passes a file descriptor to the service, so the service does not need direct filesystem access.
Follow the sample application for the supported file-selection and storage flow.

### Real-time transcription

```kotlin
suspend fun createStreamSession(
    settings: AudioStreamSettings
): AudioStreamSession
```

```kotlin
suspend fun startLiveTranscription(audioClient: AudioClient): AudioStreamSession {
    return audioClient.createStreamSession(AudioStreamSettings())
}

suspend fun pushAudio(
    session: AudioStreamSession,
    audioBytes: ByteArray
): AudioStreamResult {
    return session.pushAudioChunk(audioBytes)
}

suspend fun stopLiveTranscription(
    session: AudioStreamSession
): AudioStreamResult {
    return session.stop()
}
```

Create one session when capture starts, reuse it for each audio buffer, and call `stop()` once when
capture ends. Do not push additional audio after `stop()`.

## Audio data types

```kotlin
data class AudioTranscriptionRequest(
    val filePath: String,
    val language: String? = null,
    val temperature: Float? = null
)

data class AudioTranscriptionResponse(
    val text: String,
    val language: String? = null,
    val duration: Double? = null
)

data class AudioTranscriptionEvent(
    val text: String,
    val isFinal: Boolean = false,
    val language: String? = null
)

data class AudioStreamSettings(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val language: String? = null
)

data class AudioStreamResult(
    val text: String = "",
    val isFinal: Boolean = false
)
```

## ModelInfo

`ModelInfo` describes a catalog model:

```kotlin
data class ModelInfo(
    val alias: String,
    val name: String,
    val displayName: String,
    val version: String,
    val fileSizeMb: Long,
    val task: String? = null,
    val deviceType: String? = null,
    val executionProvider: String? = null,
    val supportsToolCalling: Boolean = false,
    val maxOutputTokens: Int = 0,
    val minFLVersion: String? = null,
    val createdAt: Long = 0,
    val providerType: String? = null,
    val uri: String? = null,
    val modelType: String? = null,
    val publisher: String? = null,
    val license: String? = null,
    val licenseDescription: String? = null,
    val promptTemplate: PromptTemplate? = null
)
```

Treat optional fields as nullable catalog metadata. Use `alias` for subsequent catalog operations.

## PromptTemplate

`PromptTemplate` contains optional templates provided by catalog metadata:

```kotlin
data class PromptTemplate(
    val system: String? = null,
    val user: String? = null,
    val assistant: String? = null,
    val prompt: String? = null
)
```

The fields represent the system message, user message, assistant response, and complete prompt
templates respectively. Treat every field as optional.

## Runtime information

### VersionInfo

```kotlin
data class VersionInfo(
    val versionName: String,
    val versionCode: Long,
    val minClientVersionName: String,
    val minClientVersionCode: Long
)
```

### CompatibilityInfo

```kotlin
data class CompatibilityInfo(
    val isCompatible: Boolean,
    val sdkVersion: String,
    val appVersion: String,
    val message: String
)
```

`CompatibilityInfo` reports the SDK and runtime versions evaluated by
`manager.checkCompatibility()`.

## Errors

```kotlin
class FoundryLocalException(
    message: String,
    val errorCode: Int = 0,
    cause: Throwable? = null
) : Exception(message, cause)
```

Catch `FoundryLocalException` for SDK failures. Do not swallow coroutine cancellation:

```kotlin
suspend fun loadModel(model: Model) {
    try {
        model.load()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: FoundryLocalException) {
        handleFoundryError(error)
    }
}
```

Do not depend on undocumented numeric error codes. Use documented codes only when a release defines
their meaning, and always retain a fallback based on the exception message and operation context.

## See also

- [Integration Guide](INTEGRATION_GUIDE.md)
- [Best Practices](BEST_PRACTICES.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [IPC and embedded deployment modes](IPC_AND_EMBEDDED_MODES.md)
