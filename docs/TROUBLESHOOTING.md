# Foundry Local Android - Troubleshooting

Common issues and solutions when working with Foundry Local SDKs.

## Table of Contents

- [Connection Issues](#connection-issues)
- [Model Loading Errors](#model-loading-errors)
- [Chat Completion Issues](#chat-completion-issues)
- [Performance Problems](#performance-problems)
- [Embedded SDK Issues](#embedded-sdk-issues)

---

## Connection Issues

### Service Connection Fails Immediately

**Symptoms:**
- `connect()` returns `false`
- `onServiceDisconnected()` called immediately
- Error message: "Service not found"

**Solutions:**

1. **Verify Foundry Local App is installed**
   ```kotlin
   fun isFoundryLocalInstalled(context: Context): Boolean {
       return try {
           context.packageManager.getPackageInfo(
               "com.microsoft.foundrylocal",
               0
           )
           true
       } catch (e: PackageManager.NameNotFoundException) {
           false
       }
   }
   ```

   If not installed:
   - Direct users to install from the private preview GitHub repository
   - The GitHub version does not require app signature validation

2. **Check Foundry Local App version**
   ```kotlin
   fun getFoundryLocalVersion(context: Context): String? {
       return try {
           val packageInfo = context.packageManager.getPackageInfo(
               "com.microsoft.foundrylocal",
               0
           )
           packageInfo.versionName
       } catch (e: PackageManager.NameNotFoundException) {
           null
       }
   }
   ```

   Update to the latest version from the private preview repository if outdated.

3. **Verify permissions**

   Ensure `INTERNET` permission is in `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

4. **Verify app installation**

   Use the Foundry Local App from this private preview GitHub repository.

---


## Model Loading Errors

### Model Fails to Load

**Symptoms:**
- `model.load()` callback returns `successful = false`
- Error message about missing files or insufficient memory

**Solutions:**

1. **Verify the model is fully downloaded**
   ```kotlin
   val isCached = model.isCached().data ?: false
   if (!isCached) {
       Log.e(TAG, "Model not cached — download it first")
       model.download(context, downloadCallback)
       return
   }
   ```

2. **Check available memory before loading**
   ```kotlin
   val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
   val memInfo = ActivityManager.MemoryInfo()
   activityManager.getMemoryInfo(memInfo)
   val availableMB = memInfo.availMem / (1024 * 1024)
   if (availableMB < 500) {
       showError("Not enough free memory. Close other apps and try again.")
       return
   }
   ```

3. **Unload any previously loaded model** — unloading other models can free memory and improve load reliability, especially on low-memory devices.
   ```kotlin
   currentModel?.unload(progressCallback)
   ```

---


## Chat Completion Issues

### Empty or Null Responses

**Symptoms:**
- `completeChat()` succeeds but returns null/empty content
- No error messages

**Solutions:**

1. **Verify model is loaded**
   ```kotlin
   val isLoaded = model.isLoaded().data ?: false

   if (!isLoaded) {
       Log.e(TAG, "Model not loaded - load before creating client")
       model.load(loadCallback)
       return
   }
   ```

2. **Check request validity**
   ```kotlin
   fun validateRequest(request: ChatCompletionRequest): Boolean {
       if (request.messages.isEmpty()) {
           Log.e(TAG, "Request has no messages")
           return false
       }

       val hasUserMessage = request.messages.any {
           it.role == ChatMessage.Role.USER
       }

       if (!hasUserMessage) {
           Log.e(TAG, "Request has no user message")
           return false
       }

       return true
   }
   ```

3. **Verify conversation history length**
   ```kotlin
   val totalTokens = request.messages.sumOf {
       it.content.split(" ").size * 1.3  // Rough token estimate
   }

   if (totalTokens > 2000) {  // Model context limit
       Log.w(TAG, "Conversation too long, trimming history")
       trimConversationHistory(request)
   }
   ```

4. **Check maxTokens parameter**
   ```kotlin
   // Don't set maxTokens too low
   request.maxTokens = 150  // Good
   // request.maxTokens = 1  // Bad - may truncate response
   ```

### Inference is Very Slow

**Symptoms:**
- Chat completion takes 30+ seconds
- App appears frozen
- Device gets hot

**Solutions:**

1. **Reduce maxTokens**
   ```kotlin
   // Instead of:
   request.maxTokens = 500  // Slow

   // Try:
   request.maxTokens = 100  // Faster
   ```

2. **Use a smaller model**
   ```kotlin
   // Instead of:
   val model = catalog.getModel("phi-3-mini-4k").data  // ~2GB, slower

   // Try:
   val model = catalog.getModel("qwen2.5-0.5b").data  // ~500MB, faster
   ```

3. **Reduce conversation history**
   ```kotlin
   fun trimHistory(messages: MutableList<ChatMessage>, maxMessages: Int = 5) {
       if (messages.size > maxMessages) {
           val systemMsg = messages.firstOrNull { it.role == ChatMessage.Role.SYSTEM }
           val recent = messages.takeLast(maxMessages - 1)

           messages.clear()
           systemMsg?.let { messages.add(it) }
           messages.addAll(recent)
       }
   }
   ```

4. **Check device thermal state**
   ```kotlin
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
       val powerManager = getSystemService(PowerManager::class.java)
       val thermalStatus = powerManager.currentThermalStatus

       if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
           Log.w(TAG, "Device is hot - inference will be slow")
           showWarning("Device is hot. Performance may be reduced.")
       }
   }
   ```

5. **Use streaming for better UX**
   ```kotlin
   // Users perceive streaming as faster
   chatClient.completeChatStreaming(request, callback)
   ```

---

## Performance Problems

1. **Unload model when not in use**
   ```kotlin
   override fun onPause() {
       super.onPause()

       if (!isChangingConfigurations && !isFinishing) {
           model?.unload(progressCallback)
       }
   }
   ```
---

## Embedded SDK Issues

### UnsatisfiedLinkError or "Native library loading failed" for onnxruntime

**Cause:** The ONNX Runtime native library is not packaged in your APK. The embedded SDK's native Core requires `libonnxruntime.so` at runtime, which is provided by the `onnxruntime-android` dependency.

**Fix:** Add the ONNX Runtime Android dependency to the host app:

```kotlin
dependencies {
    // The embedded SDK requires onnxruntime-android at runtime:
    runtimeOnly("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
}
```

If the dependency is present but the error persists, verify the `onnxruntime-android` artifact is packaged for your app's target ABI (arm64-v8a) and that `FoundryLocalManager.create()` is being called (which triggers native library loading during `CoreRuntime.initialize()`).

### Model not found when using incorrect model name

**Cause:** Using a model name that doesn't exactly match an alias in the catalog. Aliases vary — some are short (e.g., `phi-4-mini`), others are versioned (e.g., `qwen2.5-coder-0.5b-instruct-generic-cpu:4`).

**Fix:** Call `catalog.listModels()` first, then pass the exact `alias` field value into `catalog.getModel(...)`.

### OgaHandle cannot be re-initialized

**Cause:** ORT GenAI runtime lifecycle. Once shutdown occurs, the runtime cannot be restarted in the same process.

**Fix:** Do not call `OgaShutdown()` directly. The embedded SDK's `close()` method is safe to call because it only cleans up SDK wrappers and never shuts down the ORT runtime. If you hit this error, something in the app is shutting the runtime down directly.

### Embedded SDK not detected / IPC SDK used instead

**Cause:** ContentProvider auto-registration order or packaging issues.

**Fix:** Ensure the embedded SDK AAR is present in `libs/`. If both IPC and embedded AARs are present, the embedded SDK wins (its `initOrder=50` runs after IPC's `initOrder=100`, overwriting the factory). The most common cause is the embedded AAR being missing entirely — verify it was copied into `libs/` after building.

---

## Getting Help

If issues persist:

1. **Check logs**
   ```bash
   adb logcat | grep -i foundry
   ```

2. **Enable verbose logging**
   ```kotlin
   val options = Configuration(
       appName = "MyApp",
       logLevel = "Verbose"  // Maximum logging
   )
   ```

---

## See Also

- [Integration Guide](INTEGRATION_GUIDE.md) - Setup instructions
- [API Reference](API_REFERENCE.md) - Complete API documentation
- [Examples](EXAMPLES.md) - Code examples
- [Best Practices](BEST_PRACTICES.md) - Development guidelines
