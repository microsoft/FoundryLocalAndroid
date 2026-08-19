/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.embeddedchat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.foundrylocal.api.AudioClient
import com.microsoft.foundrylocal.api.AudioStreamSession
import com.microsoft.foundrylocal.api.AudioStreamSettings
import com.microsoft.foundrylocal.api.ChatClient
import com.microsoft.foundrylocal.api.ChatCompletionRequest
import com.microsoft.foundrylocal.api.ChatMessage
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
import com.microsoft.foundrylocal.api.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for the Embedded ChatApp — handles chat messaging and voice input
 * via the Foundry Local embedded (in-process) SDK.
 *
 * No service binding, no AIDL callbacks — the runtime runs directly in-process.
 */
class EmbeddedChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "EmbeddedChatVM"
        private const val MODEL_ALIAS = "qwen2.5-coder-0.5b-instruct-generic-cpu:4"
        private const val VOICE_MODEL_ALIAS = "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    val modelAlias: String = MODEL_ALIAS

    // SDK state
    private var manager: FoundryLocalManager? = null
    private var model: Model? = null
    private var chatClient: ChatClient? = null

    // Voice input state (SDK)
    private var voiceModel: Model? = null
    private var audioClient: AudioClient? = null
    private var streamSession: AudioStreamSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var voiceSetupJob: Job? = null
    private var listenJob: Job? = null
    private var captureJob: Job? = null
    private var streamingJob: Job? = null
    private var chatStreamingJob: Job? = null
    private val isStreamCaptureActive = AtomicBoolean(false)

    // UI State
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

    // Voice input UI state
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

    // --- Setup Flow ---

    fun connect(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { setupError = null }
                val mgr = FoundryLocalManager.create(
                    context,
                    Configuration(appName = "EmbeddedChatApp")
                )
                manager = mgr
                withContext(Dispatchers.Main) {
                    isConnected = true
                    checkModelStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun downloadModel(context: android.content.Context) {
        if (!isConnected) return

        if (isDownloaded) {
            if (!isVoiceModelDownloaded && !showVoiceProgress) {
                downloadVoiceModel()
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
                val catalog = manager?.getCatalog()
                if (catalog == null) {
                    withContext(Dispatchers.Main) {
                        showProgress = false
                        setupError = "Failed to load model catalog"
                    }
                    return@launch
                }

                val m = catalog.getModel(MODEL_ALIAS)
                model = m

                m.download(progress = { p ->
                    viewModelScope.launch(Dispatchers.Main) {
                        progressPercent = p
                    }
                })

                withContext(Dispatchers.Main) {
                    showProgress = false
                    isDownloaded = true
                    setupError = null
                    downloadVoiceModel()
                }
                Log.i(TAG, "Chat model download complete: $MODEL_ALIAS")
            } catch (e: Exception) {
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
                model!!.load()
                val client = model!!.createChatClient()
                withContext(Dispatchers.Main) {
                    chatClient = client
                    showProgress = false
                    isLoaded = true
                    setupError = null
                }
                Log.i(TAG, "Model loaded: $MODEL_ALIAS")
            } catch (e: Exception) {
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

        chatStreamingJob?.cancel()
        chatStreamingJob = null
        stopVoiceInput()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                model?.unload()
            } catch (e: Exception) {
                Log.e(TAG, "Unload error", e)
            }
            withContext(Dispatchers.Main) {
                isLoaded = false
                allowAutoLoad = false
                chatClient = null
                chatMessages.clear()
            }
        }
    }

    fun sendMessage() {
        if (!isLoaded || inputText.isBlank()) return

        val userMessage = inputText.trim()
        inputText = ""

        chatMessages.add(ChatMessageUI(userMessage, isUser = true))

        val assistantIndex = chatMessages.size
        chatMessages.add(ChatMessageUI("", isUser = false))

        chatStreamingJob?.cancel()
        chatStreamingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = ChatCompletionRequest(
                    messages = chatMessages.dropLast(1).map { msg ->
                        ChatMessage(
                            role = if (msg.isUser) "user" else "assistant",
                            content = msg.text
                        )
                    }
                )

                val accumulated = StringBuilder()
                chatClient!!.completeChatStreaming(request)
                    .catch { e ->
                        Log.e(TAG, "Streaming error", e)
                        withContext(Dispatchers.Main) {
                            if (assistantIndex < chatMessages.size) {
                                chatMessages[assistantIndex] =
                                    ChatMessageUI("Error: ${e.message}", isUser = false)
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
                Log.e(TAG, "Send message error", e)
                withContext(Dispatchers.Main) {
                    chatMessages[assistantIndex] =
                        ChatMessageUI("Error: ${e.message ?: e.javaClass.simpleName}", isUser = false)
                }
            }
        }
    }

    // --- Voice Input ---

    fun startVoiceSetupIfNeeded(context: android.content.Context) {
        if (!isLoaded || voiceSetupAttempted || isVoiceInputReady) return
        voiceSetupAttempted = true

        voiceSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                checkVoiceModelStatus()
                if (isVoiceInputReady) return@launch

                if (!isVoiceModelDownloaded) {
                    withContext(Dispatchers.Main) { downloadVoiceModel() }
                } else if (!isVoiceModelLoaded) {
                    withContext(Dispatchers.Main) { loadVoiceModel() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice setup failed", e)
                withContext(Dispatchers.Main) {
                    voiceError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun retryVoiceSetup(context: android.content.Context) {
        voiceSetupAttempted = false
        voiceError = null
        startVoiceSetupIfNeeded(context)
    }

    private fun downloadVoiceModel() {
        if (!isConnected || isVoiceModelDownloaded || showVoiceProgress || showProgress) return

        viewModelScope.launch(Dispatchers.Main) {
            showVoiceProgress = true
            voiceError = null
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = manager?.getCatalog()
                val vm = catalog?.getModel(VOICE_MODEL_ALIAS)
                if (vm == null) {
                    withContext(Dispatchers.Main) {
                        showVoiceProgress = false
                        voiceError = "Voice model not found in catalog"
                    }
                    return@launch
                }
                voiceModel = vm

                vm.download(progress = { p ->
                    viewModelScope.launch(Dispatchers.Main) {
                        voiceProgressPercent = p
                    }
                })

                withContext(Dispatchers.Main) {
                    showVoiceProgress = false
                    isVoiceModelDownloaded = true
                    loadVoiceModel()
                }
                Log.i(TAG, "Voice model download complete: $VOICE_MODEL_ALIAS")
            } catch (e: Exception) {
                Log.e(TAG, "Voice model download error", e)
                withContext(Dispatchers.Main) {
                    showVoiceProgress = false
                    voiceError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun loadVoiceModel() {
        if (!isConnected || !isVoiceModelDownloaded || isVoiceModelLoaded ||
            voiceModel == null || showVoiceProgress) return

        viewModelScope.launch {
            showVoiceProgress = true
            voiceError = null

            withContext(Dispatchers.IO) {
                try {
                    voiceModel!!.load()
                    val client = voiceModel!!.createAudioClient()
                    withContext(Dispatchers.Main) {
                        audioClient = client
                        showVoiceProgress = false
                        isVoiceModelLoaded = true
                        isVoiceInputReady = true
                        voiceError = null
                    }
                    Log.i(TAG, "Voice model loaded: $VOICE_MODEL_ALIAS")
                } catch (e: Exception) {
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
    fun startVoiceInput(context: android.content.Context) {
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
                streamSession = session

                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                recorder.startRecording()
                isStreamCaptureActive.set(true)

                // Capture coroutine
                captureJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isStreamCaptureActive.get()) {
                            val bytesRead = recorder.read(buffer, 0, bufferSize)
                            if (bytesRead < 0) {
                                isStreamCaptureActive.set(false)
                                break
                            }
                            if (bytesRead == 0) continue
                            channel.send(buffer.copyOf(bytesRead))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture error", e)
                    } finally {
                        channel.close()
                    }
                }

                // Push coroutine
                streamingJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        for (chunk in channel) {
                            val result = session.pushAudioChunk(chunk)
                            val partialText = result.text
                            if (partialText.isNotEmpty()) {
                                val current = partialText
                                    .replace(Regex("\\s+"), " ").trim()
                                withContext(Dispatchers.Main) {
                                    voiceTranscript = current
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Push loop error", e)
                    } finally {
                        try {
                            val finalResult = withContext(NonCancellable + Dispatchers.IO) {
                                session.stop()
                            }
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (finalResult.text.isNotEmpty()) {
                                    voiceTranscript = finalResult.text
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping stream", e)
                        }
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (voiceTranscript.isNotBlank()) {
                                inputText = voiceTranscript
                            }
                            isListening = false
                            streamSession = null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceInput error", e)
                withContext(Dispatchers.Main) {
                    voiceError = e.message ?: e.javaClass.simpleName
                    isListening = false
                }
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    fun stopVoiceInput() {
        isListening = false
        isStreamCaptureActive.set(false)
        runCatching { listenJob?.cancel() }
        listenJob = null
        runCatching { audioChunkChannel?.close() }
        runCatching { captureJob?.cancel() }

        // Let streamingJob drain buffered chunks after the channel closes. The
        // fallback covers the narrow window where the session exists before the
        // streaming job starts.
        val session = streamSession
        if (session != null) {
            viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                if (streamSession === session) {
                    streamSession = null
                    runCatching { session.stop() }
                }
            }
        }

        val recorder = audioRecord
        audioRecord = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    // --- Private helpers ---

    private fun checkModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = manager?.getCatalog() ?: return@launch
                val m = catalog.getModel(MODEL_ALIAS)
                model = m

                val cached = m.isCached()
                val loaded = m.isLoaded()

                withContext(Dispatchers.Main) {
                    isDownloaded = cached
                    isLoaded = loaded
                    if (loaded) {
                        createChatClient()
                    }
                }

                checkVoiceModelStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking model status", e)
            }
        }
    }

    private fun createChatClient() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = model?.createChatClient()
                withContext(Dispatchers.Main) {
                    chatClient = client
                    setupError = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating chat client", e)
                withContext(Dispatchers.Main) {
                    chatClient = null
                    isLoaded = false
                    allowAutoLoad = false
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    private suspend fun checkVoiceModelStatus() {
        try {
            val catalog = manager?.getCatalog() ?: return
            val vm = catalog.getModel(VOICE_MODEL_ALIAS)
            voiceModel = vm

            val cached = vm.isCached()
            val loaded = vm.isLoaded()

            withContext(Dispatchers.Main) {
                isVoiceModelDownloaded = cached
                isVoiceModelLoaded = loaded
                if (loaded) {
                    createAudioClient()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking voice model status", e)
        }
    }

    private suspend fun createAudioClient() {
        try {
            val client = withContext(Dispatchers.IO) {
                voiceModel?.createAudioClient()
            } ?: return
            withContext(Dispatchers.Main) {
                audioClient = client
                isVoiceInputReady = true
                voiceError = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating audio client", e)
            withContext(Dispatchers.Main) {
                isVoiceInputReady = false
                audioClient = null
                voiceError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatStreamingJob?.cancel()
        stopVoiceInput()
        runCatching { voiceSetupJob?.cancel() }
        manager?.close()
        Log.i(TAG, "ViewModel cleared, manager closed")
    }
}

data class ChatMessageUI(
    val text: String,
    val isUser: Boolean
)
