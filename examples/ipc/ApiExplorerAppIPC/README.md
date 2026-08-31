# ApiExplorerAppIPC

Foundry Local runs AI models directly on Android devices. `ApiExplorerAppIPC` shows the new shared API in IPC mode with a simple Compose screen for the full model lifecycle.

## Quick Example

```kotlin
fun runModel(modelAlias: String) {
    lifecycleScope.launch {
        val manager = FoundryLocalManager.create(
            context = context,
            config = Configuration(appName = "ApiExplorerAppIPC"),
            onDisconnected = { /* update UI */ }
        )

        val catalog = manager.getCatalog()
        val model = catalog.getModel(modelAlias)
        model.download(progress = { pct -> println(pct) }, contentIntent = null, timeoutMinutes = 30)
        model.load()
        val chatClient = model.createChatClient()
        val response = chatClient.completeChat(
            ChatCompletionRequest(messages = listOf(ChatMessage.user("Hello")))
        )
        println(response.message?.content)
    }
}
```

The application supplies `modelAlias` from its model selection or configuration.

## What This App Demonstrates

1. Connect to the Foundry Local service with `FoundryLocalManager.create(...)`
2. List the catalog with `catalog.listModels()`
3. Download a model with coroutine progress callbacks and cancel via `Job.cancel()`
4. Load and unload the model with suspend functions
5. Run synchronous chat with `completeChat(...)`
6. Run streaming chat with `completeChatStreaming(...).collect { ... }`
7. Remove the cached model with `removeFromCache()`
8. Recover after disconnects with `isConnected` and `reconnect()`

> **Good to know:** Release AARs are not committed to this repository. Place the verified IPC
> AAR in this sample's `libs/` directory before building.

## Build and Run

1. Download `foundry-local-ipc-sdk-<version>.aar` from the matching GitHub Release, verify its
   published SHA-256 hash, and place it in:

   ```text
   examples/ipc/ApiExplorerAppIPC/libs/
   ```

2. Install the [Foundry Local service app from Google Play](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).

3. Build the app from the repository root:

   ```bash
   ./gradlew :ApiExplorerAppIPC:assembleDebug
   ```

4. Connect an Android device and install the app:

   ```bash
   ./gradlew :ApiExplorerAppIPC:installDebug
   ```

5. Open **API Explorer IPC** from the device launcher.

> **Good to know:** Keep only one IPC AAR in the sample's `libs/` directory. Gradle loads every AAR
> in that directory, and multiple versions cause duplicate-class errors.

> **Good to know:** This example uses the shared API package `com.microsoft.foundrylocal.api.*` only. It does not use the older IPC-specific callback API, `FLResult`, or AIDL callback stubs.
