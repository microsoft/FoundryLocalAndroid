# Foundry Local Android - Code Examples

Complete, runnable code examples for integrating Foundry Local into your Android application.

> **📱 Example Apps**: See the [examples/](../examples/) directory for standalone apps:
> - [ChatApp](../examples/ChatApp) — Polished chat with optional voice input
> - [AudioTranscriptionApp](../examples/AudioTranscriptionApp) — File and real-time audio transcription
> - [ApiExplorerApp](../examples/ApiExplorerApp) — Interactive SDK API reference and lifecycle demo
>
> If these app directories are not present in your checkout yet, update to the latest release tag or the `main` branch.
>
> E2E tests live separately in [testing/ipc-e2e/](../testing/ipc-e2e/) (`:ExampleApp` module).

## Table of Contents

- [Basic Examples](#basic-examples)
  - [Minimal Integration](#minimal-integration)
  - [Simple Chat](#simple-chat)
  - [Streaming Chat](#streaming-chat)

---

## Basic Examples

### Minimal Integration

The simplest possible integration - connect, get a model, and run inference.

```kotlin
import com.microsoft.foundrylocal.FoundryLocalManager
import com.microsoft.foundrylocal.FoundryChatCompletionClient
import com.microsoft.foundrylocal.IFoundryLocalManager
import com.microsoft.foundrylocal.datamodels.Configuration
import com.microsoft.foundrylocal.datamodels.chat.ChatCompletionRequest
import com.microsoft.foundrylocal.datamodels.chat.ChatMessage
import com.microsoft.foundrylocal.callbacks.FoundryServiceConnectionCallback
import com.microsoft.foundrylocal.callbacks.FoundryOperationProgressCallback
import kotlinx.coroutines.*

class MinimalExample : AppCompatActivity() {
    private lateinit var manager: FoundryLocalManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize
        val options = Configuration(appName = "MinimalApp")
        manager = FoundryLocalManager(options)
        
        // Connect
        manager.connect(this, object : FoundryServiceConnectionCallback {
            override fun onServiceConnected(service: IFoundryLocalManager) {
                runInference()
            }
            
            override fun onServiceDisconnected(
                errorCode: FoundryServiceConnectionCallback.ErrorCode, 
                message: String?
            ) {
                Log.e(TAG, "Disconnected: ${message ?: "unknown"}")
            }
        })
    }
    
    private fun runInference() {
        lifecycleScope.launch(Dispatchers.Default) {
            // Get model
            val catalog = manager.getCatalog().data
            val model = catalog?.getModel("phi-3-mini-4k")?.data
            
            // Ensure model is loaded
            if (model?.isLoaded()?.data != true) {
                model?.load(object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Int,
                        message: String?
                    ) {}
                    
                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        if (successful) performChat(model)
                    }
                })
            } else {
                performChat(model)
            }
        }
    }
    
    private fun performChat(model: FoundryModel) {
        lifecycleScope.launch(Dispatchers.Default) {
            val chatClient = model.createChatCompletionClient().data
            
            val request = ChatCompletionRequest().apply {
                messages.add(ChatMessage(ChatMessage.Role.USER, "Hello!"))
            }
            
            val response = chatClient?.completeChat(request)?.data?.message?.content
            
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Response: $response")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        manager.disconnect(this)
    }
    
    companion object {
        private const val TAG = "MinimalExample"
    }
}
```

---

### Simple Chat

Basic chat with a single question and response.

```kotlin
class ChatActivity : AppCompatActivity() {
    private lateinit var manager: FoundryLocalManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val options = Configuration(appName = "ChatApp")
        manager = FoundryLocalManager(options)
        
        manager.connect(this, object : FoundryServiceConnectionCallback {
            override fun onServiceConnected(service: IFoundryLocalManager) {
                askQuestion("What is the capital of France?")
            }
            
            override fun onServiceDisconnected(
                errorCode: FoundryServiceConnectionCallback.ErrorCode, 
                message: String?
            ) {
                Toast.makeText(
                    this@ChatActivity, 
                    "Service disconnected", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }
    
    private fun askQuestion(question: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val catalog = manager.getCatalog().data
            val model = catalog?.getModel("phi-3-mini-4k")?.data
            
            // Load if needed
            if (model?.isLoaded()?.data != true) {
                model?.load(object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Int,
                        message: String?
                    ) {
                        Log.d(TAG, "Loading: $progressPercent%")
                    }
                    
                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        if (successful) {
                            sendMessage(model, question)
                        } else {
                            Log.e(TAG, "Load failed: $errorMessage")
                        }
                    }
                })
            } else {
                sendMessage(model, question)
            }
        }
    }
    
    private fun sendMessage(model: FoundryModel, message: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val chatClient = model.createChatCompletionClient().data
            
            val request = ChatCompletionRequest().apply {
                messages.add(ChatMessage(ChatMessage.Role.USER, message))
                temperature = 0.7f
                maxTokens = 150
            }
            
            val response = chatClient?.completeChat(request)?.data?.message?.content
            
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@ChatActivity,
                    response,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        manager.disconnect(this)
    }
    
    companion object {
        private const val TAG = "ChatActivity"
    }
}
```

---

### Streaming Chat

Chat with streaming token-by-token responses.

```kotlin
class StreamingChatActivity : AppCompatActivity() {
    private lateinit var manager: FoundryLocalManager
    private var chatClient: FoundryChatCompletionClient? = null
    private lateinit var responseTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming_chat)
        
        responseTextView = findViewById(R.id.responseText)
        
        val options = Configuration(appName = "StreamingChatApp")
        manager = FoundryLocalManager(options)
        
        manager.connect(this, object : FoundryServiceConnectionCallback {
            override fun onServiceConnected(service: IFoundryLocalManager) {
                initializeModel()
            }
            
            override fun onServiceDisconnected(
                errorCode: FoundryServiceConnectionCallback.ErrorCode, 
                message: String?
            ) {
                Log.e(TAG, "Disconnected: ${message ?: "unknown"}")
            }
        })
        
        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val input = findViewById<EditText>(R.id.inputText).text.toString()
            if (input.isNotEmpty()) {
                streamResponse(input)
            }
        }
    }
    
    private fun initializeModel() {
        lifecycleScope.launch(Dispatchers.Default) {
            val catalog = manager.getCatalog().data
            val model = catalog?.getModel("phi-3-mini-4k")?.data
            
            if (model?.isLoaded()?.data != true) {
                model?.load(object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Int,
                        message: String?
                    ) {}
                    
                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        if (successful) {
                            chatClient = model.createChatCompletionClient().data
                        }
                    }
                })
            } else {
                chatClient = model.createChatCompletionClient().data
            }
        }
    }
    
    private fun streamResponse(userMessage: String) {
        val request = ChatCompletionRequest().apply {
            messages.add(ChatMessage(ChatMessage.Role.USER, userMessage))
            temperature = 0.8f
            maxTokens = 200
        }
        
        val responseBuilder = StringBuilder()
        
        chatClient?.completeChatStreaming(request, 
            object : IFoundryOperationProgressCallback.Stub() {
                override fun onProgressUpdate(
                    operationType: Int,
                    modelAlias: String,
                    status: Int,
                    progressPercent: Int,
                    message: String
                ) {
                    responseBuilder.append(message)
                    runOnUiThread {
                        responseTextView.text = responseBuilder.toString()
                    }
                }
                
                override fun onOperationComplete(
                    operationType: Int,
                    modelAlias: String,
                    successful: Boolean,
                    errorMessage: String?
                ) {
                    if (successful) {
                        Log.d(TAG, "Streaming completed")
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@StreamingChatActivity,
                                "Error: $errorMessage",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        manager.disconnect(this)
    }
    
    companion object {
        private const val TAG = "StreamingChatActivity"
    }
}
```
---

## See Also

- [Integration Guide](INTEGRATION_GUIDE.md) - Quick start guide
- [API Reference](API_REFERENCE.md) - Complete API documentation
- [Best Practices](BEST_PRACTICES.md) - Development guidelines
- [Troubleshooting](TROUBLESHOOTING.md) - Common issues and solutions
