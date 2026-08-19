# IPC vs Embedded SDK — Comparison Guide

Foundry Local for Android offers two SDK variants for on-device AI inference. Both share the same public API (`FoundryLocalManager`, `Catalog`, `Model`, `ChatClient`, `AudioClient`) — the difference is **where** inference runs.

---

## Architecture Overview

### IPC (Inter-Process Communication)

Your app includes a thin SDK library (~200 KB). Inference runs in the **Foundry Local service app** — a separate process on the device.

```
┌─────────────────┐       AIDL IPC        ┌──────────────────────────┐
│    Your App     │ ◄────────────────────► │   Foundry Local App      │
│  (IPC SDK lib)  │   cross-process calls  │  (native inference .so)  │
└─────────────────┘                        └──────────────────────────┘
```

### Embedded (In-Process)

Your app bundles the full inference engine. Everything runs inside your process — no external app needed.

```
┌──────────────────────────────────────────┐
│              Your App                    │
│  ┌──────────────┐  ┌──────────────────┐  │
│  │ Embedded SDK │──│ Native .so libs  │  │
│  │  (~80-100MB) │  │ (ORT + Core)     │  │
│  └──────────────┘  └──────────────────┘  │
└──────────────────────────────────────────┘
```

---

## Feature Comparison

| Feature | IPC | Embedded |
|---------|-----|----------|
| **Requires Foundry Local app installed** | Yes | No |
| **APK size overhead** | ~200 KB | ~80–100 MB per ABI |
| **Models shared between apps** | Yes (service manages one cache) | No (per-app cache) |
| **Process isolation** | Yes — service crash doesn't kill your app | No — crash takes your app down |
| **Connection setup** | Async service binding (~50–200 ms) | Synchronous in-process init |
| **API style** | `suspend` + `Flow` (shared API) | `suspend` + `Flow` (shared API) |
| **Chat completion** | ✅ | ✅ |
| **Streaming chat** | ✅ | ✅ |
| **Audio transcription** | ✅ | ✅ |
| **Real-time audio streaming** | ✅ | ✅ |
| **Model download with progress** | ✅ (foreground service notification) | ✅ (in-process) |
| **Model catalog** | ✅ | ✅ |
| **Multiple models loaded** | ✅ (service manages memory) | ✅ (app manages memory) |
| **Cancellation** | ✅ (coroutine cancellation) | ✅ (coroutine cancellation) |
| **Min SDK** | API 33 (Android 13) | API 33 (Android 13) |
| **Offline operation** | ✅ (after model download) | ✅ (after model download) |
| **Caller verification** | Yes (signature allowlist) | N/A (same process) |
| **Download notification** | System notification (foreground service) | In-app only |

---

## When to Use Each

### Choose IPC when…

- **You want a small APK.** The IPC SDK is ~200 KB. Native inference libraries (~80–100 MB per ABI) live in the service app, not your APK.
- **Multiple apps share models.** All IPC clients share one model cache — download once, use everywhere.
- **You want crash isolation.** If inference hits an OOM or native crash, your app stays alive.
- **You're deploying in a managed fleet** where the Foundry Local app is pre-installed (enterprise, kiosk).
- **Memory is tight.** The service runs in its own process with its own heap — your app's memory footprint stays low.

### Choose Embedded when…

- **You can't require a second app install.** Users install your app and it just works.
- **You want the simplest integration.** No service binding, no connection callbacks, no reconnection logic.
- **You're building a standalone app** (demo, prototype, kiosk with a single APK).
- **You need deterministic startup.** No waiting for service binding — the native engine is always available.
- **Distribution is constrained.** Play Store/sideload scenarios where managing two APKs is impractical.

### Decision Matrix

| Scenario | Recommendation |
|----------|---------------|
| Enterprise app with pre-installed service | **IPC** |
| Consumer app on Play Store | **Embedded** |
| Multiple apps on same device using AI | **IPC** (shared model cache) |
| Single-purpose demo/prototype | **Embedded** |
| Low-memory devices (< 6 GB RAM) | **IPC** (process isolation) |
| Maximum reliability against native crashes | **IPC** |
| Simplest developer experience | **Embedded** |
| Offline-first with no internet for setup | **Embedded** (bundle model in APK) |

---

## Tradeoffs in Depth

### Crash Safety (IPC advantage)

The ORT native runtime uses static globals that cannot be re-initialized after `OgaShutdown()`. If the native engine crashes or corrupts state, the IPC service process dies independently — your app can detect the disconnection and attempt reconnection. With embedded, the same crash takes your entire app down.

### APK Size (IPC advantage)

IPC SDK: ~200 KB AAR. Embedded SDK: ~80–100 MB AAR per ABI (includes `libonnxruntime.so` ~25 MB, `libonnxruntime-genai.so` ~67 MB, `Microsoft.AI.Foundry.Local.Core.so` ~16 MB, plus OpenSSL and JNI bindings).

### Shared Models (IPC advantage)

The Foundry Local service manages a single model cache. If a user has three apps using Phi-4 mini (~2.5 GB), the model is downloaded once. Embedded apps each maintain their own cache — 3× the disk space.

### Simplicity (Embedded advantage)

No service binding, no `onServiceDisconnected` handling, no reconnection logic. The embedded API is pure Kotlin coroutines — `create()`, `getCatalog()`, `model.load()`, `chatClient.completeChat()`. All synchronous from the caller's perspective (within the coroutine).

### Distribution (Embedded advantage)

One APK to install. No user confusion about "install this other app first." No version compatibility checks between SDK and service. The embedded SDK is always compatible with itself.

### Startup Latency (Embedded advantage)

IPC requires binding to the service (~50–200 ms depending on whether the service process is cold-started). Embedded initializes the native engine in-process during `FoundryLocalManager.create()` — subsequent operations are immediately available.

---

## API Comparison

Both SDKs implement the same shared API interfaces from `FoundryLocalAPI`:

```kotlin
// Identical in both variants
val manager = FoundryLocalManager.create(context, Configuration(appName = "MyApp"))
val catalog = manager.getCatalog()
val model = catalog.getModel("phi-4-mini")
model.download { progress -> updateUI(progress) }
model.load()

val chatClient = model.createChatClient()
val response = chatClient.completeChat(
    ChatCompletionRequest(messages = listOf(ChatMessage.user("Hello!")))
)

// Streaming (Flow-based)
chatClient.completeChatStreaming(request).collect { chunk ->
    appendToUI(chunk.delta)
}

// Cleanup
model.unload()
manager.close()
```

The only difference is which AAR you include:
- **IPC:** `FoundryLocalIPCSDK-release-fat.aar`
- **Embedded:** `FoundryLocalEmbeddedSDK-release-fat.aar`

Factory registration happens automatically via `ContentProvider` — no manual initialization code needed.

---

## Migration Guide

### IPC → Embedded

1. **Replace the AAR.** Swap `FoundryLocalIPCSDK-*.aar` for `FoundryLocalEmbeddedSDK-*.aar` in your `libs/` folder.

2. **Remove service-related code.** Delete any:
   - `FoundryServiceConnectionCallback` usage
   - `manager.connect(context, callback)` / `manager.disconnect(context)` calls
   - `onServiceDisconnected` handling and reconnection logic

3. **Switch to the shared API entry point:**
   ```kotlin
   // Before (IPC-specific legacy API — com.microsoft.foundrylocal.FoundryLocalManager)
   val manager = FoundryLocalManager(Configuration(appName = "MyApp"))
   manager.connect(context, callback)
   // ... wait for onServiceConnected ...

   // After (shared API — com.microsoft.foundrylocal.api.FoundryLocalManager)
   // Works with either SDK variant. Update your imports accordingly.
   val manager = FoundryLocalManager.create(context, Configuration(appName = "MyApp"))
   // Ready immediately (within the coroutine)
   ```

4. **Update error handling.** IPC can throw `RemoteException` on service death. Embedded throws `FoundryLocalException` for all errors — no remote-specific exceptions.

5. **Uninstall the Foundry Local app** from test devices (optional — it won't interfere, but it's no longer needed).

### Embedded → IPC

1. **Replace the AAR.** Swap `FoundryLocalEmbeddedSDK-*.aar` for `FoundryLocalIPCSDK-*.aar` in your `libs/` folder.

2. **Install the Foundry Local app** on the device.

3. **Handle service lifecycle.** The service can disconnect unexpectedly (process killed, app update). Add reconnection logic:
   ```kotlin
   override fun onServiceDisconnected(errorCode, message) {
       // Attempt reconnection or show error to user
   }
   ```

4. **Consider signature allowlisting.** The Foundry Local app verifies callers by APK signing certificate. During development, the debug keystore hash is pre-allowed. For release builds, register your signing certificate hash in the service's allowlist.

5. **Test with service not installed.** Verify your app handles the "service not found" case gracefully (prompt user to install the Foundry Local app).

---

## Dependency Setup

### IPC

```kotlin
// build.gradle.kts
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

Manifest: Add `<uses-permission android:name="android.permission.INTERNET" />` for model downloads.

### Embedded

```kotlin
// build.gradle.kts
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

Manifest: Add `<uses-permission android:name="android.permission.INTERNET" />` for model downloads.

> **Note:** The embedded fat AAR bundles `libonnxruntime.so` automatically — you do **not** need a separate `runtimeOnly("com.microsoft.onnxruntime:onnxruntime-android:...")` dependency.

---

## Both SDKs on the Classpath

If both AARs are present (e.g., during migration or testing), the **embedded SDK wins**. This is by design — the embedded `ContentProvider` runs after the IPC one (via `initOrder`) and overwrites the factory registration.

To force IPC when both are present, manually register the IPC factory before calling `create()`:
```kotlin
// Not recommended for production — pick one SDK and exclude the other AAR
FoundryLocalManager.registerFactory { context, config ->
    com.microsoft.foundrylocal.adapter.IpcFoundryLocalManager.create(context, config)
}
```

---

## FAQ

**Q: Can I switch between IPC and embedded at runtime?**
No. Pick one SDK variant at build time. The factory registration happens at app startup.

**Q: Does the embedded SDK support model sharing between apps?**
No. Each embedded app has its own model cache. Use IPC if you need cross-app model sharing.

**Q: Is the API surface identical?**
Yes. Both implement `FoundryLocalManager`, `Catalog`, `Model`, `ChatClient`, and `AudioClient` from the shared `FoundryLocalAPI` module. Code written against the shared API works with either SDK.

**Q: What about ProGuard/R8?**
Both SDKs: use `-keep class com.microsoft.foundrylocal.** { *; }` in your ProGuard rules.

**Q: Which models work with each SDK?**
Both support the same model catalog. Model availability is determined by the Foundry Local model registry, not the SDK variant.

---

## Further Reading

- [Integration Guide](INTEGRATION_GUIDE.md) — Step-by-step setup for each SDK
- [API Reference](API_REFERENCE.md) — Full class and method reference
- [Best Practices](BEST_PRACTICES.md) — Performance, lifecycle, error handling
- [Troubleshooting](TROUBLESHOOTING.md) — Common issues and solutions
