# SDK Architecture

This document explains the dual-SDK architecture of Foundry Local for Android
and the design decisions behind it.

## Overview

Foundry Local for Android offers two SDK implementations that share a common
API contract:

| | IPC SDK | Embedded SDK |
|---|---|---|
| Module | `FoundryLocalIPCSDK` | `FoundryLocalEmbeddedSDK` (planned) |
| Communication | AIDL cross-process IPC | Direct in-process JNI |
| Requires Service App | Yes (`FoundryLocalApp`) | No |
| Native libs bundled | In the Service App | In the client app |

Both SDKs implement the same **shared API contract** defined in
`common/FoundryLocalAPI/`. Client code written against the shared API can
switch between IPC and embedded with minimal changes.

## Module Structure

```
common/
  FoundryLocalAPI/          ← Shared API interfaces (Model, Catalog, ChatClient, …)
  FoundryLocalCore/         ← Shared native bridge: CoreEngine, NativeBridge, CoreCallback

ipc-service/
  FoundryLocalIPCContract/  ← AIDL interfaces, parcelables, FLResult
  FoundryLocalIPCSDK/          ← IPC SDK + adapter layer
  FoundryLocalApp/          ← Service app (inference host)

embedded-sdk/               ← (planned) Embedded SDK
```

### Native Core Sharing

Both the IPC service app and the embedded SDK use the same native Core library
(`Microsoft.AI.Foundry.Local.Core.so`) via JNI. The JNI stubs and bridge code
live in `common/FoundryLocalCore/`:

- **`CoreEngine`** — JSON command construction, native calls via `NativeBridge`, response/streaming parsing
- **`NativeBridge`** / **`JniNativeBridge`** — interface + JNI implementation for native method calls
- **`CoreCallback`** — `fun interface` matching the JNI wire format for streaming callbacks (`onUpdate(String?) → Int`)

`FoundryCoreManager` in the IPC service app is a thin **delegating facade** around `CoreEngine`,
adding Android-specific concerns (environment setup, AIDL callback adaptation, cancellation tracking).
The embedded SDK will use `CoreEngine` directly.

Both SDKs must use the **same Core version** — the JNI function signatures must
match the `.so` binaries. Independent Core versioning per SDK is not supported
(and not anticipated). If that need arises, the module can be split at that time.

## Why Two SDKs?

**IPC SDK** — The service app model means native inference libraries (~50 MB)
are installed once and shared across all client apps. Updates to the inference
engine don't require client app updates.

**Embedded SDK** — For apps that want full control, no dependency on an
external service app, and the cleanest possible API (no AIDL overhead).

## Error Handling: FLResult vs Exceptions

This is a key architectural difference driven by an Android platform
constraint.

### The AIDL Constraint

Android's AIDL IPC mechanism only supports `RemoteException` across process
boundaries. You **cannot** throw custom exceptions (like
`FoundryLocalException`) from the service and catch them in the client — the
exception type, error code, and structured error info are lost.

To work around this, the IPC SDK uses `FLResult<T>` as a return type on the
AIDL wire:

```kotlin
// AIDL interface — returns FLResult, never throws custom exceptions
interface IFoundryModel {
    FLResult load(IFoundryOperationProgressCallback callback);
    FLResult isCached();
}
```

```kotlin
// FLResult carries success/failure + structured error across the process boundary
data class FLResult<T>(
    val status: Boolean,    // true = success
    val data: T?,           // payload on success
    val error: FLError?     // error code + message on failure
)
```

### The Shared API Contract

The shared API (`FoundryLocalAPI`) defines a clean, idiomatic Kotlin interface
using **suspend functions that throw on failure**:

```kotlin
// Shared API — exception-based, no FLResult
interface Model {
    suspend fun download(
        progress: ((Float) -> Unit)? = null,
        contentIntent: PendingIntent? = null,
        timeoutMinutes: Int = 0
    )
    suspend fun load()
    suspend fun isCached(): Boolean
    suspend fun createChatClient(): ChatClient
}
```

### How Each SDK Implements It

**IPC SDK (adapter layer):**

The adapter wraps existing IPC calls, unwraps `FLResult`, and throws on
failure. `FLResult` still crosses the AIDL boundary — it is hidden from the
customer:

```
Service Process                 AIDL boundary           Client Process
───────────────                 ─────────────           ──────────────
does inference                                          
  → returns FLResult  ───FLResult over Binder──►  Adapter:
                                                    unwrap FLResult
                                                      → success? return data
                                                      → failure? throw FoundryLocalException
                                                  
                                                  Customer code:
                                                    try {
                                                      val result = model.isCached()
                                                    } catch (e: FoundryLocalException) { … }
```

**Embedded SDK (planned):**

No process boundary means no `FLResult` at all. JNI calls return values
directly and throw exceptions natively:

```
Same Process
────────────
Customer code:
  try {
    val result = model.isCached()  // → JNI call → native code → returns directly
  } catch (e: FoundryLocalException) { … }
```

### Summary

| | IPC SDK | Embedded SDK |
|---|---|---|
| Wire format | `FLResult` (required by AIDL) | Direct return + throw |
| Customer sees | Exception-based (adapter unwraps) | Exception-based (native) |
| `FLResult` exists? | Yes, under the hood | No |
| Custom exceptions cross boundary? | No (Android limitation) | N/A (no boundary) |

## Streaming: Callbacks vs Flow

A similar pattern applies to streaming (chat completion, audio transcription):

**AIDL boundary** uses `IFoundryOperationProgressCallback` — an AIDL callback
interface that the service invokes on the client's binder thread.

**Shared API** uses `Flow<ChatCompletionChunk>` — idiomatic Kotlin, with
structured cancellation.

The adapter bridges between them using `callbackFlow`:

```kotlin
// Adapter converts AIDL callback → Flow
override fun completeChatStreaming(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
    callbackFlow {
        val callback = object : IFoundryOperationProgressCallback.Stub() {
            override fun onProgressUpdate(…) { trySend(chunk) }
            override fun onOperationComplete(…) { close() }
        }
        ipcClient.completeChatStreaming(ipcRequest, callback)
        awaitClose { /* cancel remote operation */ }
    }
```

Cancelling the Flow collector automatically cancels the remote IPC operation
via `cancelOperation()`.

## Thread Safety

AIDL callbacks arrive on **binder threads**, not the main thread or the
coroutine dispatcher. The adapter uses `AtomicBoolean` flags to safely
coordinate between the binder callback thread and the coroutine's `awaitClose`
block, ensuring cancel signals are only sent on actual collector cancellation
(not on normal completion or startup failure).

## Fat AAR Packaging

The `createFatAar` Gradle task merges three modules into a single AAR for
distribution:

1. `FoundryLocalIPCSDK` — the SDK code + adapter layer
2. `FoundryLocalIPCContract` — AIDL interfaces and parcelables
3. `FoundryLocalAPI` — shared API contract interfaces

This means AAR consumers get everything they need in one dependency, without
needing to declare separate dependencies on the contract modules.

## Migration Path

Existing IPC SDK customers can adopt the shared API incrementally:

```kotlin
// Old pattern — callback + FLResult
val manager = FoundryLocalManager(Configuration(appName = "MyApp"))
manager.connect(context, object : FoundryServiceConnectionCallback {
    override fun onServiceConnected(mgr) {
        val result = manager.getCatalog()
        if (!result.status) { /* handle error */ }
        val catalog = result.data!!
    }
    override fun onServiceDisconnected(err, msg) { /* handle */ }
})

// New pattern — suspend + exceptions (same IPC underneath)
val manager = IpcFoundryLocalManager.create(context, Configuration(appName = "MyApp"))
val catalog = manager.getCatalog()  // throws on failure
```

Both patterns coexist — the old API is unchanged and fully supported. The
adapter's `ipc` property provides escape-hatch access to the underlying IPC
manager for features not yet in the shared API.

## Factory Registration

The SDK uses a factory pattern so clients don't need to know which
implementation (IPC or embedded) is being used:

```kotlin
// Client code — implementation-agnostic
lifecycleScope.launch {
    val config = Configuration(appName = "my-app")
    val manager = FoundryLocalManager.create(context, config)
}
```

The IPC SDK auto-registers its factory via an Android `ContentProvider`
(`IpcSdkInitProvider`), which runs before `Application.onCreate()`. This is the
same pattern used by Firebase, WorkManager, and other Android libraries.

When the embedded SDK is added, it will register its own factory the same way.
Only one factory can be active at a time — the last registration wins.
