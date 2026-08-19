# Foundry Local Android SDK — Feature Guide

---

## 1. Download Cancellation

Pass a `CancellationToken` to `model.download()` and call `.cancel()` to stop an in-progress download.

```kotlin
val token = CancellationToken()

model.download(
    context = context,
    progressCallback = callback,
    cancellationToken = token
)

// Cancel at any time
token.cancel()
```

The download foreground service will stop and notify via `onOperationComplete` with `successful = false`.

---

## 2. Download Timeout

Pass `timeoutMinutes` (range: 1–120) to `model.download()`. Defaults to Core's built-in timeout if `0`.

```kotlin
model.download(
    context = context,
    progressCallback = callback,
    timeoutMinutes = 10   // cancel download after 10 min
)
```

---

## 3. Parallel Download Threads

Set `NumModelDownloadThreads` in `additionalSettings` when initializing. Core default is 8.

```kotlin
val options = Configuration(
    appName = "MyApp",
    additionalSettings = mapOf("NumModelDownloadThreads" to "16")
)
val manager = FoundryLocalManager(options)
```

---

## 4. Catalog Region

The catalog region is auto-detected by the native Core. You can override it by setting `CatalogRegion` in `additionalSettings`:

```kotlin
// Default: auto-detected (no CatalogRegion needed)
val options = Configuration(appName = "MyApp")

// Override: explicitly set a region
val options = Configuration(
    appName = "MyApp",
    additionalSettings = mapOf("CatalogRegion" to "westus")
)
```

---

## 5. Version Compatibility Check

Call after connecting to verify the SDK and App versions are mutually compatible.

```kotlin
val result = manager.checkCompatibility()
if (result.status) {
    val info = result.data!!
    if (info.isCompatible) {
        // Safe to proceed
    } else {
        // Show upgrade prompt: info.message has details
        Log.w(TAG, info.message)
    }
}
```

Returns a `CompatibilityInfo` with `isCompatible`, `sdkVersion`, `appVersion`, and a human-readable `message`.
