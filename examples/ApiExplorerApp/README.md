# ApiExplorerApp

Foundry Local runs AI models directly on Android devices. `ApiExplorerApp` shows the shared API in IPC mode with a simple Compose screen for the full model lifecycle.

## Quick Example

```kotlin
// Most shared API methods are suspend functions — call from a coroutine
lifecycleScope.launch {
    val manager = FoundryLocalManager.create(
        context = context,
        config = Configuration(appName = "ApiExplorerApp"),
        onDisconnected = { /* update UI */ }
    )

    val catalog = manager.getCatalog()
    val model = catalog.getModel("qwen2.5-coder-0.5b-instruct-generic-cpu:4")
    model.download(progress = { pct -> println(pct) }, contentIntent = null, timeoutMinutes = 30)
    model.load()
    val chatClient = model.createChatClient()
    val response = chatClient.completeChat(
        ChatCompletionRequest(messages = listOf(ChatMessage.user("Hello")))
    )
    println(response.message?.content)
}
```

## What This App Demonstrates

1. Connect to the Foundry Local service with `FoundryLocalManager.create(...)`
2. List the catalog with `catalog.listModels()`
3. Download a model with coroutine progress callbacks and cancel via `Job.cancel()`
4. Load and unload the model with suspend functions
5. Run synchronous chat with `completeChat(...)`
6. Run streaming chat with `completeChatStreaming(...).collect { ... }`
7. Remove the cached model with `removeFromCache()`
8. Recover after disconnects with `isConnected` and `reconnect()`

> **Good to know:** Download `foundry-local-ipc-sdk-{version}.aar` from
> [GitHub Releases](https://github.com/microsoft/FoundryLocalAndroid/releases)
> and place it in `libs/`.

## Setup

1. Clear old AARs and copy the downloaded release AAR:
   ```bash
   rm -f examples/ApiExplorerApp/libs/*.aar
   cp foundry-local-ipc-sdk-{version}.aar examples/ApiExplorerApp/libs/
   ```
2. Install the [Foundry Local companion app](https://play.google.com/store/apps/details?id=com.microsoft.foundrylocal.app).
3. Build and install `:ApiExplorerApp`.

> **Good to know:** This example uses the shared API package `com.microsoft.foundrylocal.api.*` only. It does not use the older IPC-specific callback API, `FLResult`, or AIDL callback stubs.
