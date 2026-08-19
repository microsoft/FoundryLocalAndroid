# Foundry Local Android - API Reference

Complete reference documentation for all classes, methods, and types in the Foundry Local SDK.

## SDK Variants

Foundry Local for Android offers two SDK variants that share the same public API:

| Variant | Artifact | Description |
|---------|----------|-------------|
| **IPC SDK** | `com.microsoft.foundrylocal:ipc-sdk` | Communicates with a separate Foundry Local service app via AIDL IPC. The service hosts the native inference runtime. |
| **Embedded SDK** | `com.microsoft.foundrylocal:embedded-sdk` | Runs the native inference runtime directly inside the client app's process via JNI. No service app required. |

Both SDKs implement the same shared API interfaces (`FoundryLocalManager`, `Catalog`, `Model`, `ChatClient`). Code written against the shared API works with either SDK — just swap the dependency.

### Choosing a Variant

- **IPC SDK**: Use when you want the service app to manage model lifecycle independently, share models across apps, or keep inference memory out of your app's process.
- **Embedded SDK**: Use when you want a self-contained app with no external dependencies, simpler deployment, or need to bundle native libraries directly.

### Quick Start (Embedded SDK)

```kotlin
// The API is identical — only the Gradle dependency changes.
// With the embedded SDK on the classpath, FoundryLocalManager.create()
// automatically uses in-process inference.

val manager = FoundryLocalManager.create(context, Configuration(appName = "my-app"))
val catalog = manager.getCatalog()
val models = catalog.listModels()

val model = catalog.getModel("phi-4-mini")
model.download { progress -> Log.d("Demo", "Download: ${(progress * 100).toInt()}%") }
model.load()

val chatClient = model.createChatClient()
val request = ChatCompletionRequest(
    messages = listOf(ChatMessage(role = "user", content = "Hello!"))
)
val response = chatClient.completeChat(request)

// Streaming
chatClient.completeChatStreaming(request).collect { chunk ->
    print(chunk.delta)
}

manager.close()
```

> **Note:** Audio transcription (`createAudioClient()`) is not yet available in the embedded SDK. It will be added in a future release.

## Table of Contents

- [Configuration](#configuration)
- [FoundryLocalManager](#foundrylocalmanager)
- [Catalog](#catalog)
- [FoundryModel](#foundrymodel)
- [FoundryModelInfo](#foundrymodelinfo)
- [FoundryChatCompletionClient](#foundrychatcompletionclient)
- [ChatCompletionRequest](#chatcompletionrequest)
- [ChatMessage](#chatmessage)
- [ChatCompletion](#chatcompletion)
- [Audio Transcription](#audio-transcription)
  - [FoundryAudioTranscriptionClient](#foundryaudiotranscriptionclient)
  - [AudioTranscriptionCallback](#audiotranscriptioncallback)
  - [AudioTranscriptionRequest](#audiotranscriptionrequest)
  - [AudioTranscriptionResponse](#audiotranscriptionresponse)
  - [AudioStreamSettings](#audiostreamsettings)
  - [AudioStreamResult](#audiostreamresult)
- [FLResult](#flresult)
- [Callbacks](#callbacks)
- [Error Codes](#error-codes)

---

## Configuration

Configuration options for initializing the Foundry Local SDK.

```kotlin
data class Configuration(
    var appName: String = "foundry",
    var modelCacheDir: String? = null,
    var logLevel: String = "Information",
    var azureCatalogFilter: String? = null,
    var disableTelemetry: Boolean = false,
    var numDownloadThreads: Int = 4
)
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `appName` | `String` | `"foundry"` | **Required**. Your application name for identification |
| `modelCacheDir` | `String?` | `null` | Optional custom directory for caching models |
| `logLevel` | `String` | `"Information"` | Logging level (see [Log Levels](#log-levels)) |
| `azureCatalogFilter` | `String?` | `null` | Optional filter for Azure catalog models |
| `disableTelemetry` | `Boolean` | `false` | Set to `true` to disable telemetry collection |
| `numDownloadThreads` | `Int` | `4` | Number of threads for parallel model downloads (ExampleApp uses 16) |

### Log Levels

Valid values for `logLevel`:
- `"Verbose"` - Most detailed logging
- `"Debug"` - Debug information
- `"Information"` - General information (default)
- `"Warning"` - Warning messages only
- `"Error"` - Error messages only
- `"Fatal"` - Fatal errors only

### Example

```kotlin
val options = Configuration(
    appName = "MyApp",
    logLevel = "Debug",
    modelCacheDir = "/custom/cache/path",
    disableTelemetry = false,
    numDownloadThreads = 16
)
```

---

## FoundryLocalManager

Main entry point for the Foundry Local SDK. Manages service connection and provides access to the model catalog.

```kotlin
class FoundryLocalManager(options: Configuration)
```

### Constructor

```kotlin
FoundryLocalManager(options: Configuration)
```

**Parameters:**
- `options` - Configuration options for the SDK

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `isConnected` | `Boolean` | Whether the manager is currently connected to the service |

### Methods

#### connect()

Establishes connection to the Foundry Local service.

```kotlin
fun connect(
    context: Context?, 
    callback: FoundryServiceConnectionCallback?
): Boolean
```

**Parameters:**
- `context` - Android context (Activity or Application)
- `callback` - Optional callback for connection state changes

**Returns:** `Boolean` - `true` if connection initiated successfully, `false` otherwise

**Example:**
```kotlin
val connected = manager.connect(context, object : FoundryServiceConnectionCallback {
    override fun onServiceConnected(service: IFoundryLocalManager) {
        Log.d(TAG, "Connected")
    }
    
    override fun onServiceDisconnected(
        errorCode: FoundryServiceConnectionCallback.ErrorCode, 
        message: String?
    ) {
        Log.e(TAG, "Disconnected: ${message ?: "unknown"}")
    }
})
```

#### disconnect()

Disconnects from the Foundry Local service.

```kotlin
fun disconnect(context: Context?)
```

**Parameters:**
- `context` - Android context used during connection

**Note:** Always call this in `onDestroy()` to prevent resource leaks.

#### getCatalog()

Retrieves the model catalog.

```kotlin
fun getCatalog(): FLResult<Catalog>
```

**Returns:** `FLResult<Catalog>` - Result containing the catalog instance

**Example:**
```kotlin
val result = manager.getCatalog()
if (result.status) {
    val catalog = result.data!!
    // Use catalog
}
```

#### getAPIVersion()

Gets the API version of the connected service.

```kotlin
fun getAPIVersion(): FLResult<String>
```

**Returns:** `FLResult<String>` - Result containing version string

#### isServiceRunning()

Checks if the Foundry Local service is currently running.

```kotlin
fun isServiceRunning(): FLResult<Boolean>
```

**Returns:** `FLResult<Boolean>` - Result indicating service status

#### downloadModel()

Downloads a model by its alias.

```kotlin
fun downloadModel(
    modelAlias: String, 
    progressCallback: FoundryOperationProgressCallback
)
```

**Parameters:**
- `modelAlias` - The model identifier (e.g., "phi-3-mini-4k")
- `progressCallback` - Callback for download progress updates

#### listLoadedModels()

Lists all currently loaded models.

```kotlin
fun listLoadedModels(): FLResult<MutableList<FoundryModelInfo?>>
```

**Returns:** `FLResult<MutableList<FoundryModelInfo?>>` - Result containing list of loaded models

---

## Catalog

Provides access to the model catalog, cached models, and model instances.

> **Note**: Due to resource limitations on mobile devices, we recommend using models smaller than 3GB. Some recommended models include `phi-3-mini-4k`, `phi-3.5-mini`, `deepseek-r1-1.5b`, `qwen2.5-0.5b`, `qwen2.5-1.5b`, `qwen2.5-coder-0.5b`, and `qwen2.5-coder-1.5b`. See [foundrylocal.ai/models](https://www.foundrylocal.ai/models) for the latest model list.

```kotlin
class Catalog
```

### Methods

#### listModels()

Lists all available models in the catalog.

```kotlin
fun listModels(): FLResult<MutableList<FoundryModelInfo?>>
```

**Returns:** `FLResult<MutableList<FoundryModelInfo?>>` - Result containing list of available models

**Example:**
```kotlin
val result = catalog.listModels()
if (result.status) {
    val models = result.data!!
    models.forEach { modelInfo ->
        modelInfo?.let {
            println("${it.alias}: ${it.description}")
        }
    }
}
```

#### getModel()

Retrieves a specific model by its alias.

```kotlin
fun getModel(modelAlias: String): FLResult<FoundryModel>
```

**Parameters:**
- `modelAlias` - The model identifier (e.g., "phi-3-mini-4k")

**Returns:** `FLResult<FoundryModel>` - Result containing the model instance

**Example:**
```kotlin
val result = catalog.getModel("phi-3-mini-4k")
if (result.status) {
    val model = result.data!!
    // Use model
}
```

#### getModelInfo()

Gets detailed information about a specific model.

```kotlin
fun getModelInfo(modelAlias: String): FLResult<FoundryModelInfo?>
```

**Parameters:**
- `modelAlias` - The model identifier

**Returns:** `FLResult<FoundryModelInfo?>` - Result containing model information

#### getCachedModels()

Lists all models currently cached on the device.

```kotlin
fun getCachedModels(): FLResult<MutableList<FoundryModelInfo?>>
```

**Returns:** `FLResult<MutableList<FoundryModelInfo?>>` - Result containing list of cached models

#### getCacheLocation()

Gets the current cache directory path.

```kotlin
fun getCacheLocation(): FLResult<String>
```

**Returns:** `FLResult<String>` - Result containing cache directory path

#### setCacheLocation()

Sets a custom cache directory path.

```kotlin
fun setCacheLocation(directory: String): FLResult<Boolean>
```

**Parameters:**
- `directory` - Absolute path to the cache directory

**Returns:** `FLResult<Boolean>` - Result indicating success

**Note:** Must have write permissions for the specified directory.

#### removeCachedModel()

Removes a model from the cache.

```kotlin
fun removeCachedModel(modelAlias: String): FLResult<Boolean>
```

**Parameters:**
- `modelAlias` - The model identifier to remove

**Returns:** `FLResult<Boolean>` - Result indicating success

**Note:** Model must not be currently loaded.

---

## FoundryModel

Represents an AI model with methods for lifecycle management.

```kotlin
class FoundryModel
```

### Methods

#### download()

Downloads the model files to the device cache.

```kotlin
fun download(
    context: Context, 
    progressCallback: FoundryOperationProgressCallback
)
```

**Parameters:**
- `context` - Android context
- `progressCallback` - Callback for download progress

**Requirements:**
- Internet connectivity
- `INTERNET` permission
- `POST_NOTIFICATIONS` permission (Android 13+)
- Sufficient storage space

**Example:**
```kotlin
model.download(context, object : FoundryOperationProgressCallback {
    override fun onProgressUpdate(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        status: FoundryOperationProgressCallback.OperationStatus,
        progressPercent: Int,
        message: String?
    ) {
        updateProgressBar(progressPercent)
    }
    
    override fun onOperationComplete(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        successful: Boolean,
        errorMessage: String?
    ) {
        if (successful) {
            // Model ready to load
        }
    }
})
```

#### load()

Loads the model into memory for inference.

```kotlin
fun load(progressCallback: FoundryOperationProgressCallback)
```

**Parameters:**
- `progressCallback` - Callback for load progress

**Requirements:**
- Model must be downloaded/cached
- Sufficient device memory

**Note:** This operation can take several seconds depending on model size.

#### unload()

Unloads the model from memory.

```kotlin
fun unload(progressCallback: FoundryOperationProgressCallback)
```

**Parameters:**
- `progressCallback` - Callback for unload progress

**Note:** Always unload models when finished to free memory.

#### isCached()

Checks if the model is downloaded and cached.

```kotlin
fun isCached(): FLResult<Boolean>
```

**Returns:** `FLResult<Boolean>` - Result indicating if model is cached

#### isLoaded()

Checks if the model is currently loaded in memory.

```kotlin
fun isLoaded(): FLResult<Boolean>
```

**Returns:** `FLResult<Boolean>` - Result indicating if model is loaded

#### isDownloading()

Checks if the model is currently being downloaded by the service.

```kotlin
fun isDownloading(): FLResult<Boolean>
```

**Returns:** `FLResult<Boolean>` - Result indicating if a download is currently active for this model

**Notes:** This queries the service-side download state, so it is accurate even if the client app was restarted during a download.

#### createChatCompletionClient()

Creates a chat completion client for this model.

```kotlin
fun createChatCompletionClient(): FLResult<FoundryChatCompletionClient>
```

**Returns:** `FLResult<FoundryChatCompletionClient>` - Result containing the client

**Requirements:** Model must be loaded before creating a client.

---

## FoundryModelInfo

Contains metadata about a model.

```kotlin
class FoundryModelInfo
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `alias` | `String` | Model identifier (e.g., "phi-3-mini-4k") |
| `displayName` | `String?` | Human-readable display name |
| `description` | `String` | Human-readable description |
| `sizeInBytes` | `Long` | Model size in bytes |
| `fileSizeMb` | `Double?` | Model file size in megabytes |
| `format` | `String` | Model format (e.g., "GGUF") |
| `version` | `String?` | Model version |
| `publisher` | `String?` | Model publisher |

---

## FoundryChatCompletionClient

Client for performing chat completions with a loaded model.

```kotlin
class FoundryChatCompletionClient
```

### Methods

#### completeChat()

Performs a synchronous chat completion.

```kotlin
fun completeChat(request: ChatCompletionRequest): FLResult<ChatCompletion>
```

**Parameters:**
- `request` - The chat completion request configuration

**Returns:** `FLResult<ChatCompletion>` - Result containing the completion response

**Example:**
```kotlin
val request = ChatCompletionRequest().apply {
    messages.add(ChatMessage(ChatMessage.Role.USER, "Hello!"))
    temperature = 0.7f
    maxTokens = 100
}

val result = chatClient.completeChat(request)
if (result.status) {
    val completion = result.data!!
    val response = completion.message?.content
    println(response)
}
```

#### completeChatStreaming()

Performs a streaming chat completion.

```kotlin
fun completeChatStreaming(
    request: ChatCompletionRequest, 
    callback: ChatCompletionStreamingCallback
)
```

**Parameters:**
- `request` - The chat completion request configuration
- `callback` - Callback for streaming tokens

**Example:**
```kotlin
val responseBuilder = StringBuilder()

chatClient.completeChatStreaming(request, 
    object : IFoundryOperationProgressCallback.Stub() {
        override fun onProgressUpdate(
            operationType: Int,
            modelAlias: String,
            status: Int,
            progressPercent: Int,
            message: String
        ) {
            responseBuilder.append(message)
            updateUI(responseBuilder.toString())
        }
        
        override fun onOperationComplete(
            operationType: Int,
            modelAlias: String,
            successful: Boolean,
            errorMessage: String?
        ) {
            // Streaming complete
        }
    }
)
```

> **Tip**: You can also use `FoundryOperationProgressCallbackAdapter` for simpler callback implementations when you don't need to handle every callback method.

---

## ChatCompletionRequest

Configuration for a chat completion request.

```kotlin
class ChatCompletionRequest
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `messages` | `MutableList<ChatMessage>` | Empty list | Conversation history |
| `temperature` | `Float?` | `null` | Sampling temperature (0.0 - 2.0) |
| `maxTokens` | `Int?` | `null` | Maximum tokens to generate |
| `topP` | `Float?` | `null` | Nucleus sampling threshold (0.0 - 1.0) |
| `topK` | `Int?` | `null` | Top-K sampling value |
| `presencePenalty` | `Float?` | `null` | Presence penalty (-2.0 to 2.0) |
| `frequencyPenalty` | `Float?` | `null` | Frequency penalty (-2.0 to 2.0) |
| `stop` | `List<String>?` | `null` | Stop sequences |

### Parameter Details

#### temperature
Controls randomness in responses:
- `0.0` - Deterministic, always picks most likely token
- `1.0` - Default/balanced randomness
- `2.0` - Maximum randomness

**Use cases:**
- `0.0-0.3`: Factual tasks, code generation
- `0.7-0.9`: Creative writing, conversation
- `1.0-2.0`: Highly creative/experimental

#### maxTokens
Maximum number of tokens to generate. If not specified, generates until natural completion or model limit.

#### topP (Nucleus Sampling)
Only considers tokens whose cumulative probability is >= topP:
- `0.1` - Very focused, deterministic
- `0.9` - Balanced (recommended)
- `1.0` - Considers all tokens

#### topK
Only considers the K most likely tokens:
- Lower values (10-20): More focused
- Higher values (40-50): More diverse

#### presencePenalty
Penalizes tokens based on whether they appear in the text:
- Positive values: Encourage new topics
- Negative values: Stay on topic

#### frequencyPenalty
Penalizes tokens based on their frequency:
- Positive values: Reduce repetition
- Negative values: Allow repetition

#### stop
List of strings that stop generation when encountered:
```kotlin
stop = listOf("</s>", "\n\n", "END")
```

### Example

```kotlin
val request = ChatCompletionRequest().apply {
    messages.add(ChatMessage(ChatMessage.Role.SYSTEM, "You are a helpful assistant."))
    messages.add(ChatMessage(ChatMessage.Role.USER, "Tell me about AI"))
    
    temperature = 0.8f
    maxTokens = 200
    topP = 0.9f
    topK = 40
    presencePenalty = 0.1f
    frequencyPenalty = 0.1f
    stop = listOf("</s>")
}
```

---

## ChatMessage

Represents a single message in a conversation.

```kotlin
class ChatMessage(
    var role: Role,
    var content: String
)
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `role` | `Role` | The role of the message sender |
| `content` | `String` | The message text content |

### Role Enum

```kotlin
enum class Role {
    SYSTEM,     // System instructions/context
    USER,       // User input
    ASSISTANT   // AI assistant response
}
```

### Role Descriptions

- **SYSTEM**: Sets behavior, context, or instructions for the AI
- **USER**: Represents user input/questions
- **ASSISTANT**: AI-generated responses

### Example

```kotlin
// System message
val systemMsg = ChatMessage(
    ChatMessage.Role.SYSTEM, 
    "You are a helpful coding assistant."
)

// User message
val userMsg = ChatMessage(
    ChatMessage.Role.USER, 
    "How do I reverse a string in Kotlin?"
)

// Assistant message (from previous response)
val assistantMsg = ChatMessage(
    ChatMessage.Role.ASSISTANT, 
    "You can use .reversed() extension function."
)
```

---

## ChatCompletion

Response from a chat completion request.

```kotlin
class ChatCompletion
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique identifier for this completion |
| `modelAlias` | `String?` | The model that generated the response |
| `created` | `Long` | Unix timestamp of creation |
| `message` | `ChatMessage?` | The AI-generated response message |

### Example

```kotlin
val result = chatClient.completeChat(request)
if (result.status) {
    val completion = result.data!!
    println("ID: ${completion.id}")
    println("Model: ${completion.modelAlias}")
    println("Response: ${completion.message?.content}")
    println("Timestamp: ${completion.created}")
}
```

---

## FLResult

Generic result wrapper for SDK operations.

```kotlin
class FLResult<T>
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `status` | `Boolean` | `true` if operation succeeded, `false` if failed |
| `data` | `T?` | Result data (**guaranteed non-null when `status` is `true`**) |
| `error` | `FLError?` | Error information (**guaranteed non-null when `status` is `false`**) |

> **Important**: When `status` is `true`, the `data` field will never be null. You can safely use the non-null assertion operator (`!!`) after checking status.

### FLError

```kotlin
class FLError {
    var code: Int          // Error code (see Error Codes)
    var message: String    // Human-readable error message
}
```

### Usage Pattern

```kotlin
val result = catalog.getModel("model-name")

if (result.status) {
    // Success - data is guaranteed non-null
    val model = result.data!!
    // Use model
} else {
    // Failure - error is guaranteed non-null
    val error = result.error!!
    Log.e(TAG, "Error ${error.code}: ${error.message}")
}
```

---

## Callbacks

### FoundryServiceConnectionCallback

Callback for service connection state changes.

```kotlin
interface FoundryServiceConnectionCallback {
    fun onServiceConnected(service: IFoundryLocalManager)
    
    fun onServiceDisconnected(
        errorCode: ErrorCode, 
        message: String?
    )
    
    enum class ErrorCode {
        UNKNOWN,
        SERVICE_NOT_FOUND,
        PERMISSION_DENIED,
        CONNECTION_LOST
    }
}
```

### FoundryOperationProgressCallback

Callback for long-running operations (download, load, unload).

```kotlin
interface FoundryOperationProgressCallback {
    fun onProgressUpdate(
        operationType: OperationType,
        modelAlias: String,
        status: OperationStatus,
        progressPercent: Int,
        message: String?
    )
    
    fun onOperationComplete(
        operationType: OperationType,
        modelAlias: String,
        successful: Boolean,
        errorMessage: String?
    )
    
    enum class OperationType {
        DOWNLOAD,
        LOAD,
        UNLOAD,
        INFERENCE
    }
    
    enum class OperationStatus {
        STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
```

### FoundryOperationProgressCallbackAdapter

An adapter class that provides default (no-op) implementations of `FoundryOperationProgressCallback` methods. Extend this class and override only the methods you need.

```kotlin
open class FoundryOperationProgressCallbackAdapter : FoundryOperationProgressCallback {
    override fun onProgressUpdate(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        status: FoundryOperationProgressCallback.OperationStatus,
        progressPercent: Int,
        message: String?
    ) { /* no-op */ }
    
    override fun onOperationComplete(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        successful: Boolean,
        errorMessage: String?
    ) { /* no-op */ }
}
```

**Example:**
```kotlin
// Only handle completion, skip progress updates
model.load(object : FoundryOperationProgressCallbackAdapter() {
    override fun onOperationComplete(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        successful: Boolean,
        errorMessage: String?
    ) {
        if (successful) {
            Log.d(TAG, "Model loaded")
        }
    }
})
```

---

## Audio Transcription

Audio transcription client API support was added in **SDK 0.1.3**. It provides three
transcription modes: file-based synchronous, file-based streaming, and real-time PCM
streaming.

> **Note:** Runtime support also depends on the installed Foundry Local App (service)
> build implementing and enabling audio transcription. Even when using SDK 0.1.3 or
> later, calls may fail with `NOT_IMPLEMENTED` (`501`) if the service build does not support
> this feature.

### FoundryAudioTranscriptionClient

Client for running audio transcription inference against a loaded model.

```kotlin
class FoundryAudioTranscriptionClient
```

Obtain instances via `FoundryModel.createAudioTranscriptionClient()`.

#### transcribeAudio()

Transcribes an audio file synchronously.

```kotlin
fun transcribeAudio(request: AudioTranscriptionRequest): FLResult<AudioTranscriptionResponse>
```

**Parameters:**
- `request` - The transcription request with file path and optional parameters

**Returns:** `FLResult<AudioTranscriptionResponse>` - Result containing the transcription

**Example:**
```kotlin
val request = AudioTranscriptionRequest("/path/to/audio.mp3").apply {
    language = "en"
    temperature = 0.0f
}
val result = audioClient.transcribeAudio(request)
if (result.status) {
    val text = result.data!!.text
    println("Transcription: $text")
}
```

#### transcribeAudioStreaming()

Transcribes an audio file with streaming results via callback.

```kotlin
fun transcribeAudioStreaming(
    request: AudioTranscriptionRequest,
    callback: AudioTranscriptionCallback
): FLResult<Boolean>
```

**Parameters:**
- `request` - The transcription request with file path and optional parameters
- `callback` - An `AudioTranscriptionCallback` to receive streaming results

**Returns:** `FLResult<Boolean>` - `true` if the streaming operation started successfully

**Example:**
```kotlin
val callback = object : AudioTranscriptionCallback {
    override fun onPartialResult(text: String) {
        println("Partial: $text")
    }
    override fun onFinalResult(response: AudioTranscriptionResponse) {
        println("Final: ${response.text}")
    }
    override fun onError(errorMessage: String) {
        println("Error: $errorMessage")
    }
}
audioClient.transcribeAudioStreaming(request, callback)
```

#### startStream()

Starts a real-time streaming transcription session for live audio input.

```kotlin
fun startStream(settings: AudioStreamSettings): FLResult<String>
```

**Parameters:**
- `settings` - PCM format settings (sample rate, channels, bits per sample, language)

**Returns:** `FLResult<String>` - A session handle to use with `pushAudioChunk()` and `stopStream()`

#### pushAudioChunk()

Pushes a chunk of PCM audio data to an active streaming session.

```kotlin
fun pushAudioChunk(sessionHandle: String, audioData: ByteArray): FLResult<AudioStreamResult>
```

**Parameters:**
- `sessionHandle` - The session handle returned by `startStream()`
- `audioData` - Raw PCM audio bytes (must be ≤ 512 KB)

**Returns:** `FLResult<AudioStreamResult>` - Partial transcription result (`isFinal = false`)

#### stopStream()

Stops the streaming session and returns the final transcript.

```kotlin
fun stopStream(sessionHandle: String): FLResult<AudioStreamResult>
```

**Parameters:**
- `sessionHandle` - The session handle returned by `startStream()`

**Returns:** `FLResult<AudioStreamResult>` - Final transcription result (`isFinal = true`)

**Real-Time Streaming Example:**
```kotlin
// 1. Start a streaming session
val settings = AudioStreamSettings().apply {
    sampleRate = 16000
    channels = 1
    bitsPerSample = 16
    language = "en"
}
val sessionResult = audioClient.startStream(settings)
if (!sessionResult.status) {
    println("Error: ${sessionResult.error?.message}")
    return
}
val sessionHandle = sessionResult.data!!

// 2. Push PCM audio chunks as they are recorded
val pcmData: ByteArray = byteArrayOf() // Replace with actual audio data from AudioRecord
val partialResult = audioClient.pushAudioChunk(sessionHandle, pcmData)
if (partialResult.status) {
    println("Partial: ${partialResult.data!!.text}")
}

// 3. Stop and get the final transcript
val finalResult = audioClient.stopStream(sessionHandle)
if (finalResult.status) {
    println("Final: ${finalResult.data!!.text}")
}
```

### AudioTranscriptionCallback

Callback interface for streaming audio transcription results.

```kotlin
interface AudioTranscriptionCallback {
    fun onPartialResult(text: String)
    fun onFinalResult(response: AudioTranscriptionResponse)
    fun onError(errorMessage: String)
}
```

| Method | Description |
|--------|-------------|
| `onPartialResult(text)` | Called with partial transcription text as it becomes available |
| `onFinalResult(response)` | Called with the final complete transcription result |
| `onError(errorMessage)` | Called when an error occurs during transcription |

> **Note:** Callbacks are invoked on a binder thread. Post to the main thread before updating UI.

### AudioTranscriptionRequest

Request to transcribe an audio file.

```kotlin
val request = AudioTranscriptionRequest("/absolute/path/to/audio.mp3").apply {
    language = "en"
    temperature = 0.0f
}
```

#### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `filePath` | `String` | *(required)* | Absolute path to the audio file. Must be accessible to the Foundry Local service process. |
| `language` | `String?` | `null` | BCP-47 language hint (e.g., `"en"`, `"es"`). `null` uses auto-detection. |
| `temperature` | `Float?` | `null` | Sampling temperature (0.0–1.0). `null` uses model default. |

> **Important:** The file must be accessible to the Foundry Local service process. See `AudioTranscriptionRequest` class docs for guidance on scoped storage.

### AudioTranscriptionResponse

Response from an audio transcription request.

```kotlin
class AudioTranscriptionResponse : Parcelable
```

#### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `text` | `String` | `""` | The transcribed text content |
| `language` | `String?` | `null` | Detected or specified language (BCP-47 code) |
| `duration` | `Double?` | `null` | Duration of the audio in seconds |

### AudioStreamSettings

Settings for starting a real-time streaming transcription session.

```kotlin
class AudioStreamSettings : Parcelable
```

#### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sampleRate` | `Int` | `16000` | PCM sample rate in Hz |
| `channels` | `Int` | `1` | Number of audio channels (1 = mono, 2 = stereo) |
| `bitsPerSample` | `Int` | `16` | Bits per audio sample |
| `language` | `String?` | `null` | BCP-47 language hint. `null` uses auto-detection. |

### AudioStreamResult

Result from a real-time streaming transcription operation.

```kotlin
class AudioStreamResult : Parcelable
```

#### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `text` | `String` | `""` | Transcribed text (partial or complete) |
| `isFinal` | `Boolean` | `false` | `true` for final result after `stopStream()`, `false` for intermediate results |

---

## Error Codes

Standard error codes used throughout the SDK.

| Code | Constant | Description |
|------|----------|-------------|
| 400 | `BAD_REQUEST` | Invalid arguments or malformed request |
| 403 | `FORBIDDEN` | Security/permission violation |
| 404 | `NOT_FOUND` | Resource not found |
| 500 | `INTERNAL_ERROR` | Generic internal server error |
| 501 | `NOT_IMPLEMENTED` | Feature not implemented in the installed App build |
| 502 | `REMOTE_ERROR` | Remote service/IPC failure |
| 503 | `SERVICE_UNAVAILABLE` | Service temporarily unavailable |
| 510 | `BINDER_CONVERSION_ERROR` | Failed to convert binder to interface |
| 599 | `UNKNOWN_ERROR` | Unknown/unexpected error |
---

## See Also

- [Integration Guide](INTEGRATION_GUIDE.md) - Quick start and basic integration
- [Examples](EXAMPLES.md) - Complete code examples
- [Best Practices](BEST_PRACTICES.md) - Development guidelines
- [Troubleshooting](TROUBLESHOOTING.md) - Common issues and solutions
