/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.chatapp

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.foundrylocal.api.AudioClient
import com.microsoft.foundrylocal.api.AudioStreamResult
import com.microsoft.foundrylocal.api.AudioStreamSession
import com.microsoft.foundrylocal.api.AudioStreamSettings
import com.microsoft.foundrylocal.api.ChatClient
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
import com.microsoft.foundrylocal.api.ChatMessage
import com.microsoft.foundrylocal.api.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for the ChatApp - handles chat messaging and voice input via Foundry Local SDK.
 */
class ChatViewModel : ViewModel() {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MODEL_ALIAS = "qwen2.5-coder-0.5b-instruct-generic-cpu:4"
        private const val STREAMING_AUDIO_MODEL_ALIAS = "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    private data class StreamState(
        val session: AudioStreamSession,
        val finalized: AtomicBoolean = AtomicBoolean(false)
    )

    val modelAlias: String = MODEL_ALIAS

    private var foundryManager: FoundryLocalManager? = null
    private var chatCompletionClient: ChatClient? = null
    private var model: Model? = null

    private var voiceModel: Model? = null
    private var audioClient: AudioClient? = null
    private var streamState: StreamState? = null
    private var audioRecord: AudioRecord? = null
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var connectionJob: Job? = null
    private var voiceSetupJob: Job? = null
    private var listenJob: Job? = null
    private var captureJob: Job? = null
    private var streamingJob: Job? = null
    private var streamCleanupJob: Job? = null
    private var chatStreamingJob: Job? = null
    private var activeAssistantIndex: Int? = null
    private val isStreamCaptureActive = AtomicBoolean(false)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isConnected by mutableStateOf(false)
        private set

    var isDownloaded by mutableStateOf(false)
        private set

    var isLoaded by mutableStateOf(false)
        private set

    val isFullyReady: Boolean
        get() = isLoaded && isVoiceInputReady

    var inputText by mutableStateOf("")

    var chatMessages = mutableStateListOf<ChatMessageUI>()
        private set

    var progressPercent by mutableStateOf(0f)
        private set

    var showProgress by mutableStateOf(false)
        private set

    var allowAutoLoad by mutableStateOf(true)
        private set

    var setupError by mutableStateOf<String?>(null)
        private set

    var isVoiceModelDownloaded by mutableStateOf(false)
        private set

    var isVoiceModelLoaded by mutableStateOf(false)
        private set

    var isVoiceInputReady by mutableStateOf(false)
        private set

    var isListening by mutableStateOf(false)
        private set

    var voiceTranscript by mutableStateOf("")
        private set

    var voiceProgressPercent by mutableStateOf(0f)
        private set

    var showVoiceProgress by mutableStateOf(false)
        private set

    var voiceError by mutableStateOf<String?>(null)
        private set

    private var voiceSetupAttempted = false

    fun startVoiceSetupIfNeeded(context: ComponentActivity) {
        if (!isLoaded || voiceSetupAttempted || isVoiceInputReady) return
        voiceSetupAttempted = true

        voiceSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                checkVoiceModelStatus()
                if (isVoiceInputReady) return@launch

                if (!isVoiceModelDownloaded) {
                    downloadVoiceModel(context)
                } else if (!isVoiceModelLoaded) {
                    withContext(Dispatchers.Main) { loadVoiceModel() }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Voice setup failed", e)
                withContext(Dispatchers.Main) {
                    voiceError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun retryVoiceSetup(context: ComponentActivity) {
        voiceSetupAttempted = false
        voiceError = null
        startVoiceSetupIfNeeded(context)
    }

    fun connect(context: ComponentActivity) {
        if (isConnected || connectionJob?.isActive == true) return

        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { setupError = null }

                val manager = foundryManager?.also {
                    it.reconnect()
                } ?: FoundryLocalManager.create(
                        context = context,
                        config = Configuration(
                            appName = "ChatApp",
                            logLevel = "Debug"
                        ),
                        onDisconnected = {
                            releaseRecorder()
                            viewModelScope.launch(Dispatchers.Main) {
                                handleDisconnected("Service disconnected")
                            }
                        }
                    )
                foundryManager = manager

                withContext(Dispatchers.Main) {
                    isConnected = manager.isConnected
                }
                checkModelStatus()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    setupError = e.message ?: e.javaClass.simpleName
                }
            } finally {
                connectionJob = null
            }
        }
    }

    fun downloadModel(context: ComponentActivity) {
        if (!isConnected) return

        if (isDownloaded) {
            if (!isVoiceModelDownloaded && !showVoiceProgress) {
                Log.d(TAG, "Chat model cached, starting voice download directly")
                downloadVoiceModel(context)
            }
            return
        }

        if (showProgress) return

        viewModelScope.launch(Dispatchers.Main) {
            setupError = null
            showProgress = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadModel = foundryManager
                    ?.getCatalog()
                    ?.getModel(MODEL_ALIAS)
                    ?: throw IllegalStateException("Model $MODEL_ALIAS not found in catalog")
                model = downloadModel

                Log.d(TAG, "Starting download for model: $MODEL_ALIAS")
                downloadModel.download(
                    progress = { p ->
                        viewModelScope.launch(Dispatchers.Main) { progressPercent = p }
                    },
                    contentIntent = mainActivityPendingIntent(context)
                )

                withContext(Dispatchers.Main) {
                    showProgress = false
                    isDownloaded = true
                    setupError = null
                    Log.d(TAG, "Chaining voice model download")
                    downloadVoiceModel(context)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Download error", e)
                withContext(Dispatchers.Main) {
                    showProgress = false
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun loadModel() {
        if (!isConnected || !isDownloaded || model == null) return

        viewModelScope.launch(Dispatchers.Main) {
            setupError = null
            showProgress = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentModel = model ?: throw IllegalStateException("Model not ready")
                currentModel.load()
                val client = currentModel.createChatClient()
                withContext(Dispatchers.Main) {
                    progressPercent = 100f
                    showProgress = false
                    isLoaded = true
                    setupError = null
                    chatCompletionClient = client
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Load error", e)
                withContext(Dispatchers.Main) {
                    showProgress = false
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun returnToSetup() {
        if (!isConnected || model == null) return

        val generationJob = chatStreamingJob
        chatStreamingJob = null
        isLoaded = false
        allowAutoLoad = false
        chatCompletionClient = null
        showProgress = true
        stopVoiceInput()

        viewModelScope.launch(Dispatchers.IO) {
            generationJob?.cancelAndJoin()
            var unloadError: Exception? = null
            try {
                model?.unload()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Unload error", e)
                unloadError = e
            }
            withContext(Dispatchers.Main) {
                showProgress = false
                setupError = unloadError?.message
                    ?: unloadError?.javaClass?.simpleName
                chatMessages.clear()
            }
        }
    }

    fun sendMessage() {
        if (!isLoaded || inputText.isBlank() || chatStreamingJob?.isActive == true) return

        val userMessage = inputText.trim()
        inputText = ""

        chatMessages.add(ChatMessageUI(userMessage, isUser = true))
        val assistantIndex = chatMessages.size
        chatMessages.add(ChatMessageUI("", isUser = false))
        activeAssistantIndex = assistantIndex

        chatStreamingJob?.cancel()
        chatStreamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = ChatCompletionRequest(
                    messages = chatMessages.dropLast(1)
                        .filter { it.includeInHistory }
                        .map { msg ->
                            if (msg.isUser) {
                                ChatMessage(ChatMessage.ROLE_USER, msg.text)
                            } else {
                                ChatMessage(ChatMessage.ROLE_ASSISTANT, msg.text)
                            }
                        },
                    temperature = 0.7f,
                    maxTokens = 1000
                )

                val accumulated = StringBuilder()
                requireChatClient().completeChatStreaming(request)
                    .catch { e ->
                        Log.e(TAG, "Streaming error", e)
                        withContext(Dispatchers.Main) {
                            if (assistantIndex < chatMessages.size) {
                                chatMessages[assistantIndex] =
                                    ChatMessageUI(
                                        text = "Error: ${e.message ?: e.javaClass.simpleName}",
                                        isUser = false,
                                        includeInHistory = false
                                    )
                            }
                        }
                    }
                    .collect { chunk ->
                        accumulated.append(chunk.delta)
                        withContext(Dispatchers.Main) {
                            if (assistantIndex < chatMessages.size) {
                                chatMessages[assistantIndex] =
                                    ChatMessageUI(accumulated.toString(), isUser = false)
                            }
                        }
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Send message error", e)
                withContext(Dispatchers.Main) {
                    if (assistantIndex < chatMessages.size) {
                        chatMessages[assistantIndex] =
                            ChatMessageUI(
                                text = "Error: ${e.message ?: e.javaClass.simpleName}",
                                isUser = false,
                                includeInHistory = false
                            )
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (activeAssistantIndex == assistantIndex) {
                            activeAssistantIndex = null
                    }
                }
            }
        }
    }

    private suspend fun requireChatClient(): ChatClient {
        chatCompletionClient?.let { return it }
        val currentModel = model ?: throw IllegalStateException("Load the model first")
        return currentModel.createChatClient().also { client ->
            withContext(Dispatchers.Main) { chatCompletionClient = client }
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = foundryManager?.getCatalog() ?: return@launch
                val currentModel = catalog.getModel(MODEL_ALIAS)
                model = currentModel

                val cached = currentModel.isCached()
                val loaded = currentModel.isLoaded()
                val client = if (loaded) currentModel.createChatClient() else null

                withContext(Dispatchers.Main) {
                    isDownloaded = cached
                    isLoaded = loaded
                    chatCompletionClient = client
                }

                checkVoiceModelStatus()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error checking model status", e)
            }
        }
    }

    private suspend fun checkVoiceModelStatus() {
        try {
            val catalog = foundryManager?.getCatalog() ?: run {
                withContext(Dispatchers.Main) { resetVoiceState() }
                return
            }
            val vm = catalog.getModel(STREAMING_AUDIO_MODEL_ALIAS)
            voiceModel = vm

            val cached = vm.isCached()
            val loaded = vm.isLoaded()
            val client = if (loaded) vm.createAudioClient() else null

            withContext(Dispatchers.Main) {
                isVoiceModelDownloaded = cached
                isVoiceModelLoaded = loaded
                audioClient = client
                isVoiceInputReady = client != null
                if (client != null) voiceError = null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error checking voice model status", e)
            withContext(Dispatchers.Main) {
                resetVoiceState()
                voiceError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun downloadVoiceModel(context: ComponentActivity) {
        if (!isConnected || isVoiceModelDownloaded || showVoiceProgress || showProgress) {
            Log.d(TAG, "downloadVoiceModel skipped: connected=$isConnected, downloaded=$isVoiceModelDownloaded, inProgress=$showVoiceProgress, chatInProgress=$showProgress")
            return
        }

        viewModelScope.launch(Dispatchers.Main) {
            showVoiceProgress = true
            voiceError = null
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val vm = foundryManager
                    ?.getCatalog()
                    ?.getModel(STREAMING_AUDIO_MODEL_ALIAS)
                    ?: throw IllegalStateException("Voice model not found in catalog")

                voiceModel = vm

                Log.d(TAG, "Starting download for voice model: $STREAMING_AUDIO_MODEL_ALIAS")
                vm.download(
                    progress = { p ->
                        viewModelScope.launch(Dispatchers.Main) { voiceProgressPercent = p }
                    },
                    contentIntent = mainActivityPendingIntent(context)
                )

                withContext(Dispatchers.Main) {
                    showVoiceProgress = false
                    isVoiceModelDownloaded = true
                    loadVoiceModel()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Voice model download error", e)
                withContext(Dispatchers.Main) {
                    showVoiceProgress = false
                    voiceError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun loadVoiceModel() {
        if (!isConnected || !isVoiceModelDownloaded || isVoiceModelLoaded || voiceModel == null || showVoiceProgress) return

        viewModelScope.launch {
            showVoiceProgress = true
            voiceError = null

            withContext(Dispatchers.IO) {
                try {
                    val vm = voiceModel ?: throw IllegalStateException("Voice model unavailable")
                    vm.load()
                    val client = vm.createAudioClient()
                    withContext(Dispatchers.Main) {
                        showVoiceProgress = false
                        isVoiceModelLoaded = true
                        isVoiceInputReady = true
                        audioClient = client
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Voice model load error", e)
                    withContext(Dispatchers.Main) {
                        showVoiceProgress = false
                        voiceError = e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun startVoiceInput(context: ComponentActivity) {
        if (isListening) return

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceError = "Microphone permission required"
            return
        }

        voiceError = null
        voiceTranscript = ""
        isListening = true

        listenJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = audioClient
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        voiceError = "Voice model not ready"
                        isListening = false
                    }
                    return@launch
                }

                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                if (minBufferSize <= 0) {
                    withContext(Dispatchers.Main) {
                        voiceError = "Audio input not supported"
                        isListening = false
                    }
                    return@launch
                }
                val bufferSize = maxOf(minBufferSize, 4096)

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )
                audioRecord = recorder

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        voiceError = "Failed to initialize microphone"
                        isListening = false
                    }
                    recorder.release()
                    audioRecord = null
                    return@launch
                }

                val settings = AudioStreamSettings(
                    sampleRate = sampleRate,
                    channels = 1,
                    bitsPerSample = 16,
                    language = "en"
                )
                val session = client.createStreamSession(settings)
                val state = StreamState(session)
                streamState = state

                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                recorder.startRecording()
                isStreamCaptureActive.set(true)

                captureJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isStreamCaptureActive.get()) {
                            val bytesRead = recorder.read(buffer, 0, bufferSize)
                            if (bytesRead < 0) {
                                throw IllegalStateException(
                                    "AudioRecord.read() failed: $bytesRead"
                                )
                            }
                            if (bytesRead == 0) continue
                            channel.send(buffer.copyOf(bytesRead))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isStreamCaptureActive.get()) {
                            Log.e(TAG, "Capture error", e)
                            channel.close(e)
                        }
                    } finally {
                        channel.close()
                    }
                }

                streamingJob = viewModelScope.launch(Dispatchers.IO) {
                    var streamError: Exception? = null
                    try {
                        for (chunk in channel) {
                            val result = session.pushAudioChunk(chunk)
                            val partialText = result.text
                            if (partialText.isNotEmpty()) {
                                val current = normalizeTranscript(partialText)
                                withContext(Dispatchers.Main) {
                                    voiceTranscript = current
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        streamError = e
                        Log.e(TAG, "Push loop error", e)
                    } finally {
                        captureJob?.cancel()
                        channel.cancel()
                        releaseRecorder(recorder)
                        var finalized = false
                        val finalResult = try {
                            withContext(NonCancellable) {
                                finalizeSession(state).also {
                                    finalized = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping stream", e)
                            null
                        }
                        withContext(NonCancellable + Dispatchers.Main) {
                            finalResult?.text?.takeIf { it.isNotEmpty() }?.let {
                                voiceTranscript = normalizeTranscript(it)
                            }
                            if (finalized) {
                                if (voiceTranscript.isNotBlank()) {
                                    inputText = voiceTranscript
                                }
                                isListening = false
                                if (streamState === state) streamState = null
                                if (streamError != null) {
                                    voiceError = streamError.message
                                        ?: streamError.javaClass.simpleName
                                }
                            } else if (streamState === state) {
                                voiceError = "Failed to stop audio stream. Tap Stop to retry."
                                isListening = true
                            }
                            captureJob = null
                            audioChunkChannel = null
                            streamingJob = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                releaseRecorder()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceInput error", e)
                releaseRecorder()
                val state = streamState
                var finalized = state == null
                if (state != null) {
                    finalized = runCatching {
                        withContext(NonCancellable) { finalizeSession(state) }
                    }.onFailure {
                        Log.e(TAG, "Failed to clean up audio stream", it)
                    }.isSuccess
                    if (finalized && streamState === state) streamState = null
                }
                withContext(NonCancellable + Dispatchers.Main) {
                    if (finalized) {
                        voiceError = e.message ?: e.javaClass.simpleName
                        isListening = false
                    } else if (state != null && streamState === state) {
                        voiceError = "Failed to stop audio stream. Tap Stop to retry."
                        isListening = true
                    }
                }
            }
        }
    }

    fun stopVoiceInput() {
        val state = streamState
        if (!isListening && state == null) return

        isStreamCaptureActive.set(false)
        val setupJob = listenJob
        listenJob = null
        releaseRecorder()

        if (streamCleanupJob?.isActive != true) {
            streamCleanupJob = viewModelScope.launch(Dispatchers.IO) {
                runCatching { setupJob?.cancelAndJoin() }
                val currentState = streamState
                val finalized = if (currentState != null &&
                    streamingJob?.isActive != true
                ) {
                    runCatching {
                        withContext(NonCancellable) { finalizeSession(currentState) }
                    }.onFailure {
                        Log.e(TAG, "Failed to stop audio stream", it)
                    }.isSuccess
                } else {
                    null
                }

                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentState != null &&
                        streamState === currentState &&
                        finalized != null
                    ) {
                        if (finalized == true) {
                            streamState = null
                            if (voiceTranscript.isNotBlank()) {
                                inputText = voiceTranscript
                            }
                            isListening = false
                        } else {
                            isListening = true
                            voiceError = "Failed to stop audio stream. Tap Stop to retry."
                        }
                    } else if (currentState == null) {
                        isListening = false
                    }
                    streamCleanupJob = null
                }
            }
        }
    }

    private fun handleDisconnected(message: String) {
        chatStreamingJob?.cancel()
        chatStreamingJob = null
        activeAssistantIndex?.let { index ->
            if (index < chatMessages.size) {
                chatMessages[index] = chatMessages[index].copy(includeInHistory = false)
            }
        }
        activeAssistantIndex = null
        isStreamCaptureActive.set(false)
        releaseRecorder()
        runCatching { audioChunkChannel?.close() }
        streamState = null
        streamingJob?.cancel()
        streamingJob = null
        captureJob?.cancel()
        captureJob = null
        listenJob?.cancel()
        listenJob = null
        streamCleanupJob?.cancel()
        streamCleanupJob = null
        isListening = false
        isConnected = false
        isLoaded = false
        isDownloaded = false
        chatCompletionClient = null
        model = null
        showProgress = false
        progressPercent = 0f
        resetVoiceState()
        showVoiceProgress = false
        voiceProgressPercent = 0f
        voiceSetupAttempted = false
        setupError = message
    }

    private fun resetVoiceState() {
        isVoiceModelDownloaded = false
        isVoiceModelLoaded = false
        isVoiceInputReady = false
        voiceModel = null
        audioClient = null
    }

    private fun mainActivityPendingIntent(context: ComponentActivity): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun normalizeTranscript(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun releaseRecorder(recorder: AudioRecord? = audioRecord) {
        isStreamCaptureActive.set(false)
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
        } catch (e: Exception) {
            state.finalized.set(false)
            throw e
        }
    }

    override fun onCleared() {
        super.onCleared()
        isStreamCaptureActive.set(false)
        releaseRecorder()
        runCatching { audioChunkChannel?.close() }
        val connectJob = connectionJob
        val setupJob = voiceSetupJob
        val listenSetupJob = listenJob
        val producerJob = captureJob
        val pushJob = streamingJob
        val retryJob = streamCleanupJob
        val generationJob = chatStreamingJob
        cleanupScope.launch {
            runCatching { connectJob?.cancelAndJoin() }
            runCatching { setupJob?.cancelAndJoin() }
            runCatching { listenSetupJob?.cancelAndJoin() }
            runCatching { producerJob?.cancelAndJoin() }
            runCatching { pushJob?.cancelAndJoin() }
            runCatching { retryJob?.cancelAndJoin() }
            runCatching { generationJob?.cancelAndJoin() }

            val state = streamState
            streamState = null
            if (state != null) {
                runCatching { finalizeSession(state) }
                    .onFailure { Log.e(TAG, "Failed to stop audio stream", it) }
            }
            val manager = foundryManager
            foundryManager = null
            manager?.close()
            cleanupScope.cancel()
        }
    }
}

data class ChatMessageUI(
    val text: String,
    val isUser: Boolean,
    val includeInHistory: Boolean = true
)
