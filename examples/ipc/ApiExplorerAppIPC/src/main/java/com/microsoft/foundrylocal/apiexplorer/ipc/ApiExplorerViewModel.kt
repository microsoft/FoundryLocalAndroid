/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.apiexplorer.ipc

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.foundrylocal.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiExplorerViewModel : ViewModel() {

    data class ModelEntry(
        val alias: String,
        val displayName: String,
        val fileSizeMb: Long
    )

    companion object {
        private const val TAG = "ApiExplorerViewModel"
        private const val MODEL_NAME = "qwen2.5-coder-0.5b-instruct-generic-cpu:4"
    }

    var isConnected by mutableStateOf(false)
        private set
    var hasManager by mutableStateOf(false)
        private set
    var catalogModels = mutableStateListOf<ModelEntry>()
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var isCached by mutableStateOf(false)
        private set
    var isLoaded by mutableStateOf(false)
        private set
    var chatInput by mutableStateOf("")
    var isBusy by mutableStateOf(false)
        private set
    var outputLog = mutableStateListOf<String>()
        private set

    private var manager: FoundryLocalManager? = null
    private var model: Model? = null
    private var chatClient: ChatClient? = null

    init {
        // Observe download progress from the process-level singleton.
        // This allows a reopened app to pick up live progress from an ongoing download.
        viewModelScope.launch {
            ActiveDownloadState.state.collect { downloadState ->
                withContext(Dispatchers.Main) {
                    downloadProgress = downloadState.progressPercent
                    if (downloadState.isActive) {
                        isDownloading = true
                    } else {
                        isDownloading = false
                        isBusy = false
                        ActiveDownloadState.consumeCompletionMessage()?.let { msg ->
                            appendLog(msg)
                            if (downloadState.successful) {
                                isCached = true
                                launch(Dispatchers.IO) { refreshModelState() }
                            }
                        }
                    }
                }
            }
        }
    }

    fun connect(context: Context) {
        if (isConnected || isBusy) return
        // If a manager already exists (disconnected state), use reconnect() instead
        if (manager != null) {
            reconnect()
            return
        }

        isBusy = true
        appendLog("Connecting to Foundry Local…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val createdManager = FoundryLocalManager.create(
                    context = context,
                    config = Configuration(
                        appName = "ApiExplorerAppIPC",
                        logLevel = "Debug"
                    ),
                    onDisconnected = {
                        viewModelScope.launch(Dispatchers.Main) {
                            handleDisconnected("Connection lost. Tap Reconnect to restore the session.")
                        }
                    }
                )

                manager = createdManager
                val compatibility = createdManager.checkCompatibility()
                refreshModelState()

                withContext(Dispatchers.Main) {
                    hasManager = true
                    isConnected = createdManager.isConnected
                    appendLog("Connected ✓")
                    appendLog(
                        if (compatibility.isCompatible) {
                            "Compatibility: ${compatibility.message}"
                        } else {
                            "Compatibility warning: ${compatibility.message}"
                        }
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Connect failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Connect failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun reconnect() {
        val currentManager = manager ?: return
        if (isBusy) return

        isBusy = true
        appendLog("Reconnecting…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                model = null
                chatClient = null
                currentManager.reconnect()
                refreshModelState()

                withContext(Dispatchers.Main) {
                    isConnected = currentManager.isConnected
                    appendLog("Reconnected ✓")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Reconnect failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Reconnect failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun listCatalog() {
        val currentManager = manager ?: return
        if (!isConnected || isBusy) return

        isBusy = true
        appendLog("Listing catalog models…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models = currentManager
                    .getCatalog()
                    .listModels()
                    .sortedBy { it.displayName.lowercase(java.util.Locale.ROOT) }

                withContext(Dispatchers.Main) {
                    catalogModels.clear()
                    catalogModels.addAll(
                        models.map { info ->
                            ModelEntry(
                                alias = info.alias,
                                displayName = info.displayName,
                                fileSizeMb = info.fileSizeMb
                            )
                        }
                    )
                    appendLog("Found ${models.size} model(s)")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "List catalog failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Catalog failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun downloadModel(context: Context) {
        if (!isConnected || isBusy || isDownloading) return
        if (ActiveDownloadState.isActive) {
            appendLog("Download already in progress")
            return
        }

        isBusy = true
        appendLog("Downloading $MODEL_NAME…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentModel = resolveModel()
                if (currentModel.isCached()) {
                    withContext(Dispatchers.Main) {
                        isCached = true
                        isBusy = false
                        appendLog("Model already cached ✓")
                    }
                    return@launch
                }

                // Start download in process-level scope (survives swipe-away)
                ActiveDownloadState.startDownload(currentModel, context)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Download setup failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Download failed: ${error.userMessage()}")
                    isBusy = false
                }
            }
        }
    }

    fun cancelDownload() {
        if (!isDownloading) return
        appendLog("Cancelling download…")
        ActiveDownloadState.cancelDownload()
    }

    fun loadModel() {
        if (!isConnected || isBusy || isLoaded) return

        isBusy = true
        appendLog("Loading model…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentModel = resolveModel()
                currentModel.load()
                chatClient = currentModel.createChatClient()
                refreshModelState()

                withContext(Dispatchers.Main) {
                    appendLog("Model loaded ✓")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Load failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Load failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun unloadModel() {
        if (!isConnected || isBusy || !isLoaded) return

        isBusy = true
        appendLog("Unloading model…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                resolveModel().unload()
                chatClient = null
                refreshModelState()

                withContext(Dispatchers.Main) {
                    appendLog("Model unloaded ✓")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Unload failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Unload failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun sendChat() {
        if (!isConnected || !isLoaded || isBusy) return

        val prompt = chatInput.trim()
        if (prompt.isEmpty()) return

        chatInput = ""
        isBusy = true
        appendLog("User: $prompt")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = requireChatClient().completeChat(
                    ChatCompletionRequest(
                        messages = listOf(ChatMessage.user(prompt))
                    )
                )

                withContext(Dispatchers.Main) {
                    appendLog("Assistant: ${response.message?.content?.ifBlank { "(empty response)" } ?: "(empty response)"}")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Chat failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Chat failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun sendChatStreaming() {
        if (!isConnected || !isLoaded || isBusy) return

        val prompt = chatInput.trim()
        if (prompt.isEmpty()) return

        chatInput = ""
        isBusy = true
        appendLog("User: $prompt")
        val streamingIndex = outputLog.size
        outputLog.add("Assistant (streaming): ")

        viewModelScope.launch(Dispatchers.IO) {
            val responseText = StringBuilder()
            try {
                requireChatClient()
                    .completeChatStreaming(
                        ChatCompletionRequest(
                            messages = listOf(ChatMessage.user(prompt))
                        )
                    )
                    .collect { chunk ->
                        responseText.append(chunk.delta)
                        withContext(Dispatchers.Main) {
                            updateStreamingLog(
                                streamingIndex,
                                "Assistant (streaming): ${responseText}"
                            )
                        }
                    }

                withContext(Dispatchers.Main) {
                    if (responseText.length == 0) {
                        updateStreamingLog(streamingIndex, "Assistant (streaming): (empty response)")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Streaming chat failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Streaming failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun removeCachedModel() {
        if (!isConnected || isBusy || !isCached) return

        isBusy = true
        appendLog("Removing cached model…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentModel = resolveModel()
                if (currentModel.isLoaded()) {
                    withContext(Dispatchers.Main) {
                        appendLog("Unload the model before removing it from cache.")
                    }
                    return@launch
                }

                currentModel.removeFromCache()
                chatClient = null
                refreshModelState()

                withContext(Dispatchers.Main) {
                    appendLog("Removed cached model ✓")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Remove cached model failed", error)
                withContext(Dispatchers.Main) {
                    appendLog("Remove failed: ${error.userMessage()}")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isBusy = false
                }
            }
        }
    }

    fun clearCatalog() {
        catalogModels.clear()
    }

    fun clearLog() {
        outputLog.clear()
    }

    override fun onCleared() {
        // Do NOT cancel the download here — it runs in process-level scope
        // and should survive ViewModel destruction (foreground service keeps process alive)
        manager?.close()
        super.onCleared()
    }

    private suspend fun resolveModel(): Model {
        model?.let { return it }

        val currentManager = manager ?: throw IllegalStateException("Connect first.")
        return currentManager.getCatalog().getModel(MODEL_NAME).also { model = it }
    }

    private suspend fun requireChatClient(): ChatClient {
        chatClient?.let { return it }

        val currentModel = resolveModel()
        if (!currentModel.isLoaded()) {
            throw IllegalStateException("Load the model first.")
        }
        return currentModel.createChatClient().also { chatClient = it }
    }

    private suspend fun refreshModelState() {
        val currentManager = manager ?: return
        val currentModel = try {
            model ?: currentManager.getCatalog().getModel(MODEL_NAME).also { model = it }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Unable to resolve demo model", error)
            withContext(Dispatchers.Main) {
                isCached = false
                isLoaded = false
                chatClient = null
            }
            return
        }

        val cached = currentModel.isCached()
        val loaded = currentModel.isLoaded()
        val downloading = currentModel.isDownloading()
        val client = when {
            !loaded -> null
            chatClient != null -> chatClient
            else -> try {
                currentModel.createChatClient()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create ChatClient during refresh", e)
                null
            }
        }

        withContext(Dispatchers.Main) {
            isCached = cached
            isLoaded = loaded
            isDownloading = downloading
            chatClient = client
        }
    }

    private fun handleDisconnected(message: String) {
        isConnected = false
        isBusy = false
        isDownloading = false
        isLoaded = false
        model = null
        chatClient = null
        ActiveDownloadState.cancelDownload()
        appendLog(message)
    }

    private fun appendLog(message: String) {
        outputLog.add(message)
    }

    private fun updateStreamingLog(index: Int, value: String) {
        if (index in outputLog.indices) {
            outputLog[index] = value
        } else {
            outputLog.add(value)
        }
    }

    private fun Exception.userMessage(): String = message ?: javaClass.simpleName
}
