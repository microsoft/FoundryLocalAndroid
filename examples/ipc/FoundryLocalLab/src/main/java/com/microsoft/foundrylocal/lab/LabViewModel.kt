package com.microsoft.foundrylocal.lab

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.foundrylocal.api.AudioClient
import com.microsoft.foundrylocal.api.AudioStreamResult
import com.microsoft.foundrylocal.api.AudioStreamSession
import com.microsoft.foundrylocal.api.AudioStreamSettings
import com.microsoft.foundrylocal.api.Catalog
import com.microsoft.foundrylocal.api.ChatClient
import com.microsoft.foundrylocal.api.ChatMessage
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
import com.microsoft.foundrylocal.api.Model
import com.microsoft.foundrylocal.api.ModelInfo
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LabModelChoice(
    val alias: String,
    val displayName: String,
    val task: String,
    val fileSizeMb: Long,
    val executionProvider: String,
    val deviceType: String,
    val supportsToolCalling: Boolean,
    val maxOutputTokens: Int,
    val name: String = "",
    val version: String = "",
    val uri: String = ""
) {
    val capability: LabModelCapability
        get() = LabModelCapability.classify(task, alias)

    val supportsFileTranscription: Boolean
        get() = capability == LabModelCapability.AUDIO && alias.contains("whisper", ignoreCase = true)

    val supportsLiveTranscription: Boolean
        get() = capability == LabModelCapability.AUDIO &&
            alias.contains("nemotron-speech-streaming-en", ignoreCase = true)
}

enum class LabMessageRole {
    USER,
    ASSISTANT
}

data class LabMessage(
    val role: LabMessageRole,
    val content: String,
    val durationMs: Long? = null,
    val includeInHistory: Boolean = true
)

data class LabUiState(
    val isConnected: Boolean = false,
    val apiVersion: String = "Unknown",
    val sdkVersion: String = "Unknown",
    val runtimeVersion: String = "Unknown",
    val isCompatible: Boolean? = null,
    val compatibilityMessage: String = "Not checked",
    val cacheLocation: String = "Service managed",
    val availableModels: List<LabModelChoice> = emptyList(),
    val selectedModelAlias: String = "",
    val cachedModelCount: Int = 0,
    val activeModelAlias: String? = null,
    val isDownloaded: Boolean = false,
    val isLoaded: Boolean = false,
    val isChatReady: Boolean = false,
    val isAudioReady: Boolean = false,
    val isCatalogRefreshing: Boolean = false,
    val isBusy: Boolean = false,
    val operationLabel: String? = null,
    val progressPercent: Int = 0,
    val systemPrompt: String = "You are a concise on-device assistant. Be accurate and state uncertainty plainly.",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 512,
    val chatParameters: LabChatParameters = LabChatParameters(),
    val audioParameters: LabAudioParameters = LabAudioParameters(),
    val chatInput: String = "",
    val chatError: String? = null,
    val messages: List<LabMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val lastRawResponse: String = "",
    val lastInferenceDurationMs: Long? = null,
    val selectedAudioFileName: String = "",
    val isAudioFilePreparing: Boolean = false,
    val audioDurationMs: Long? = null,
    val transcriptionText: String = "",
    val streamingPartialText: String = "",
    val isTranscribing: Boolean = false,
    val isListening: Boolean = false,
    val isStreamStopping: Boolean = false,
    val audioError: String? = null,
    val runtimeEvents: List<String> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    val selectedModel: LabModelChoice?
        get() = availableModels.firstOrNull { it.alias == selectedModelAlias }

    val chatModelCount: Int
        get() = availableModels.count { it.capability == LabModelCapability.CHAT }

    val audioModelCount: Int
        get() = availableModels.count { it.capability == LabModelCapability.AUDIO }

    val isCapabilityRunning: Boolean
        get() = isGenerating || isTranscribing || isListening || isStreamStopping || isAudioFilePreparing
}

/**
 * A capability workbench for the public Foundry Local 0.1.6 coroutine API.
 * The selected model is the only model loaded by this app at any one time.
 */
class LabViewModel : ViewModel() {
    private companion object {
        const val TAG = "LabViewModel"
        const val FALLBACK_MODEL_ALIAS = "qwen2.5-coder-0.5b"
        const val MAX_RUNTIME_EVENTS = 30
        const val MAX_CONVERSATION_MESSAGES = 12
        const val MIN_MAX_TOKENS = 64
        const val FALLBACK_MAX_TOKENS = 2_048
    }

    private data class StreamState(
        val session: AudioStreamSession,
        val finalized: AtomicBoolean = AtomicBoolean(false)
    )

    private val _uiState = MutableStateFlow(LabUiState())
    val uiState: StateFlow<LabUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var manager: FoundryLocalManager? = null
    private var catalog: Catalog? = null
    private var model: Model? = null
    private var chatClient: ChatClient? = null
    private var audioClient: AudioClient? = null
    private var selectedAudioPath: String? = null

    private var connectionJob: Job? = null
    private var catalogJob: Job? = null
    private var modelOperationJob: Job? = null
    private var generationJob: Job? = null
    private var filePreparationJob: Job? = null
    private var transcriptionJob: Job? = null
    private var streamSetupJob: Job? = null
    private var captureJob: Job? = null
    private var pushJob: Job? = null
    private var streamCleanupJob: Job? = null

    private var streamState: StreamState? = null
    private var audioRecord: AudioRecord? = null
    private var audioChunks: Channel<ByteArray>? = null
    private val isCaptureActive = AtomicBoolean(false)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: ComponentActivity) {
        if (initialized) return
        initialized = true
        connect(context)
    }

    private fun connect(context: ComponentActivity, reconnecting: Boolean = false) {
        if (connectionJob?.isActive == true || (!reconnecting && manager?.isConnected == true)) return
        appendEvent("Connecting to Foundry Local IPC service")
        _uiState.update { it.copy(isBusy = true, operationLabel = "Connecting") }
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (reconnecting) {
                    catalogJob?.cancelAndJoin()
                    modelOperationJob?.cancelAndJoin()
                    _uiState.update { it.copy(isBusy = true, operationLabel = "Connecting") }
                    cancelCapabilityWorkAndJoin()
                    model = null
                    catalog = null
                    chatClient = null
                    audioClient = null
                }
                val currentManager = manager?.also { it.reconnect() } ?: FoundryLocalManager.create(
                    context = context,
                    config = Configuration(appName = "FoundryLocalLab", logLevel = "Information"),
                    onDisconnected = {
                        viewModelScope.launch(Dispatchers.Main) {
                            handleDisconnected("Connection lost. Reconnect to reacquire runtime handles.")
                        }
                    }
                )
                manager = currentManager
                _uiState.update { it.copy(isConnected = currentManager.isConnected, errorMessage = null) }
                appendEvent("IPC service connected")
                refreshCatalogAndSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendEvent("Connection failed: ${error.userMessage()}")
                _uiState.update { it.copy(isConnected = false, errorMessage = "Connection failed: ${error.userMessage()}") }
            } finally {
                _uiState.update { it.copy(isBusy = false, operationLabel = null) }
                connectionJob = null
            }
        }
    }

    fun reconnect(context: ComponentActivity) {
        if (connectionJob?.isActive == true) return
        _uiState.update {
            it.copy(
                isConnected = false,
                isDownloaded = false,
                isLoaded = false,
                isChatReady = false,
                isAudioReady = false,
                activeModelAlias = null,
                errorMessage = null
            )
        }
        connect(context, reconnecting = true)
    }

    fun refreshCatalog() {
        if (!_uiState.value.isConnected || _uiState.value.isBusy || catalogJob?.isActive == true || modelOperationJob != null) return
        _uiState.update { it.copy(isBusy = true, operationLabel = "Refreshing catalog", isCatalogRefreshing = true) }
        catalogJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshCatalogAndSelection()
            } finally {
                _uiState.update { it.copy(isBusy = false, operationLabel = null, isCatalogRefreshing = false) }
                catalogJob = null
            }
        }
    }

    private suspend fun refreshCatalogAndSelection() {
        _uiState.update { it.copy(isCatalogRefreshing = true) }
        try {
            val currentManager = manager ?: return
            val compatibility = currentManager.checkCompatibility()
            val versionInfo = currentManager.getVersionInfo()
            val currentCatalog = currentManager.getCatalog()
            catalog = currentCatalog
            val choices = currentCatalog.listModels()
                .map(::toChoice)
                .sortedWith(
                    compareBy<LabModelChoice> { it.capability != LabModelCapability.CHAT }
                        .thenBy { it.displayName.lowercase(Locale.ROOT) }
                )
            val existingAlias = _uiState.value.selectedModelAlias
            val cachedModels = currentCatalog.getCachedModels().map { it.identity() }
            val selectedAlias = FoundryModelIdentityResolver.preferredAlias(
                candidates = choices
                    .filter { it.capability.isRunnableInCurrentBuild }
                    .map { it.identity() },
                existingAlias = existingAlias,
                cachedModels = cachedModels,
                fallbackAlias = FALLBACK_MODEL_ALIAS
            )
            val selected = choices.firstOrNull { it.alias == selectedAlias }
            _uiState.update {
                it.copy(
                    apiVersion = currentManager.getAPIVersion().ifBlank { "Unknown" },
                    sdkVersion = compatibility.sdkVersion.ifBlank { "Unknown" },
                    runtimeVersion = compatibility.appVersion.ifBlank { versionInfo.versionName.ifBlank { "Unknown" } },
                    isCompatible = compatibility.isCompatible,
                    compatibilityMessage = compatibility.message,
                    cacheLocation = currentCatalog.getCacheLocation().ifBlank { "Service managed" },
                    availableModels = choices,
                    selectedModelAlias = selectedAlias,
                    maxTokens = it.maxTokens.coerceIn(MIN_MAX_TOKENS, selected.configurableMaxTokens()),
                    errorMessage = when {
                        choices.isEmpty() -> "No models were returned by the catalog."
                        !compatibility.isCompatible -> "Foundry Local version warning: ${compatibility.message}"
                        else -> null
                    }
                )
            }
            appendEvent(
                "Compatibility ${if (compatibility.isCompatible) "passed" else "warning"}: " +
                    "SDK ${compatibility.sdkVersion}, runtime ${compatibility.appVersion}"
            )
            appendEvent(
                "Catalog: ${choices.size} models · " +
                    "${choices.count { it.capability == LabModelCapability.CHAT }} chat · " +
                    "${choices.count { it.capability == LabModelCapability.AUDIO }} audio"
            )
            if (selectedAlias.isNotBlank()) refreshSelectedModelState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            appendEvent("Catalog refresh failed: ${error.userMessage()}")
            _uiState.update { it.copy(errorMessage = "Catalog refresh failed: ${error.userMessage()}") }
        } finally {
            _uiState.update { it.copy(isCatalogRefreshing = false) }
        }
    }

    fun selectModel(alias: String) {
        val state = _uiState.value
        if (alias == state.selectedModelAlias) return
        val selected = state.availableModels.firstOrNull { it.alias == alias }
        if (selected == null || !selected.capability.isRunnableInCurrentBuild) {
            _uiState.update { it.copy(errorMessage = selected?.capability?.availabilityNote ?: "Model unavailable") }
            return
        }
        if (state.isBusy || modelOperationJob != null || catalogJob?.isActive == true) {
            _uiState.update { it.copy(errorMessage = "Wait for the current model operation to finish.") }
            return
        }
        _uiState.update { it.copy(isBusy = true, operationLabel = "Switching model", progressPercent = 0) }
        modelOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                cancelCapabilityWorkAndJoin()
                val previousModel = model ?: if (state.isLoaded) resolveSelectedModel() else null
                if (previousModel != null && (state.isLoaded || previousModel.isLoaded())) previousModel.unload()
                applySelection(alias)
                refreshSelectedModelState()
                _uiState.update { it.copy(successMessage = "Model switched safely. Load it when ready.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendEvent("Model switch failed: ${error.userMessage()}")
                _uiState.update { it.copy(errorMessage = "Model switch failed: ${error.userMessage()}") }
            } finally {
                finishModelOperation()
            }
        }
    }

    private fun applySelection(alias: String) {
        val selected = _uiState.value.availableModels.first { it.alias == alias }
        model = null
        chatClient = null
        audioClient = null
        _uiState.update {
            it.copy(
                selectedModelAlias = alias,
                isDownloaded = false,
                isLoaded = false,
                isChatReady = false,
                isAudioReady = false,
                activeModelAlias = null,
                progressPercent = 0,
                maxTokens = it.maxTokens.coerceIn(MIN_MAX_TOKENS, selected.configurableMaxTokens()),
                transcriptionText = "",
                streamingPartialText = "",
                audioError = null
            )
        }
        appendEvent("Selected model: $alias")
    }

    private suspend fun refreshSelectedModelState() {
        val currentCatalog = catalog ?: return
        val selected = _uiState.value.selectedModel ?: return
        val currentModel = currentCatalog.getModel(selected.catalogId())
        model = currentModel
        val cachedModels = currentCatalog.getCachedModels()
        val loadedModels = currentCatalog.getLoadedModels()
        val cached = FoundryModelIdentityResolver.isCached(
            selected = selected.identity(),
            cachedModels = cachedModels.map { it.identity() },
            sdkReportsCached = currentModel.isCached()
        )
        val loaded = currentModel.isLoaded() || loadedModels.any {
            FoundryModelIdentityResolver.isSameModel(selected.identity(), it.identity())
        }
        val newChatClient = if (loaded && selected.capability == LabModelCapability.CHAT) {
            runCatching { currentModel.createChatClient() }.getOrNull()
        } else {
            null
        }
        val newAudioClient = if (loaded && selected.capability == LabModelCapability.AUDIO) {
            runCatching { currentModel.createAudioClient() }.getOrNull()
        } else {
            null
        }
        chatClient = newChatClient
        audioClient = newAudioClient
        _uiState.update {
            it.copy(
                cachedModelCount = cachedModels.size,
                isDownloaded = cached,
                isLoaded = loaded,
                isChatReady = newChatClient != null,
                isAudioReady = newAudioClient != null,
                activeModelAlias = selected.alias.takeIf { loaded },
                errorMessage = if (loaded && newChatClient == null && newAudioClient == null) {
                    "Model loaded, but its capability client could not be created."
                } else {
                    it.errorMessage
                }
            )
        }
        appendEvent(
            "Model state: cached=$cached, loaded=$loaded, " +
                "client=${when { newChatClient != null -> "chat"; newAudioClient != null -> "audio"; else -> "none" }}"
        )
    }

    fun downloadModel(context: ComponentActivity) {
        val state = _uiState.value
        if (!state.isConnected || state.selectedModelAlias.isBlank() || state.isDownloaded) return
        if (state.isBusy || modelOperationJob != null || catalogJob?.isActive == true) return
        _uiState.update { it.copy(isBusy = true, operationLabel = "Downloading model", progressPercent = 0, errorMessage = null) }
        modelOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentModel = resolveSelectedModel()
                currentModel.download(
                    progress = { value ->
                        _uiState.update { it.copy(progressPercent = value.toInt().coerceIn(0, 100)) }
                    },
                    contentIntent = mainActivityPendingIntent(context)
                )
                refreshSelectedModelState()
                _uiState.update { it.copy(successMessage = "Model download completed.") }
                appendEvent("Model download completed")
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(errorMessage = "Model download cancelled.") }
                appendEvent("Model download cancelled")
                throw cancelled
            } catch (error: Exception) {
                // A late callback may fail after the service has cached the model. Progress alone
                // is not proof of completion; reconcile against the service before allowing load.
                val reportedError = error.userMessage()
                try {
                    refreshSelectedModelState()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refreshError: Exception) {
                    appendEvent("Download state refresh failed: ${refreshError.userMessage()}")
                }
                if (_uiState.value.isDownloaded) {
                    _uiState.update { it.copy(errorMessage = null, successMessage = "Model is cached despite a download callback error.") }
                    appendEvent("Download reported an error, but the service confirms the model is cached: $reportedError")
                } else {
                    _uiState.update { it.copy(errorMessage = "Model download failed: $reportedError") }
                    appendEvent("Model download failed: $reportedError")
                }
            } finally {
                finishModelOperation()
            }
        }
    }

    fun loadModel() {
        val state = _uiState.value
        val selected = state.selectedModel ?: return
        if (!state.isConnected || !state.isDownloaded || !selected.capability.isRunnableInCurrentBuild) return
        if (state.isBusy || modelOperationJob != null || catalogJob?.isActive == true) return
        _uiState.update { it.copy(isBusy = true, operationLabel = "Loading model", progressPercent = 0, errorMessage = null) }
        modelOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentCatalog = catalog ?: throw IllegalStateException("Catalog unavailable")
                val currentSelection = _uiState.value.selectedModel
                    ?: throw IllegalStateException("Choose a model first")
                unloadOtherModels(currentCatalog, currentSelection)
                val currentModel = resolveSelectedModel()
                currentModel.load()
                when (selected.capability) {
                    LabModelCapability.CHAT -> {
                        chatClient = currentModel.createChatClient()
                        audioClient = null
                    }
                    LabModelCapability.AUDIO -> {
                        audioClient = currentModel.createAudioClient()
                        chatClient = null
                    }
                    LabModelCapability.OTHER -> error("Unsupported model capability")
                }
                _uiState.update {
                    it.copy(
                        isLoaded = true,
                        isChatReady = chatClient != null,
                        isAudioReady = audioClient != null,
                        activeModelAlias = currentSelection.alias,
                        progressPercent = 100,
                        successMessage = "Model loaded; ${selected.capability.label.lowercase()} client ready."
                    )
                }
                appendEvent("Model loaded; ${selected.capability.label.lowercase()} client ready")
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(errorMessage = "Model load cancelled.") }
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "Model load failed: ${error.userMessage()}") }
                appendEvent("Model load failed: ${error.userMessage()}")
            } finally {
                finishModelOperation()
            }
        }
    }

    fun unloadModel() {
        if (!_uiState.value.isLoaded || _uiState.value.isBusy || modelOperationJob != null || catalogJob?.isActive == true) return
        _uiState.update { it.copy(isBusy = true, operationLabel = "Unloading model", progressPercent = 0) }
        modelOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                cancelCapabilityWorkAndJoin()
                resolveSelectedModel().unload()
                chatClient = null
                audioClient = null
                _uiState.update {
                    it.copy(
                        isLoaded = false,
                        isChatReady = false,
                        isAudioReady = false,
                        activeModelAlias = null,
                        progressPercent = 100,
                        successMessage = "Model unloaded."
                    )
                }
                appendEvent("Model unloaded")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "Model unload failed: ${error.userMessage()}") }
                appendEvent("Model unload failed: ${error.userMessage()}")
            } finally {
                finishModelOperation()
            }
        }
    }

    fun removeModelFromCache() {
        val state = _uiState.value
        if (!state.isConnected || !state.isDownloaded || state.isLoaded || state.isBusy || modelOperationJob != null || catalogJob?.isActive == true) return
        _uiState.update { it.copy(isBusy = true, operationLabel = "Removing cached model", progressPercent = 0) }
        modelOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                resolveSelectedModel().removeFromCache()
                model = null
                chatClient = null
                audioClient = null
                refreshSelectedModelState()
                _uiState.update { it.copy(successMessage = "Removed model from cache.") }
                appendEvent("Removed model from cache")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = "Cache removal failed: ${error.userMessage()}") }
                appendEvent("Cache removal failed: ${error.userMessage()}")
            } finally {
                finishModelOperation()
            }
        }
    }

    fun cancelModelOperation() {
        modelOperationJob?.cancel()
    }

    private fun finishModelOperation() {
        _uiState.update { it.copy(isBusy = false, operationLabel = null) }
        modelOperationJob = null
    }

    private suspend fun resolveSelectedModel(): Model {
        model?.let { return it }
        val selected = _uiState.value.selectedModel ?: throw IllegalStateException("Choose a model first")
        val currentCatalog = catalog ?: throw IllegalStateException("Catalog unavailable")
        return currentCatalog.getModel(selected.catalogId()).also { model = it }
    }

    private suspend fun unloadOtherModels(currentCatalog: Catalog, selected: LabModelChoice) {
        currentCatalog.getLoadedModels()
            .filterNot { FoundryModelIdentityResolver.isSameModel(selected.identity(), it.identity()) }
            .forEach { loaded ->
                appendEvent("Unloading active model ${loaded.alias}")
                currentCatalog.getModel(loaded.identity().catalogId()).unload()
            }
    }

    fun runPrompt(input: String) {
        val prompt = input.trim()
        if (prompt.isEmpty()) return
        val requestState = _uiState.value
        if (requestState.isBusy || requestState.isCapabilityRunning) return
        if (requestState.chatParameters.errors.isNotEmpty()) {
            _uiState.update { it.copy(chatError = "Check the highlighted parameters before running.") }
            return
        }
        if (!_uiState.value.isChatReady || chatClient == null) {
            _uiState.update { it.copy(errorMessage = "Load a chat model first.") }
            return
        }
        if (generationJob?.isActive == true) return

        val existing = _uiState.value.messages.takeLast(MAX_CONVERSATION_MESSAGES - 2)
        val assistantIndex = existing.size + 1
        val startedAt = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                messages = existing + LabMessage(LabMessageRole.USER, prompt) + LabMessage(LabMessageRole.ASSISTANT, ""),
                isGenerating = true,
                chatInput = "",
                chatError = null,
                lastRawResponse = "",
                errorMessage = null
            )
        }
        appendEvent("Streaming chat request started")

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val accumulated = StringBuilder()
            try {
                val history = existing.filter { it.includeInHistory }.map { message ->
                    when (message.role) {
                        LabMessageRole.USER -> ChatMessage.user(message.content)
                        LabMessageRole.ASSISTANT -> ChatMessage.assistant(message.content)
                    }
                }
                val request = requestState.chatParameters.request(
                    messages = listOf(ChatMessage.system(requestState.systemPrompt)) + history + ChatMessage.user(prompt),
                    temperature = requestState.temperature,
                    maxTokens = requestState.maxTokens
                )
                chatClient!!.completeChatStreaming(request).collect { chunk ->
                    accumulated.append(chunk.delta)
                    _uiState.update { current ->
                        val messages = current.messages.toMutableList()
                        if (assistantIndex in messages.indices) {
                            messages[assistantIndex] = LabMessage(LabMessageRole.ASSISTANT, accumulated.toString())
                        }
                        current.copy(messages = messages, lastRawResponse = accumulated.toString())
                    }
                }
                val duration = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { current ->
                    val messages = current.messages.toMutableList()
                    if (assistantIndex in messages.indices) {
                        if (accumulated.isBlank()) {
                            messages[assistantIndex - 1] = messages[assistantIndex - 1].copy(includeInHistory = false)
                        }
                        messages[assistantIndex] = LabMessage(
                            LabMessageRole.ASSISTANT,
                            accumulated.toString().ifBlank { "(empty response)" },
                            duration,
                            includeInHistory = accumulated.isNotBlank()
                        )
                    }
                    current.copy(
                        messages = messages,
                        lastRawResponse = accumulated.toString(),
                        lastInferenceDurationMs = duration,
                        isGenerating = false
                    )
                }
                appendEvent("Streaming chat completed in ${duration} ms")
            } catch (cancelled: CancellationException) {
                val duration = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { current ->
                    val messages = current.messages.toMutableList()
                    if (assistantIndex in messages.indices) {
                        messages[assistantIndex - 1] = messages[assistantIndex - 1].copy(includeInHistory = false)
                        messages[assistantIndex] = LabMessage(
                            LabMessageRole.ASSISTANT,
                            accumulated.toString().ifBlank { "Generation cancelled." },
                            duration,
                            includeInHistory = false
                        )
                    }
                    current.copy(messages = messages, isGenerating = false, lastInferenceDurationMs = duration)
                }
                appendEvent("Streaming chat cancelled after ${duration} ms")
                throw cancelled
            } catch (error: Exception) {
                val duration = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { current ->
                    val messages = current.messages.toMutableList()
                    if (assistantIndex in messages.indices) {
                        messages[assistantIndex - 1] = messages[assistantIndex - 1].copy(includeInHistory = false)
                        messages[assistantIndex] = LabMessage(
                            LabMessageRole.ASSISTANT,
                            accumulated.toString().ifBlank { "Error: ${error.userMessage()}" },
                            duration,
                            includeInHistory = false
                        )
                    }
                    current.copy(
                        messages = messages,
                        isGenerating = false,
                        chatError = error.userMessage(),
                        lastInferenceDurationMs = duration,
                        errorMessage = "Generation failed: ${error.userMessage()}"
                    )
                }
                appendEvent("Streaming chat failed: ${error.userMessage()}")
            } finally {
                generationJob = null
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    fun updateSystemPrompt(value: String) {
        if (_uiState.value.isCapabilityRunning) return
        _uiState.update { it.copy(systemPrompt = value) }
    }

    fun updateTemperature(value: Float) {
        if (_uiState.value.isCapabilityRunning) return
        _uiState.update { it.copy(temperature = value.coerceIn(0f, 1f)) }
    }

    fun updateMaxTokens(value: Int) {
        if (_uiState.value.isCapabilityRunning) return
        val limit = _uiState.value.selectedModel.configurableMaxTokens()
        _uiState.update { it.copy(maxTokens = value.coerceIn(MIN_MAX_TOKENS, limit)) }
    }

    fun clearConversation() {
        cancelGeneration()
        _uiState.update { it.copy(messages = emptyList(), lastRawResponse = "", lastInferenceDurationMs = null, chatError = null) }
        appendEvent("Conversation cleared")
    }

    fun updateChatInput(value: String) {
        if (!_uiState.value.isGenerating) _uiState.update { it.copy(chatInput = value) }
    }

    fun updateChatParameters(value: LabChatParameters) {
        if (!_uiState.value.isCapabilityRunning) _uiState.update { it.copy(chatParameters = value) }
    }

    fun updateAudioParameters(value: LabAudioParameters) {
        if (!_uiState.value.isCapabilityRunning) _uiState.update { it.copy(audioParameters = value) }
    }

    fun resetPlaygroundParameters(mode: LabInputMode) {
        if (_uiState.value.isCapabilityRunning) return
        _uiState.update {
            if (mode == LabInputMode.TEXT) it.copy(
                systemPrompt = LabUiState().systemPrompt,
                temperature = 0.2f,
                maxTokens = 512.coerceAtMost(it.selectedModel.configurableMaxTokens()),
                chatParameters = LabChatParameters()
            ) else it.copy(audioParameters = LabAudioParameters())
        }
    }

    fun onAudioFilePicked(context: Context, uri: Uri, displayName: String) {
        if (_uiState.value.isBusy || _uiState.value.isCapabilityRunning) return
        filePreparationJob?.cancel()
        _uiState.update { it.copy(isAudioFilePreparing = true, audioError = null) }
        filePreparationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.takeIf(String::isNotBlank) ?: displayName
                val safeName = sanitizeDisplayName(fileName)
                val cacheFile = File(context.cacheDir, "lab_${System.currentTimeMillis()}_$safeName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: throw IllegalStateException("Cannot open selected file")
                coroutineContext.ensureActive()
                selectedAudioPath = cacheFile.absolutePath
                _uiState.update {
                    it.copy(
                        selectedAudioFileName = fileName,
                        audioDurationMs = null,
                        transcriptionText = "",
                        streamingPartialText = "",
                        audioError = null
                    )
                }
                appendEvent("Prepared audio file: $safeName (${cacheFile.length()} bytes)")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(audioError = "Failed to open audio file: ${error.userMessage()}") }
                appendEvent("Audio file preparation failed: ${error.userMessage()}")
            } finally {
                _uiState.update { it.copy(isAudioFilePreparing = false) }
                filePreparationJob = null
            }
        }
    }

    fun transcribeFile(streaming: Boolean) {
        val requestState = _uiState.value
        if (requestState.isBusy || requestState.isCapabilityRunning) return
        if (requestState.selectedModel?.supportsFileTranscription != true) {
            _uiState.update { it.copy(audioError = "Choose a Whisper model for audio files.") }
            return
        }
        if (requestState.audioParameters.errors.isNotEmpty()) {
            _uiState.update { it.copy(audioError = "Check the highlighted parameters before running.") }
            return
        }
        val path = selectedAudioPath
        if (path == null) {
            _uiState.update { it.copy(audioError = "Select an audio file first.") }
            return
        }
        val client = audioClient
        if (!_uiState.value.isAudioReady || client == null) {
            _uiState.update { it.copy(audioError = "Load an audio transcription model first.") }
            return
        }
        if (transcriptionJob?.isActive == true) return
        transcriptionJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isTranscribing = true, transcriptionText = "", streamingPartialText = "", audioError = null, audioDurationMs = null)
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val request = requestState.audioParameters.request(path)
                if (streaming) {
                    client.transcribeStreaming(request).collect { event ->
                        _uiState.update {
                            if (event.isFinal) {
                                it.copy(transcriptionText = event.text, streamingPartialText = "")
                            } else {
                                it.copy(streamingPartialText = event.text)
                            }
                        }
                    }
                } else {
                    val response = client.transcribe(request)
                    _uiState.update { it.copy(transcriptionText = response.text, streamingPartialText = "") }
                }
                val duration = SystemClock.elapsedRealtime() - startedAt
                _uiState.update { it.copy(isTranscribing = false, lastInferenceDurationMs = duration, audioDurationMs = duration) }
                appendEvent("${if (streaming) "Streaming" else "File"} transcription completed in ${duration} ms")
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isTranscribing = false, audioError = "Transcription cancelled.") }
                appendEvent("File transcription cancelled")
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isTranscribing = false, audioError = "Transcription failed: ${error.userMessage()}") }
                appendEvent("File transcription failed: ${error.userMessage()}")
            } finally {
                transcriptionJob = null
            }
        }
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
    }

    @Suppress("MissingPermission")
    fun startLiveTranscription(context: ComponentActivity) {
        if (_uiState.value.isBusy || _uiState.value.isCapabilityRunning) return
        if (_uiState.value.selectedModel?.supportsLiveTranscription != true) {
            _uiState.update { it.copy(audioError = "Choose a Nemotron streaming model for the microphone.") }
            return
        }
        if (_uiState.value.isListening || _uiState.value.isStreamStopping || streamState != null ||
            streamSetupJob?.isActive == true || streamCleanupJob?.isActive == true) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.update { it.copy(audioError = "Microphone permission is required.") }
            return
        }
        val client = audioClient
        if (!_uiState.value.isAudioReady || client == null) {
            _uiState.update { it.copy(audioError = "Load a live transcription model first.") }
            return
        }
        _uiState.update {
            it.copy(isListening = true, transcriptionText = "", streamingPartialText = "", audioError = null, audioDurationMs = null)
        }
        val startedAt = SystemClock.elapsedRealtime()
        streamSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16_000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minimum = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                if (minimum <= 0) throw IllegalStateException("Audio input is not supported")
                val bufferSize = maxOf(minimum, 4_096)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    throw IllegalStateException("Failed to initialize the microphone")
                }
                audioRecord = recorder
                val session = client.createStreamSession(
                    AudioStreamSettings(sampleRate = sampleRate, channels = 1, bitsPerSample = 16, language = "en")
                )
                val state = StreamState(session)
                streamState = state
                val channel = Channel<ByteArray>(64)
                audioChunks = channel
                recorder.startRecording()
                isCaptureActive.set(true)
                appendEvent("Live transcription started")

                captureJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isCaptureActive.get()) {
                            val bytesRead = recorder.read(buffer, 0, buffer.size)
                            if (bytesRead < 0 && isCaptureActive.get()) {
                                throw IllegalStateException("AudioRecord.read() failed: $bytesRead")
                            }
                            if (bytesRead > 0) channel.send(buffer.copyOf(bytesRead))
                        }
                        channel.close()
                    } catch (cancelled: CancellationException) {
                        channel.close()
                        throw cancelled
                    } catch (error: Exception) {
                        channel.close(error)
                    } finally {
                        isCaptureActive.set(false)
                    }
                }

                pushJob = viewModelScope.launch(Dispatchers.IO) {
                    var streamError: Exception? = null
                    try {
                        for (chunk in channel) {
                            val result = session.pushAudioChunk(chunk)
                            result.text.takeIf(String::isNotBlank)?.let { text ->
                                _uiState.update { it.copy(streamingPartialText = normalizeTranscript(text)) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        streamError = IllegalStateException("Audio processing was interrupted before all queued audio was sent.")
                        throw cancelled
                    } catch (error: Exception) {
                        streamError = error
                        Log.e(TAG, "Live audio stream failed", error)
                    } finally {
                        isCaptureActive.set(false)
                        releaseRecorder(recorder)
                        withContext(NonCancellable) {
                            val captureStopped = withTimeoutOrNull(3_000) { captureJob?.cancelAndJoin(); true } == true
                            if (captureStopped) {
                                finishStream(state, startedAt, streamError)
                            } else {
                                _uiState.update {
                                    it.copy(isListening = true, audioError = "Microphone capture is still stopping. Try Stop again.")
                                }
                                appendEvent("Microphone capture did not stop in time")
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                releaseRecorder()
                throw cancelled
            } catch (error: Exception) {
                releaseRecorder()
                audioChunks?.close()
                val state = streamState
                if (state != null) {
                    withContext(NonCancellable) { finishStream(state, startedAt, error) }
                } else {
                    _uiState.update { it.copy(isListening = false, audioError = error.userMessage()) }
                }
                appendEvent("Live transcription failed: ${error.userMessage()}")
            } finally {
                streamSetupJob = null
            }
        }
    }

    fun stopLiveTranscription() {
        if (!_uiState.value.isListening && streamState == null && streamSetupJob?.isActive != true) return
        if (_uiState.value.isStreamStopping || streamCleanupJob?.isActive == true) return
        _uiState.update { it.copy(isStreamStopping = true) }
        isCaptureActive.set(false)
        releaseRecorder()
        runCatching { audioChunks?.close() }
        val setupJob = streamSetupJob
        streamCleanupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val setupStopped = withTimeoutOrNull(3_000) { setupJob?.cancelAndJoin(); true } == true
                if (!setupStopped) throw IllegalStateException("Microphone setup is still stopping. Try again.")
                val capture = captureJob
                val captureStopped = withTimeoutOrNull(3_000) { capture?.cancelAndJoin(); true } == true
                if (!captureStopped) throw IllegalStateException("Microphone capture is still stopping. Try again.")
                val push = pushJob
                if (push != null) {
                    val alreadyStopped = push.isCompleted
                    // Give queued chunks a short opportunity to drain, then cancel a stuck IPC call.
                    if (withTimeoutOrNull(3_000) { push.join(); true } != true) {
                        push.cancel()
                        if (withTimeoutOrNull(3_000) { push.join(); true } != true) {
                            throw IllegalStateException("Audio processing is still stopping. Try again.")
                        }
                    }
                    pushJob = null
                    if (alreadyStopped) streamState?.let { finishStream(it, null, null) }
                } else {
                    streamState?.let { finishStream(it, null, null) }
                }
                if (streamState == null) _uiState.update { it.copy(isListening = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isListening = true, audioError = error.userMessage()) }
                appendEvent("Live transcription cleanup failed: ${error.userMessage()}")
            } finally {
                _uiState.update { it.copy(isStreamStopping = false) }
                streamCleanupJob = null
            }
        }
    }

    private suspend fun finishStream(state: StreamState, startedAt: Long?, streamError: Exception?) {
        val result = try {
            finalizeSession(state)
        } catch (error: Exception) {
            Log.e(TAG, "Live stream finalization failed", error)
            _uiState.update {
                it.copy(isListening = true, audioError = "Could not stop live transcription: ${error.userMessage()}. Try Stop again.")
            }
            appendEvent("Live stream finalization failed: ${error.userMessage()}")
            return
        }
        if (streamState === state) streamState = null
        captureJob = null
        audioChunks = null
        _uiState.update {
            it.copy(
                transcriptionText = result?.text?.takeIf(String::isNotBlank)?.let(::normalizeTranscript)
                    ?: it.streamingPartialText,
                streamingPartialText = "",
                isListening = false,
                audioDurationMs = startedAt?.let { start -> SystemClock.elapsedRealtime() - start } ?: it.audioDurationMs,
                audioError = streamError?.userMessage()
            )
        }
        if (streamError != null) appendEvent("Live transcription failed: ${streamError.userMessage()}")
        else appendEvent("Live transcription stopped")
    }

    fun clearAudioResult() {
        if (_uiState.value.isListening) return
        cancelTranscription()
        _uiState.update { it.copy(transcriptionText = "", streamingPartialText = "", audioError = null, audioDurationMs = null) }
    }

    private suspend fun cancelCapabilityWorkAndJoin() {
        val preparationJob = filePreparationJob
        preparationJob?.cancelAndJoin()
        val chatJob = generationJob
        generationJob = null
        chatJob?.cancelAndJoin()
        val fileJob = transcriptionJob
        transcriptionJob = null
        fileJob?.cancelAndJoin()
        if (_uiState.value.isListening || streamState != null || streamSetupJob?.isActive == true || streamCleanupJob?.isActive == true) {
            stopLiveTranscription()
            streamCleanupJob?.join()
            if (streamState != null || streamSetupJob?.isActive == true || pushJob?.isActive == true) {
                throw IllegalStateException("Stop the live transcription session before continuing.")
            }
        }
    }

    fun clearRuntimeEvents() {
        _uiState.update { it.copy(runtimeEvents = emptyList()) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    private fun handleDisconnected(message: String) {
        generationJob?.cancel()
        filePreparationJob?.cancel()
        transcriptionJob?.cancel()
        catalogJob?.cancel()
        modelOperationJob?.cancel()
        stopLiveTranscription()
        model = null
        catalog = null
        chatClient = null
        audioClient = null
        _uiState.update {
            it.copy(
                isConnected = false,
                isDownloaded = false,
                isLoaded = false,
                isChatReady = false,
                isAudioReady = false,
                isBusy = false,
                operationLabel = null,
                activeModelAlias = null,
                isGenerating = false,
                isTranscribing = false,
                errorMessage = message
            )
        }
        appendEvent(message)
    }

    private fun LabModelChoice?.configurableMaxTokens(): Int {
        val reported = this?.maxOutputTokens ?: 0
        return if (reported >= MIN_MAX_TOKENS) reported else FALLBACK_MAX_TOKENS
    }

    private fun toChoice(info: ModelInfo): LabModelChoice = LabModelChoice(
        alias = info.alias,
        displayName = info.displayName.ifBlank { info.name.ifBlank { info.alias } },
        task = info.task.orEmpty(),
        fileSizeMb = info.fileSizeMb,
        executionProvider = info.executionProvider.orEmpty(),
        deviceType = info.deviceType.orEmpty(),
        supportsToolCalling = info.supportsToolCalling,
        maxOutputTokens = info.maxOutputTokens,
        name = info.name,
        version = info.version,
        uri = info.uri.orEmpty()
    )

    private fun LabModelChoice.identity(): FoundryModelIdentity = FoundryModelIdentity(
        alias = alias,
        name = name,
        displayName = displayName,
        version = version,
        uri = uri
    )

    private fun ModelInfo.identity(): FoundryModelIdentity = FoundryModelIdentity(
        alias = alias,
        name = name,
        displayName = displayName,
        version = version,
        uri = uri.orEmpty()
    )

    private fun LabModelChoice.catalogId(): String = identity().catalogId()

    private fun FoundryModelIdentity.catalogId(): String =
        FoundryModelIdentityResolver.catalogId(this)

    private fun mainActivityPendingIntent(context: ComponentActivity): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun sanitizeDisplayName(displayName: String): String = displayName
        .substringAfterLast('/')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .ifBlank { "audio_input" }

    private fun normalizeTranscript(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun releaseRecorder(recorder: AudioRecord? = audioRecord) {
        isCaptureActive.set(false)
        if (audioRecord === recorder) audioRecord = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private suspend fun finalizeSession(state: StreamState): AudioStreamResult? {
        if (!state.finalized.compareAndSet(false, true)) return null
        return try {
            state.session.stop()
        } catch (error: Exception) {
            state.finalized.set(false)
            throw error
        }
    }

    private fun appendEvent(message: String) {
        _uiState.update { it.copy(runtimeEvents = (it.runtimeEvents + message).takeLast(MAX_RUNTIME_EVENTS)) }
    }

    private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: this::class.java.simpleName

    override fun onCleared() {
        super.onCleared()
        isCaptureActive.set(false)
        releaseRecorder()
        runCatching { audioChunks?.close() }
        val jobs = listOfNotNull(
            connectionJob,
            catalogJob,
            modelOperationJob,
            generationJob,
            filePreparationJob,
            transcriptionJob,
            streamSetupJob,
            captureJob,
            pushJob,
            streamCleanupJob
        )
        cleanupScope.launch {
            jobs.forEach { job -> runCatching { job.cancelAndJoin() } }
            streamState?.let { state ->
                runCatching { finalizeSession(state) }
                    .onFailure { Log.e(TAG, "Failed to stop live stream", it) }
            }
            streamState = null
            manager?.close()
            manager = null
            cleanupScope.cancel()
        }
    }
}
