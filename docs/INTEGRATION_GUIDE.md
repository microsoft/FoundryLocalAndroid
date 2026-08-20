# Integration Guide

Foundry Local runs generative AI models directly on Android devices through one shared Kotlin API.

> **Preview:** Foundry Local for Android is under active development. The API surface and supported
> capabilities continue to grow and may change between preview releases. Pin the AAR version used by
> your app and review the release notes before upgrading.

Foundry Local distributes each deployment mode as a separate AAR. One AAR does not contain both
modes. Add exactly one AAR to your application:

- The IPC AAR connects to the separately installed Foundry Local service app, where inference runs.
- The embedded AAR packages the inference engine in your application, where inference runs.

Both AARs expose the same Kotlin API. Choose one based on your installation, packaging,
process-isolation, and resource requirements.

## Quick example

The same model lifecycle works in both modes:

```kotlin
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager

suspend fun runChat(context: Context, modelAlias: String): String {
    val manager = FoundryLocalManager.create(
        context,
        Configuration(appName = "MyApp")
    )

    val model = manager.getCatalog().getModel(modelAlias)
    if (!model.isCached()) {
        model.download(progress = { progress -> updateDownloadProgress(progress) })
    }
    if (!model.isLoaded()) {
        model.load()
    }

    val chatClient = model.createChatClient()
    val response = chatClient.completeChat(
        ChatCompletionRequest(
            messages = listOf(ChatMessage.user("Explain on-device inference briefly."))
        )
    )

    return response.message?.content.orEmpty()
}
```

All suspend functions return their values directly and throw `FoundryLocalException` on failure.
Streaming methods return Kotlin `Flow`.

> **Good to know:** Choose the model as part of your app experience or configuration, then pass its
> catalog alias into your inference code. Catalog contents can change between releases.

## Prerequisites

Use this build baseline:

| Requirement | Version |
|---|---|
| Minimum Android version | API 33 (Android 13) |
| Compile SDK | 36 |
| Kotlin | 1.9.25 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.0 |
| Java | 17 |

You also need:

- coroutine support
- one Foundry Local release AAR and its published SHA-256 hash
- internet permission for catalog access and model downloads

Use only one deployment-mode AAR in an application:

- IPC: `foundry-local-ipc-sdk-<version>.aar`
- Embedded: `foundry-local-embedded-sdk-<version>.aar`

Do not include both unless a release explicitly documents that configuration as supported.

## IPC setup

IPC mode connects your app to the Foundry Local service app, which hosts the inference runtime.

1. Download `foundry-local-ipc-sdk-<version>.aar` from the matching release.

2. Verify its SHA-256 hash against the value published with the release.

3. Place the AAR in your app module:

   ```text
   app/
     libs/
       foundry-local-ipc-sdk-<version>.aar
   ```

4. Add the local AAR, AndroidX Core, and coroutines:

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/foundry-local-ipc-sdk-<version>.aar"))
       implementation("androidx.core:core-ktx:1.12.0")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<coroutines-version>")
   }
   ```

   AndroidX Core provides the notification and foreground-service classes used while downloading
   models. The IPC AAR does not package this external dependency.

5. Add internet permission:

   ```xml
   <!-- app/src/main/AndroidManifest.xml -->
   <uses-permission android:name="android.permission.INTERNET" />
   ```

6. Install the
   [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).

7. Initialize the manager from a coroutine:

   ```kotlin
   suspend fun createIpcManager(context: Context): FoundryLocalManager {
       return FoundryLocalManager.create(
           context = context,
           config = Configuration(appName = "MyApp"),
           onDisconnected = {
               mainHandler.post { notifyConnectionLost() }
           }
       )
   }
   ```

> **Good to know:** After an IPC disconnection, call `manager.reconnect()` and reacquire the
> `Catalog`, `Model`, `ChatClient`, or `AudioClient`. Handles obtained before reconnecting may be
> stale.

## Embedded setup

Embedded mode packages the runtime in your application and runs inference in your process.

1. Download `foundry-local-embedded-sdk-<version>.aar` from the matching release.

2. Verify its SHA-256 hash against the value published with the release.

3. Place the AAR in your app module:

   ```text
   app/
     libs/
       foundry-local-embedded-sdk-<version>.aar
   ```

4. Add the local AAR and coroutines:

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/foundry-local-embedded-sdk-<version>.aar"))
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<coroutines-version>")
   }
   ```

5. Add internet permission:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

6. Initialize the manager from a coroutine:

   ```kotlin
   suspend fun createEmbeddedManager(context: Context): FoundryLocalManager {
       return FoundryLocalManager.create(
           context,
           Configuration(appName = "MyApp")
       )
   }
   ```

No service app or service-binding code is required.

> **Good to know:** The embedded runtime shares memory and process lifetime with your app. Test the
> final packaged application under realistic memory pressure and across every supported device
> configuration.

## Model lifecycle

The catalog is the starting point for both modes. It exposes models available to this release and
device.

1. Resolve the model selected by your app:

   ```kotlin
   suspend fun getSelectedModel(
       manager: FoundryLocalManager,
       modelAlias: String
   ): Model {
       return manager.getCatalog().getModel(modelAlias)
   }
   ```

2. Download the model if it is not cached:

   ```kotlin
   suspend fun downloadIfNeeded(model: Model) {
       if (!model.isCached()) {
           model.download(progress = { progress ->
               // progress is reported from 0 to 100
           })
       }
   }
   ```

   Cancel the coroutine running `download()` to cancel the operation.

3. Load the model:

   ```kotlin
   suspend fun loadIfNeeded(model: Model) {
       if (!model.isLoaded()) {
           model.load()
       }
   }
   ```

4. Create a client supported by that model:

   ```kotlin
   suspend fun createChatClient(model: Model): ChatClient {
       return model.createChatClient()
   }
   ```

5. Unload the model when it is no longer needed:

   ```kotlin
   suspend fun unloadModel(model: Model) {
       model.unload()
   }
   ```

6. Remove cached files only after unloading:

   ```kotlin
   suspend fun removeCachedModel(model: Model) {
       if (!model.isLoaded() && model.isCached()) {
           model.removeFromCache()
       }
   }
   ```

## Streaming chat

Collect the returned `Flow` from a lifecycle-owned coroutine:

```kotlin
fun streamChat(chatClient: ChatClient): Job {
    return viewModelScope.launch(Dispatchers.IO) {
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage.user("Write one sentence about local AI."))
        )

        chatClient.completeChatStreaming(request).collect { chunk ->
            withContext(Dispatchers.Main) {
                appendAnswer(chunk.delta)
            }
        }
    }
}
```

Cancel the collecting coroutine to stop generation. Keep the `Job` if the UI needs an explicit
stop action.

## Multi-turn chat

The API does not implicitly manage conversation history. Preserve previous messages and send the
history with each request:

```kotlin
val history = mutableListOf<ChatMessage>()

suspend fun sendMessage(chatClient: ChatClient, userText: String): String {
    history += ChatMessage.user(userText)
    val response = chatClient.completeChat(
        ChatCompletionRequest(messages = history)
    )
    response.message?.let(history::add)
    return response.message?.content.orEmpty()
}
```

Trim history according to the selected model's context limits.

## Error handling

Catch `FoundryLocalException` around SDK operations and keep coroutine cancellation separate:

```kotlin
suspend fun loadModel(model: Model) {
    try {
        model.load()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: com.microsoft.foundrylocal.api.FoundryLocalException) {
        showError(error.message ?: "Foundry Local operation failed")
    }
}
```

For IPC mode, check `manager.isConnected` after a failure. If disconnected, reconnect and reacquire
dependent handles before retrying.

## Cleanup

Own the manager in a lifecycle-aware component such as a `ViewModel` or application-scoped service.
Cancel active inference jobs before releasing model resources:

```kotlin
suspend fun closeFoundryLocal(
    manager: FoundryLocalManager,
    model: Model,
    streamingJob: Job?
) {
    streamingJob?.cancelAndJoin()
    if (model.isLoaded()) {
        model.unload()
    }
    manager.close()
}
```

Do not call SDK operations on a manager after `close()`.

## Next steps

- [API Reference](API_REFERENCE.md)
- [Examples](EXAMPLES.md)
- [Best Practices](BEST_PRACTICES.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [IPC and embedded deployment modes](IPC_AND_EMBEDDED_MODES.md)
