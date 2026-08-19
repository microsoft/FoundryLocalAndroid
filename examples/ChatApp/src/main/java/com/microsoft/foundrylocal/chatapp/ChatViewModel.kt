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
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.microsoft.foundrylocal.FoundryLocalManager
import com.microsoft.foundrylocal.FoundryAudioTranscriptionClient
import com.microsoft.foundrylocal.FoundryChatCompletionClient
import com.microsoft.foundrylocal.FoundryModel
import com.microsoft.foundrylocal.IFoundryLocalManager
import com.microsoft.foundrylocal.callbacks.FoundryServiceConnectionCallback
import com.microsoft.foundrylocal.callbacks.FoundryOperationProgressCallback
import com.microsoft.foundrylocal.datamodels.Configuration
import com.microsoft.foundrylocal.datamodels.audio.AudioStreamSettings
import com.microsoft.foundrylocal.datamodels.chat.ChatCompletionRequest
import com.microsoft.foundrylocal.datamodels.chat.ChatMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for the ChatApp — handles chat messaging and voice input via Foundry Local SDK.
 */
class ChatViewModel : ViewModel() {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MODEL_ALIAS = "qwen2.5-coder-0.5b-instruct-generic-cpu:4"
        private const val STREAMING_AUDIO_MODEL_ALIAS = "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    private object NoOpProgressCallback : FoundryOperationProgressCallback {
        override fun onProgressUpdate(
            operationType: FoundryOperationProgressCallback.OperationType,
            modelAlias: String, status: FoundryOperationProgressCallback.OperationStatus,
            progressPercent: Float, message: String?
        ) {}
        override fun onOperationComplete(
            operationType: FoundryOperationProgressCallback.OperationType,
            modelAlias: String, successful: Boolean, errorMessage: String?
        ) {}
    }
    
    val modelAlias: String = MODEL_ALIAS

    private var foundryManager: FoundryLocalManager? = null
    private var chatCompletionClient: FoundryChatCompletionClient? = null
    private var model: FoundryModel? = null

    // Voice input resources
    private var voiceModel: FoundryModel? = null
    private var audioClient: FoundryAudioTranscriptionClient? = null
    private var audioRecord: AudioRecord? = null
    private var streamSessionHandle: String? = null
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var voiceSetupJob: Job? = null
    private var captureJob: Job? = null
    private var streamingJob: Job? = null
    private val isStreamCaptureActive = AtomicBoolean(false)

    // UI State
    var isConnected by mutableStateOf(false)
        private set
    
    var isDownloaded by mutableStateOf(false)
        private set
    
    var isLoaded by mutableStateOf(false)
        private set

    /** True when both chat model and voice model are fully ready */
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

    // Voice input state
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

    /**
     * Idempotent voice setup — call when isLoaded becomes true.
     * Internally guarded so it only runs once per load cycle.
     */
    fun startVoiceSetupIfNeeded(context: ComponentActivity) {
        if (!isLoaded || voiceSetupAttempted || isVoiceInputReady) return
        voiceSetupAttempted = true

        voiceSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check current status first
                checkVoiceModelStatus()
                if (isVoiceInputReady) return@launch

                // Download if needed, then load will chain automatically
                if (!isVoiceModelDownloaded) {
                    downloadVoiceModel(context)
                } else if (!isVoiceModelLoaded) {
                    // Already downloaded but not loaded — trigger load
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

    /** Resets voice setup state and retries. Use when voice setup fails. */
    fun retryVoiceSetup(context: ComponentActivity) {
        voiceSetupAttempted = false
        voiceError = null
        startVoiceSetupIfNeeded(context)
    }

    fun connect(context: ComponentActivity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    setupError = null
                }
                val options = Configuration(
                    appName = "ChatApp",
                    logLevel = "Debug",
                )
                
                foundryManager = FoundryLocalManager(options)
                
                foundryManager?.connect(context, object : FoundryServiceConnectionCallback {
                    override fun onServiceConnected(manager: IFoundryLocalManager) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isConnected = true
                            checkModelStatus()
                        }
                    }

                    override fun onServiceDisconnected(errorCode: FoundryServiceConnectionCallback.ErrorCode, message: String?) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isConnected = false
                            isLoaded = false
                            isDownloaded = false
                            chatCompletionClient = null
                            model = null
                            showProgress = false
                            progressPercent = 0f
                            isVoiceModelDownloaded = false
                            isVoiceModelLoaded = false
                            isVoiceInputReady = false
                            voiceModel = null
                            audioClient = null
                            showVoiceProgress = false
                            voiceProgressPercent = 0f
                            voiceSetupAttempted = false
                            setupError = message ?: "Service disconnected"
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    fun downloadModel(context: ComponentActivity) {
        if (!isConnected) return

        // If chat model already downloaded, just start voice download
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
                val catalogRes = foundryManager?.getCatalog()
                if (catalogRes == null || !catalogRes.status) {
                    withContext(Dispatchers.Main) {
                        showProgress = false
                        setupError = catalogRes?.error?.message ?: "Failed to load model catalog"
                    }
                    return@launch
                }

                val catalog = catalogRes.data
                val modelRes = catalog?.getModel(MODEL_ALIAS)
                if (modelRes == null || !modelRes.status) {
                    withContext(Dispatchers.Main) {
                        showProgress = false
                        setupError = modelRes?.error?.message ?: "Model $MODEL_ALIAS not found in catalog"
                    }
                    return@launch
                }

                val downloadModel = modelRes.data
                model = downloadModel

                if (downloadModel != null) {
                    Log.d(TAG, "Starting download for model: $MODEL_ALIAS")
                    val tapIntent = PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    downloadModel.download(context, object : FoundryOperationProgressCallback {
                        override fun onProgressUpdate(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            status: FoundryOperationProgressCallback.OperationStatus,
                            progressPercent: Float,
                            message: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                this@ChatViewModel.progressPercent = progressPercent
                            }
                        }
                        
                        override fun onOperationComplete(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            successful: Boolean,
                            errorMessage: String?
                        ) {
                            Log.d(TAG, "Chat download complete: successful=$successful, error=$errorMessage")
                            viewModelScope.launch(Dispatchers.Main) {
                                showProgress = false
                                if (successful) {
                                    isDownloaded = true
                                    setupError = null
                                    // Chain voice model download after chat model completes
                                    Log.d(TAG, "Chaining voice model download")
                                    downloadVoiceModel(context)
                                } else {
                                    setupError = errorMessage ?: "Model download failed"
                                }
                            }
                        }
                    }, tapIntent)
                } else {
                    withContext(Dispatchers.Main) {
                        showProgress = false
                        setupError = "Model metadata unavailable for $MODEL_ALIAS"
                    }
                }
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
                model?.load(object : FoundryOperationProgressCallback {
                        override fun onProgressUpdate(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            status: FoundryOperationProgressCallback.OperationStatus,
                            progressPercent: Float,
                            message: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                this@ChatViewModel.progressPercent = progressPercent
                            }
                        }
                        
                        override fun onOperationComplete(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            successful: Boolean,
                            errorMessage: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                showProgress = false
                                if (successful) {
                                    isLoaded = true
                                    setupError = null
                                    createChatClient()
                                } else {
                                    setupError = errorMessage ?: "Model load failed"
                                }
                            }
                        }
                    })
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
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val unloadModel = model
                if (unloadModel != null) {
                    unloadModel.unload(object : FoundryOperationProgressCallback {
                        override fun onProgressUpdate(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            status: FoundryOperationProgressCallback.OperationStatus,
                            progressPercent: Float,
                            message: String?
                        ) {
                            // No need to update UI during unload
                        }
                        
                        override fun onOperationComplete(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            successful: Boolean,
                            errorMessage: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                isLoaded = false
                                allowAutoLoad = false
                                chatCompletionClient = null
                                chatMessages.clear()
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unload error", e)
                withContext(Dispatchers.Main) {
                    isLoaded = false
                    allowAutoLoad = false
                    chatCompletionClient = null
                    chatMessages.clear()
                }
            }
        }
    }

    fun sendMessage() {
        if (!isLoaded || inputText.isBlank()) return
        
        val userMessage = inputText.trim()
        inputText = ""
        
        // Add user message to chat
        chatMessages.add(ChatMessageUI(userMessage, isUser = true))
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = ChatCompletionRequest().apply {
                    messages.add(ChatMessage(ChatMessage.Role.USER, userMessage))
                    temperature = 0.7f
                    maxTokens = 1000
                }
                
                val completionResult = chatCompletionClient?.completeChat(request)
                
                withContext(Dispatchers.Main) {
                    if (completionResult?.status == true) {
                        val response = completionResult.data?.message?.content ?: "No response"
                        chatMessages.add(ChatMessageUI(response, isUser = false))
                    } else {
                        chatMessages.add(ChatMessageUI("Error: ${completionResult?.error?.message}", isUser = false))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send message error", e)
                withContext(Dispatchers.Main) {
                    chatMessages.add(ChatMessageUI("Error: ${e.message ?: e.javaClass.simpleName}", isUser = false))
                }
            }
        }
    }

    private fun createChatClient() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (model != null) {
                    val chatRes = model?.createChatClient()
                    if (chatRes?.status == true && chatRes.data != null) {
                        withContext(Dispatchers.Main) {
                            chatCompletionClient = chatRes.data
                            setupError = null
                        }
                    } else {
                        // Unload model so state stays consistent
                        try { model?.unload(NoOpProgressCallback) } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            chatCompletionClient = null
                            isLoaded = false
                            allowAutoLoad = false
                            setupError = chatRes?.error?.message ?: "Failed to create chat client"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating chat client", e)
                try { model?.unload(NoOpProgressCallback) } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    chatCompletionClient = null
                    isLoaded = false
                    allowAutoLoad = false
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalogRes = foundryManager?.getCatalog()
                val catalog = catalogRes?.data
                val modelRes = catalog?.getModel(MODEL_ALIAS)
                val currentModel = modelRes?.data
                model = currentModel
                
                if (currentModel != null) {
                    val cachedRes = currentModel.isCached()
                    val loadedRes = currentModel.isLoaded()
                    
                    withContext(Dispatchers.Main) {
                        isDownloaded = cachedRes.data == true
                        isLoaded = loadedRes.data == true
                        
                        if (isLoaded) {
                            createChatClient()
                        }
                    }
                }

                // Also check voice model status
                checkVoiceModelStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking model status", e)
            }
        }
    }

    // ── Voice input ──────────────────────────────────────────────────────

    private suspend fun checkVoiceModelStatus() {
        try {
            val catalogRes = foundryManager?.getCatalog()
            val catalog = catalogRes?.data
            if (catalog == null) {
                withContext(Dispatchers.Main) {
                    voiceModel = null
                    isVoiceModelDownloaded = false
                    isVoiceModelLoaded = false
                    isVoiceInputReady = false
                }
                return
            }
            val modelRes = catalog.getModel(STREAMING_AUDIO_MODEL_ALIAS)
            val vm = modelRes?.data
            if (vm == null) {
                withContext(Dispatchers.Main) {
                    voiceModel = null
                    isVoiceModelDownloaded = false
                    isVoiceModelLoaded = false
                    isVoiceInputReady = false
                }
                return
            }
            voiceModel = vm

            val cached = vm.isCached().data == true
            val loaded = vm.isLoaded().data == true

            withContext(Dispatchers.Main) {
                isVoiceModelDownloaded = cached
                isVoiceModelLoaded = loaded
                if (loaded) {
                    createAudioClient()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking voice model status", e)
            withContext(Dispatchers.Main) {
                voiceModel = null
                isVoiceModelDownloaded = false
                isVoiceModelLoaded = false
                isVoiceInputReady = false
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
                val catalogRes = foundryManager?.getCatalog()
                val catalog = catalogRes?.data
                val modelRes = catalog?.getModel(STREAMING_AUDIO_MODEL_ALIAS)
                val vm = modelRes?.data

                if (vm == null) {
                    Log.e(TAG, "Voice model $STREAMING_AUDIO_MODEL_ALIAS not found in catalog")
                    withContext(Dispatchers.Main) {
                        showVoiceProgress = false
                        voiceError = "Voice model not found in catalog"
                    }
                    return@launch
                }

                voiceModel = vm

                Log.d(TAG, "Starting download for voice model: $STREAMING_AUDIO_MODEL_ALIAS")
                val tapIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                vm.download(context, object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Float,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            voiceProgressPercent = progressPercent
                        }
                    }

                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        Log.d(TAG, "Voice download complete: successful=$successful, error=$errorMessage")
                        viewModelScope.launch(Dispatchers.Main) {
                            showVoiceProgress = false
                            if (successful) {
                                isVoiceModelDownloaded = true
                                loadVoiceModel()
                            } else {
                                voiceError = errorMessage ?: "Download failed"
                            }
                        }
                    }
                }, tapIntent)
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
        if (!isConnected || !isVoiceModelDownloaded || isVoiceModelLoaded || voiceModel == null || showVoiceProgress) return

        viewModelScope.launch {
            showVoiceProgress = true
            voiceError = null

            withContext(Dispatchers.IO) {
                try {
                    voiceModel?.load(object : FoundryOperationProgressCallback {
                        override fun onProgressUpdate(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            status: FoundryOperationProgressCallback.OperationStatus,
                            progressPercent: Float,
                            message: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                voiceProgressPercent = progressPercent
                            }
                        }

                        override fun onOperationComplete(
                            operationType: FoundryOperationProgressCallback.OperationType,
                            modelAlias: String,
                            successful: Boolean,
                            errorMessage: String?
                        ) {
                            viewModelScope.launch(Dispatchers.Main) {
                                showVoiceProgress = false
                                if (successful) {
                                    isVoiceModelLoaded = true
                                    createAudioClient()
                                } else {
                                    voiceError = errorMessage ?: "Load failed"
                                }
                            }
                        }
                    })
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

    private fun createAudioClient() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val vm = voiceModel ?: return@launch
                val res = vm.createAudioClient()
                if (res.status) {
                    audioClient = res.data
                    withContext(Dispatchers.Main) {
                        isVoiceInputReady = true
                        voiceError = null
                    }
                } else {
                    Log.e(TAG, "createAudioClient error: ${res.error?.message}")
                    withContext(Dispatchers.Main) {
                        isVoiceInputReady = false
                        audioClient = null
                        voiceError = "Failed to create audio client: ${res.error?.message ?: "unknown error"}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating audio client", e)
                withContext(Dispatchers.Main) {
                    isVoiceInputReady = false
                    audioClient = null
                    voiceError = "Error creating audio client: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun startVoiceInput(context: ComponentActivity) {
        if (isListening) return

        // Permission check
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceError = "Microphone permission required"
            return
        }

        voiceError = null
        voiceTranscript = ""
        isListening = true

        voiceSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = audioClient
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        voiceError = "Voice model not ready"
                        isListening = false
                    }
                    return@launch
                }

                // Set up AudioRecord
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

                // Start streaming session
                val settings = AudioStreamSettings().apply {
                    this.sampleRate = sampleRate
                    channels = 1
                    bitsPerSample = 16
                    language = "en"
                }

                val startResult = try {
                    client.startStream(settings)
                } catch (e: android.os.DeadObjectException) {
                    Log.w(TAG, "Audio client binder dead, recreating...")
                    audioClient = null
                    val vm = voiceModel ?: throw Exception("Voice model unavailable")
                    val retryRes = vm.createAudioClient()
                    if (!retryRes.status) throw Exception("Failed to recreate audio client")
                    audioClient = retryRes.data
                    retryRes.data!!.startStream(settings)
                }

                if (!startResult.status) {
                    withContext(Dispatchers.Main) {
                        voiceError = "Error starting stream: ${startResult.error?.message}"
                        isListening = false
                    }
                    recorder.release()
                    audioRecord = null
                    return@launch
                }

                val sessionHandle = startResult.data ?: run {
                    withContext(Dispatchers.Main) {
                        voiceError = "Stream started without session handle"
                        isListening = false
                    }
                    recorder.release()
                    audioRecord = null
                    return@launch
                }
                streamSessionHandle = sessionHandle

                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                try {
                    recorder.startRecording()
                } catch (e: Exception) {
                    // Clean up server-side stream since startStream succeeded
                    try { client.stopStream(sessionHandle) } catch (_: Exception) {}
                    streamSessionHandle = null
                    recorder.release()
                    audioRecord = null
                    throw e
                }
                isStreamCaptureActive.set(true)

                // Capture coroutine: reads mic data into channel
                captureJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isStreamCaptureActive.get()) {
                            val bytesRead = recorder.read(buffer, 0, bufferSize)
                            if (bytesRead < 0) {
                                Log.e(TAG, "AudioRecord.read() error: $bytesRead")
                                isStreamCaptureActive.set(false)
                                break
                            }
                            if (bytesRead == 0) continue
                            channel.send(buffer.copyOf(bytesRead))
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Capture error", e)
                    } finally {
                        channel.close()
                    }
                }

                // Push coroutine: sends chunks to service, updates transcript
                streamingJob = viewModelScope.launch(Dispatchers.IO) pushLoop@{
                    val accumulated = StringBuilder()
                    try {
                        for (chunk in channel) {
                            val activeClient = audioClient
                            if (activeClient == null) {
                                isStreamCaptureActive.set(false)
                                channel.close()
                                break
                            }
                            val result = activeClient.pushAudioChunk(sessionHandle, chunk)
                            if (result.status) {
                                val partialText = result.data?.text ?: ""
                                if (partialText.isNotEmpty()) {
                                    if (accumulated.isNotEmpty()) accumulated.append(" ")
                                    accumulated.append(partialText)
                                    withContext(Dispatchers.Main) {
                                        voiceTranscript = accumulated.toString()
                                    }
                                }
                            } else {
                                Log.w(TAG, "pushAudioChunk error: ${result.error?.message}")
                            }
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Push loop error", e)
                    } finally {
                        // Channel closed → stop stream and get final transcript
                        try {
                            val finalClient = audioClient
                            if (finalClient != null) {
                                val finalResult = finalClient.stopStream(sessionHandle)
                                withContext(NonCancellable + Dispatchers.Main) {
                                    if (finalResult.status) {
                                        val finalText = finalResult.data?.text ?: ""
                                        if (finalText.isNotEmpty()) {
                                            voiceTranscript = finalText
                                        }
                                    }
                                }
                            }
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (voiceTranscript.isNotBlank()) {
                                    inputText = voiceTranscript
                                }
                                isListening = false
                                streamSessionHandle = null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping stream", e)
                            withContext(NonCancellable + Dispatchers.Main) {
                                if (voiceTranscript.isNotBlank()) {
                                    inputText = voiceTranscript
                                }
                                isListening = false
                                streamSessionHandle = null
                            }
                        }

                        // Release recorder
                        try {
                            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                                recorder.stop()
                            }
                        } catch (_: Exception) {}
                        try { recorder.release() } catch (_: Exception) {}
                        audioRecord = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startVoiceInput error", e)
                withContext(Dispatchers.Main) {
                    voiceError = e.message ?: e.javaClass.simpleName
                    isListening = false
                }
                // Clean up on failure
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    fun stopVoiceInput() {
        // Cancel setup if still in progress, then stop capture and streaming
        isListening = false
        isStreamCaptureActive.set(false)
        runCatching { voiceSetupJob?.cancel() }
        voiceSetupJob = null
        runCatching { audioChunkChannel?.close() }
        runCatching { captureJob?.cancel() }
        runCatching { streamingJob?.cancel() }

        // Immediately stop and release the AudioRecord so the mic is freed
        val recorder = audioRecord
        audioRecord = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun cleanupVoiceResources() {
        isStreamCaptureActive.set(false)
        runCatching { voiceSetupJob?.cancel() }
        voiceSetupJob = null
        runCatching { audioChunkChannel?.close() }
        runCatching { captureJob?.cancel() }
        val currentStreamingJob = streamingJob
        streamingJob = null
        runCatching { currentStreamingJob?.cancel() }

        // Only manually stop stream if there was no streaming job to handle it
        val client = audioClient
        val handle = streamSessionHandle
        if (currentStreamingJob == null && client != null && handle != null) {
            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                try { client.stopStream(handle) } catch (_: Exception) {}
            }
        }
        streamSessionHandle = null

        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanupVoiceResources()
        foundryManager?.disconnect(null)
    }
}

data class ChatMessageUI(
    val text: String,
    val isUser: Boolean
)
