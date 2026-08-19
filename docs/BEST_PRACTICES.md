# Foundry Local Android - Best Practices

Guidelines and recommendations for developing with Foundry Local SDK.

## Table of Contents

- [Error Handling](#error-handling)
- [Model Lifecycle](#model-lifecycle)
- [Connection Management](#connection-management)
- [Performance Optimization](#performance-optimization)
- [Memory Management](#memory-management)
- [UI/UX Guidelines](#uiux-guidelines)

---

## Error Handling

### Always Check FLResult Status

**✅ DO:** Check status before accessing data

```kotlin
val result = catalog.getModel("phi-3-mini-4k")

if (result.status) {
    val model = result.data!!
    // Safe to use model
} else {
    Log.e(TAG, "Error: ${result.error?.message}")
    // Handle error appropriately
}
```

**❌ DON'T:** Assume operations always succeed

```kotlin
// BAD - May crash if operation failed
val model = catalog.getModel("phi-3-mini-4k").data!!
```

### Handle All Error Cases

**✅ DO:** Implement comprehensive error handling

```kotlin
fun handleChatError(result: FLResult<ChatCompletion>) {
    if (!result.status) {
        when (result.error?.code) {
            400 -> showError("Invalid request. Please check your input.")
            403 -> showError("Permission denied. Please check app configuration.")
            404 -> showError("Model not found. Please download the model first.")
            500 -> showError("Internal error. Please try again.")
            503 -> showError("Service unavailable. Please check if Foundry Local App is installed.")
            else -> showError("An error occurred: ${result.error?.message}")
        }
    }
}
```

### Provide User-Friendly Error Messages

**✅ DO:** Convert technical errors to user-friendly messages

```kotlin
fun getUserFriendlyError(error: FLError): String {
    return when (error.code) {
        400 -> "Your request couldn't be processed. Please try again."
        403 -> "You don't have permission to access this feature."
        404 -> "The requested model is not available."
        503 -> "The AI service is not available. Please install Foundry Local App."
        else -> "Something went wrong. Please try again later."
    }
}
```

---

## Model Lifecycle

### Follow the Proper Lifecycle

The correct order of operations:

1. **Download** (if not cached)
2. **Load** (if not loaded)
3. **Use** (create client, run inference)
4. **Unload** (when done)

**✅ DO:** Check status before each step

```kotlin
suspend fun setupModel(catalog: Catalog, modelAlias: String): FoundryChatCompletionClient? {
    val model = catalog.getModel(modelAlias).data ?: return null
    
    // Step 1: Download if needed
    if (model.isCached().data != true) {
        suspendCancellableCoroutine { cont ->
            model.download(context, object : FoundryOperationProgressCallback {
                override fun onOperationComplete(
                    operationType: FoundryOperationProgressCallback.OperationType,
                    modelAlias: String,
                    successful: Boolean,
                    errorMessage: String?
                ) {
                    if (successful) cont.resume(Unit) {}
                    else cont.resumeWithException(Exception(errorMessage)) {}
                }
                override fun onProgressUpdate(/*...*/) {}
            })
        }
    }
    
    // Step 2: Load if needed
    if (model.isLoaded().data != true) {
        suspendCancellableCoroutine { cont ->
            model.load(object : FoundryOperationProgressCallback {
                override fun onOperationComplete(
                    operationType: FoundryOperationProgressCallback.OperationType,
                    modelAlias: String,
                    successful: Boolean,
                    errorMessage: String?
                ) {
                    if (successful) cont.resume(Unit) {}
                    else cont.resumeWithException(Exception(errorMessage)) {}
                }
                override fun onProgressUpdate(/*...*/) {}
            })
        }
    }
    
    // Step 3: Create client
    return model.createChatCompletionClient().data
}
```


### Reuse Chat Clients

**✅ DO:** Create client once and reuse

```kotlin
class ChatManager {
    private var chatClient: FoundryChatCompletionClient? = null
    
    suspend fun initialize(model: FoundryModel) {
        // Create once
        chatClient = model.createChatCompletionClient().data
    }
    
    suspend fun chat(message: String): String? {
        // Reuse client
        val request = ChatCompletionRequest().apply {
            messages.add(ChatMessage(ChatMessage.Role.USER, message))
        }
        return chatClient?.completeChat(request)?.data?.message?.content
    }
}
```

**❌ DON'T:** Create new client for each request

```kotlin
// BAD - Creates unnecessary overhead
suspend fun chat(model: FoundryModel, message: String): String? {
    val client = model.createChatCompletionClient().data  // Don't recreate!
    // ...
}
```

---

## Connection Management

### Check Connection State Before Operations

**✅ DO:** Verify connection before making calls

```kotlin
fun performOperation() {
    if (!manager.isConnected) {
        Log.e(TAG, "Not connected to service")
        reconnect()
        return
    }
    
    // Safe to proceed
    lifecycleScope.launch(Dispatchers.Default) {
        val catalog = manager.getCatalog().data
        // ...
    }
}
```

### Handle Service Unavailability

**✅ DO:** Gracefully handle missing service

```kotlin
val connected = manager.connect(context, callback)

if (!connected) {
    // Service not available
    AlertDialog.Builder(this)
        .setTitle("Foundry Local Required")
        .setMessage("Please install Foundry Local App to use AI features.")
        .setPositiveButton("Install") { _, _ ->
            // Guide to installation
        }
        .show()
}
```

---

## Performance Optimization

### Choose Appropriate Models

**✅ DO:** Select models based on device capabilities

```kotlin
fun selectModelForDevice(): String {
    val memoryClass = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .memoryClass
    
    return when {
        memoryClass >= 512 -> "phi-3-mini-4k"  // High-end device
        memoryClass >= 256 -> "qwen2.5-0.5b"   // Mid-range
        else -> "qwen2.5-0.5b"                  // Low-end
    }
}
```

### Optimize Request Parameters

**✅ DO:** Use appropriate parameters for use case

```kotlin
// For fast, concise responses
val quickRequest = ChatCompletionRequest().apply {
    messages.add(ChatMessage(ChatMessage.Role.USER, question))
    temperature = 0.3f  // Low for focused responses
    maxTokens = 50      // Short responses
}

// For creative, detailed responses
val creativeRequest = ChatCompletionRequest().apply {
    messages.add(ChatMessage(ChatMessage.Role.USER, prompt))
    temperature = 0.9f  // High for creativity
    maxTokens = 500     // Longer responses
}
```

### Limit Conversation History

**✅ DO:** Trim old messages to maintain performance

```kotlin
fun addMessageWithLimit(
    messages: MutableList<ChatMessage>,
    newMessage: ChatMessage,
    maxMessages: Int = 10
) {
    messages.add(newMessage)
    
    // Keep system message + last N messages
    if (messages.size > maxMessages) {
        val systemMsg = messages.firstOrNull { 
            it.role == ChatMessage.Role.SYSTEM 
        }
        val recentMessages = messages.takeLast(maxMessages - 1)
        
        messages.clear()
        systemMsg?.let { messages.add(it) }
        messages.addAll(recentMessages)
    }
}
```

### Use Streaming for Long Responses

**✅ DO:** Use streaming for better perceived performance

```kotlin
// Streaming feels faster for users
chatClient.completeChatStreaming(request, streamingCallback)

// vs non-streaming which waits for complete response
val response = chatClient.completeChat(request)  // User waits longer
```

---

## Memory Management

### Monitor Device Memory

**✅ DO:** Check available memory before operations

```kotlin
fun hasEnoughMemory(): Boolean {
    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    
    val availableMemMB = memInfo.availMem / (1024 * 1024)
    
    return availableMemMB > 500  // Need at least 500MB free
}

fun loadModelSafely() {
    if (hasEnoughMemory()) {
        model?.load(progressCallback)
    } else {
        showError("Not enough memory. Please close other apps.")
    }
}
```
---

## UI/UX Guidelines

### Provide Progress Feedback

**✅ DO:** Show progress for long operations

```kotlin
model.download(context, object : FoundryOperationProgressCallback {
    override fun onProgressUpdate(
        operationType: FoundryOperationProgressCallback.OperationType,
        modelAlias: String,
        status: FoundryOperationProgressCallback.OperationStatus,
        progressPercent: Int,
        message: String?
    ) {
        runOnUiThread {
            progressBar.progress = progressPercent
            statusText.text = "Downloading model: $progressPercent%"
        }
    }
    
    override fun onOperationComplete(/*...*/) {
        runOnUiThread {
            progressBar.visibility = View.GONE
            statusText.text = "Ready!"
        }
    }
})
```

### Implement Request Permissions

**✅ DO:** Request permissions at appropriate times

```kotlin
private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= 33) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) 
            != PackageManager.PERMISSION_GRANTED) {
            
            if (shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS)) {
                // Show explanation
                AlertDialog.Builder(this)
                    .setTitle("Notification Permission")
                    .setMessage("We need this permission to show model download progress.")
                    .setPositiveButton("OK") { _, _ ->
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 
                            REQUEST_CODE_NOTIFICATIONS
                        )
                    }
                    .show()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS
                )
            }
        }
    }
}
```
---

## See Also

- [Integration Guide](INTEGRATION_GUIDE.md) - Quick start guide
- [API Reference](API_REFERENCE.md) - Complete API documentation
- [Examples](EXAMPLES.md) - Code examples
- [Troubleshooting](TROUBLESHOOTING.md) - Common issues and solutions
