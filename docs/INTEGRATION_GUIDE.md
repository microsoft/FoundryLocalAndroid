# Foundry Local for Android — Integration Guide

Run generative AI models on-device in your Android app. No cloud, no cost per request, fully private.

---

## How It Works

Foundry Local for Android gives you two ways to run inference on-device. Pick the one that fits your app:

### Option A: IPC (Service-Based)

Your app talks to the **Foundry Local service app** over Android IPC. The service manages models and runs inference in a separate process.

```
┌─────────────┐       AIDL IPC        ┌─────────────────────┐
│  Your App   │ ◄──────────────────► │  Foundry Local App  │
│  (SDK lib)  │                        │  (inference engine) │
└─────────────┘                        └─────────────────────┘
```

**Best for:** Apps that want a small APK, share models across multiple apps, or prefer process isolation (one app crashing won't kill inference).

**Trade-off:** Users must install the Foundry Local app separately.

### Option B: Embedded (In-Process)

Your app bundles the inference engine directly. No service app needed — everything runs in your process.

```
┌───────────────────────────────────┐
│           Your App                │
│  ┌─────────┐  ┌───────────────┐  │
│  │ SDK lib │──│ Native engine │  │
│  └─────────┘  └───────────────┘  │
└───────────────────────────────────┘
```

**Best for:** Standalone apps, kiosks, or scenarios where you can't require a second app install.

**Trade-off:** Larger APK (~50 MB+ for native libraries). Models are not shared between apps.

### At a Glance

| | IPC | Embedded |
|---|---|---|
| Requires Foundry Local app | Yes | No |
| APK size overhead | ~200 KB | ~50 MB+ |
| Models shared between apps | Yes | No |
| Process isolation | Yes (crash-safe) | No |
| API style | Callbacks + coroutines | Coroutines only |

---

## Prerequisites

- Android API 33+ (Android 13)
- Kotlin 1.9.25+
- Gradle 8.6+

**For IPC:** Download both the Foundry Local App APK and `FoundryLocalIPCSDK-release-fat.aar` from [releases](https://github.com/microsoft/Foundry-Local-for-Android/releases).

**For Embedded:** Download `FoundryLocalEmbeddedSDK-release-fat.aar` from [releases](https://github.com/microsoft/Foundry-Local-for-Android/releases).

---

## IPC Integration

### 1. Install

Place `FoundryLocalIPCSDK-release-fat.aar` in your project's `libs/` directory:

```kotlin
// build.gradle.kts
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
```

Add internet permission for model downloads:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. Connect to the Service

The IPC SDK communicates with the Foundry Local app via a service connection:

```kotlin
import com.microsoft.foundrylocal.FoundryLocalManager
import com.microsoft.foundrylocal.IFoundryLocalManager
import com.microsoft.foundrylocal.datamodels.Configuration
import com.microsoft.foundrylocal.callbacks.FoundryServiceConnectionCallback

class MyActivity : AppCompatActivity() {
    private lateinit var manager: FoundryLocalManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = FoundryLocalManager(Configuration(appName = "MyApp"))

        manager.connect(this, object : FoundryServiceConnectionCallback {
            override fun onServiceConnected(service: IFoundryLocalManager) {
                // Service is ready — you can now use the catalog and models
                startInference()
            }

            override fun onServiceDisconnected(
                errorCode: FoundryServiceConnectionCallback.ErrorCode,
                message: String?
            ) {
                Log.e(TAG, "Service disconnected: $message")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        manager.disconnect(this)
    }
}
```

### 3. Download, Load, and Chat

Once connected, use the catalog to find models, download them, and run inference:

```kotlin
private fun startInference() {
    lifecycleScope.launch(Dispatchers.Default) {
        // Browse the catalog
        val catalog = manager.getCatalog().data ?: return@launch
        val model = catalog.getModel("phi-4-mini").data ?: return@launch

        // Load (downloads automatically if needed)
        if (model.isLoaded().data != true) {
            model.load(object : FoundryOperationProgressCallback {
                override fun onProgressUpdate(
                    operationType: FoundryOperationProgressCallback.OperationType,
                    modelAlias: String,
                    status: FoundryOperationProgressCallback.OperationStatus,
                    progressPercent: Int,
                    message: String?
                ) { /* update progress UI */ }

                override fun onOperationComplete(
                    operationType: FoundryOperationProgressCallback.OperationType,
                    modelAlias: String,
                    successful: Boolean,
                    errorMessage: String?
                ) { /* done */ }
            })
        }

        // Chat
        val chatClient = model.createChatCompletionClient().data ?: return@launch
        val request = ChatCompletionRequest().apply {
            messages.add(ChatMessage(ChatMessage.Role.USER, "Hello!"))
        }
        val response = chatClient.completeChat(request).data?.message?.content

        withContext(Dispatchers.Main) {
            textView.text = response
        }
    }
}
```

### 4. Stream Responses

For real-time token-by-token output:

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
            runOnUiThread { textView.text = responseBuilder.toString() }
        }

        override fun onOperationComplete(
            operationType: Int,
            modelAlias: String,
            successful: Boolean,
            errorMessage: String?
        ) {
            Log.d(TAG, "Streaming complete")
        }
    }
)
```

---

## Embedded Integration

### 1. Install

Place `FoundryLocalEmbeddedSDK-release-fat.aar` in your project's `libs/` directory:

```kotlin
// build.gradle.kts
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Required: native inference runtime
    runtimeOnly("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Required: coroutines (the embedded API uses suspend functions and Flow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

No manifest changes needed. No service app to install.

### 2. Create a Manager and Chat

The embedded SDK uses Kotlin coroutines throughout — no callbacks, no service binding:

```kotlin
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MyActivity : ComponentActivity() {
    private var manager: FoundryLocalManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize (loads native engine on first call)
            this@MyActivity.manager = FoundryLocalManager.create(
                context = applicationContext,
                config = Configuration(appName = "MyApp")
            )
            val mgr = this@MyActivity.manager ?: return@launch

            // Browse catalog and pick a model
            val catalog = mgr.getCatalog()
            val models = catalog.listModels()
            val model = catalog.getModel(models.first().alias)

            // Download and load
            model.download { progress -> updateProgressBar(progress) }
            model.load()

            // Chat (one-shot)
            val chatClient = model.createChatClient()
            val response = chatClient.completeChat(
                ChatCompletionRequest(
                    messages = listOf(ChatMessage.user("Hello!"))
                )
            )
            showResponse(response.message?.content.orEmpty())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        manager?.close()
    }
}
```

### 3. Stream Responses

Use Kotlin Flow for token-by-token streaming:

```kotlin
val request = ChatCompletionRequest(
    messages = listOf(ChatMessage.user("Tell me a joke"))
)

chatClient.completeChatStreaming(request)
    .catch { e -> Log.e(TAG, "Stream error", e) }
    .collect { chunk -> appendToUI(chunk.delta) }
```

---

## Models

Browse available models at [foundrylocal.ai/models](https://www.foundrylocal.ai/models).

> **Tips:**
> - Start with small models (under 3 GB) for mobile — e.g., `qwen2.5-0.5b` (~500 MB) for quick testing.
> - Only CPU inference is supported today. GPU/NPU support is planned.
> - Model aliases (like `phi-4-mini`) are returned by `catalog.listModels()`. Always use the exact `alias` value when calling `getModel()`.

---

## Multi-Turn Conversations

Both integration paths support conversation history. Accumulate messages and pass the full history on each request:

```kotlin
val history = mutableListOf(
    ChatMessage(ChatMessage.Role.SYSTEM, "You are a helpful assistant.")
)

// User turn
history.add(ChatMessage(ChatMessage.Role.USER, "What's the capital of France?"))

val request = ChatCompletionRequest().apply { messages.addAll(history) }
val response = chatClient.completeChat(request)

// Add assistant reply to history for next turn
response.data?.message?.let { history.add(it) }
```

---

## Next Steps

- **[API Reference](API_REFERENCE.md)** — Full class and method reference
- **[Examples](EXAMPLES.md)** — Complete working apps
- **[Best Practices](BEST_PRACTICES.md)** — Performance, lifecycle, error handling
- **[Troubleshooting](TROUBLESHOOTING.md)** — Common issues and solutions

---

## Support

- 📧 [Submit an issue](https://github.com/microsoft/Foundry-Local-for-Android/issues)
- 💬 [Share feedback](https://aka.ms/foundrylocal-androidfeedback)
