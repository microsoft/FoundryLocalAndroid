/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.audiotranscription

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.os.DeadObjectException
import android.os.RemoteException
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

import com.microsoft.foundrylocal.AudioTranscriptionCallback
import com.microsoft.foundrylocal.FoundryAudioTranscriptionClient
import com.microsoft.foundrylocal.FoundryLocalManager
import com.microsoft.foundrylocal.FoundryModel
import com.microsoft.foundrylocal.IFoundryLocalManager
import com.microsoft.foundrylocal.callbacks.FoundryOperationProgressCallback
import com.microsoft.foundrylocal.callbacks.FoundryServiceConnectionCallback
import com.microsoft.foundrylocal.datamodels.Configuration
import com.microsoft.foundrylocal.datamodels.audio.AudioStreamSettings
import com.microsoft.foundrylocal.datamodels.audio.AudioTranscriptionResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for audio transcription with two modes:
 * 1. File-based transcription using Whisper model (sync + streaming)
 * 2. Real-time mic transcription using Nemotron streaming model
 */
class AudioTranscriptionViewModel : ViewModel() {
    companion object {
        private const val TAG = "AudioTranscriptionVM"
        private const val WHISPER_MODEL_ALIAS = "openai-whisper-tiny-generic-cpu:4"
        private const val NEMOTRON_MODEL_ALIAS = "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    // --- Connection state ---
    private var foundryManager: FoundryLocalManager? = null
    private var appContext: Context? = null

    var isConnected by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf("")
        private set
    var isStatusError by mutableStateOf(false)
        private set

    // --- Whisper (file transcription) state ---
    private var whisperModel: FoundryModel? = null
    private var whisperClient: FoundryAudioTranscriptionClient? = null

    var isWhisperDownloaded by mutableStateOf(false)
        private set
    var isWhisperLoaded by mutableStateOf(false)
        private set
    var whisperProgress by mutableStateOf(0f)
        private set
    var showWhisperProgress by mutableStateOf(false)
        private set

    var selectedAudioUri: Uri? by mutableStateOf(null)
        private set
    var selectedAudioFileName by mutableStateOf("")
        private set
    var transcriptionResult by mutableStateOf("")
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var streamingPartialText by mutableStateOf("")
        private set

    // --- Nemotron (real-time streaming) state ---
    private var nemotronModel: FoundryModel? = null
    private var nemotronClient: FoundryAudioTranscriptionClient? = null

    var isNemotronDownloaded by mutableStateOf(false)
        private set
    var isNemotronLoaded by mutableStateOf(false)
        private set
    var nemotronProgress by mutableStateOf(0f)
        private set
    var showNemotronProgress by mutableStateOf(false)
        private set

    var isRealTimeStreaming by mutableStateOf(false)
        private set
    var realTimeTranscript by mutableStateOf("")
        private set

    // Controls auto-load behavior in setup flow
    var allowAutoLoad by mutableStateOf(true)
        private set

    private var streamSessionHandle: String? = null
    private var streamingJob: Job? = null
    private var captureJob: Job? = null
    private val isStreamCaptureActive = AtomicBoolean(false)
    private val isStreamStopped = AtomicBoolean(false)
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var audioRecord: AudioRecord? = null

    // ==================== Connection ====================

    fun connect(context: Context) {
        statusMessage = "Connecting..."
        isStatusError = false
        appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val options = Configuration(
                    appName = "AudioTranscriptionApp",
                    logLevel = "Debug",
                )

                foundryManager = FoundryLocalManager(options)

                val bound = foundryManager?.connect(context, object : FoundryServiceConnectionCallback {
                    override fun onServiceConnected(manager: IFoundryLocalManager) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isConnected = true
                            statusMessage = "Connected"
                            isStatusError = false
                            checkModelStatuses()
                        }
                    }

                    override fun onServiceDisconnected(
                        errorCode: FoundryServiceConnectionCallback.ErrorCode,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isConnected = false
                            statusMessage = "Disconnected: ${message ?: errorCode.name}"
                            isStatusError = true
                        }
                    }
                })
                if (bound != true) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Failed to bind to service"
                        isStatusError = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Error: ${e.message}"
                    isStatusError = true
                }
            }
        }
    }

    private fun checkModelStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalogRes = foundryManager?.getCatalog()
                val catalog = catalogRes?.data ?: return@launch

                // Check Whisper
                val whisperRes = catalog.getModel(WHISPER_MODEL_ALIAS)
                val wModel = whisperRes?.data
                if (wModel != null) {
                    whisperModel = wModel
                    val cached = wModel.isCached().let { it.status && it.data == true }
                    val loaded = wModel.isLoaded().let { it.status && it.data == true }
                    withContext(Dispatchers.Main) {
                        isWhisperDownloaded = cached
                        isWhisperLoaded = loaded
                    }
                }

                // Check Nemotron
                val nemotronRes = catalog.getModel(NEMOTRON_MODEL_ALIAS)
                val nModel = nemotronRes?.data
                if (nModel != null) {
                    nemotronModel = nModel
                    val cached = nModel.isCached().let { it.status && it.data == true }
                    val loaded = nModel.isLoaded().let { it.status && it.data == true }
                    withContext(Dispatchers.Main) {
                        isNemotronDownloaded = cached
                        isNemotronLoaded = loaded
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking model statuses", e)
            }
        }
    }

    // ==================== Whisper (File Transcription) ====================

    fun downloadWhisperModel() {
        if (!isConnected) {
            statusMessage = "Not connected to service"
            isStatusError = true
            return
        }
        showWhisperProgress = true
        whisperProgress = 0f
        statusMessage = ""
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalogRes = foundryManager?.getCatalog()
                val catalog = catalogRes?.data
                if (catalog == null) {
                    Log.e(TAG, "Failed to get catalog: status=${catalogRes?.status}")
                    withContext(Dispatchers.Main) {
                        statusMessage = "Failed to get catalog"
                        isStatusError = true
                        showWhisperProgress = false
                    }
                    return@launch
                }

                val modelRes = catalog.getModel(WHISPER_MODEL_ALIAS)
                val model = modelRes?.data

                if (model == null) {
                    Log.e(TAG, "Whisper model not found: alias=$WHISPER_MODEL_ALIAS")
                    withContext(Dispatchers.Main) {
                        statusMessage = "Whisper model not found in catalog"
                        isStatusError = true
                        showWhisperProgress = false
                    }
                    return@launch
                }
                whisperModel = model

                val ctx = appContext ?: return@launch
                val tapIntent = PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                model.download(ctx, object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Float,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            whisperProgress = progressPercent
                        }
                    }

                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            showWhisperProgress = false
                            if (successful) {
                                isWhisperDownloaded = true
                                statusMessage = "Whisper model downloaded"
                                isStatusError = false
                            } else {
                                statusMessage = "Download failed: ${errorMessage ?: "unknown"}"
                                isStatusError = true
                            }
                        }
                    }
                }, tapIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading Whisper model", e)
                withContext(Dispatchers.Main) {
                    showWhisperProgress = false
                    statusMessage = "Download error: ${e.message}"
                    isStatusError = true
                }
            }
        }
    }

    fun loadWhisperModel() {
        if (!isConnected || !isWhisperDownloaded || whisperModel == null) return
        showWhisperProgress = true
        whisperProgress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                whisperModel?.load(object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Float,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            whisperProgress = progressPercent
                        }
                    }

                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            showWhisperProgress = false
                            if (successful) {
                                isWhisperLoaded = true
                                statusMessage = "Whisper model loaded"
                                isStatusError = false
                            } else {
                                statusMessage = "Load failed: ${errorMessage ?: "unknown"}"
                                isStatusError = true
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Whisper model", e)
                withContext(Dispatchers.Main) {
                    showWhisperProgress = false
                    statusMessage = "Load error: ${e.message}"
                    isStatusError = true
                }
            }
        }
    }

    fun onAudioFilePicked(uri: Uri, fileName: String) {
        selectedAudioUri = uri
        selectedAudioFileName = fileName
        transcriptionResult = ""
        streamingPartialText = ""
    }

    fun transcribeFile(context: Context) {
        val uri = selectedAudioUri ?: return
        if (!isWhisperLoaded) {
            statusMessage = "Load the Whisper model first"
            isStatusError = true
            return
        }

        isTranscribing = true
        transcriptionResult = ""
        statusMessage = "Transcribing..."
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = getOrCreateWhisperClient()
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        isTranscribing = false
                        statusMessage = "Failed to create audio client"
                        isStatusError = true
                    }
                    return@launch
                }

                val result = try {
                    client.transcribe(context.contentResolver, uri, language = "en")
                } catch (e: android.os.DeadObjectException) {
                    Log.w(TAG, "Whisper client binder dead, recreating...")
                    whisperClient = null
                    val retryClient = getOrCreateWhisperClient()
                        ?: throw Exception("Failed to recreate audio client")
                    retryClient.transcribe(context.contentResolver, uri, language = "en")
                }

                withContext(Dispatchers.Main) {
                    isTranscribing = false
                    if (result.status) {
                        val response = result.data
                        transcriptionResult = buildString {
                            appendLine(response?.text ?: "(no text)")
                            response?.language?.let { appendLine("Language: $it") }
                            response?.duration?.let { appendLine("Duration: ${"%.1f".format(it)}s") }
                        }
                        statusMessage = "Transcription complete"
                        isStatusError = false
                    } else {
                        transcriptionResult = "Error: ${result.error?.message}"
                        statusMessage = "Transcription failed"
                        isStatusError = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing file", e)
                withContext(Dispatchers.Main) {
                    isTranscribing = false
                    transcriptionResult = "Error: ${e.message}"
                    statusMessage = "Transcription error"
                    isStatusError = true
                }
            }
        }
    }

    fun transcribeFileStreaming(context: Context) {
        val uri = selectedAudioUri ?: return
        if (!isWhisperLoaded) {
            statusMessage = "Load the Whisper model first"
            isStatusError = true
            return
        }

        isTranscribing = true
        transcriptionResult = ""
        streamingPartialText = ""
        statusMessage = "Streaming transcription..."
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = getOrCreateWhisperClient()
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        isTranscribing = false
                        statusMessage = "Failed to create audio client"
                        isStatusError = true
                    }
                    return@launch
                }

                val callback = object : AudioTranscriptionCallback {
                    override fun onPartialResult(text: String) {
                        viewModelScope.launch(Dispatchers.Main) {
                            streamingPartialText = text
                        }
                    }

                    override fun onFinalResult(response: AudioTranscriptionResponse) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isTranscribing = false
                            transcriptionResult = buildString {
                                appendLine(response.text ?: "(no text)")
                                response.language?.let { appendLine("Language: $it") }
                                response.duration?.let { appendLine("Duration: ${"%.1f".format(it)}s") }
                            }
                            streamingPartialText = ""
                            statusMessage = "Streaming transcription complete"
                            isStatusError = false
                        }
                    }

                    override fun onError(errorMessage: String) {
                        viewModelScope.launch(Dispatchers.Main) {
                            isTranscribing = false
                            transcriptionResult = "Error: $errorMessage"
                            statusMessage = "Streaming transcription failed"
                            isStatusError = true
                        }
                    }
                }

                val result = try {
                    client.transcribeStreaming(context.contentResolver, uri, callback, language = "en")
                } catch (e: android.os.DeadObjectException) {
                    Log.w(TAG, "Whisper client binder dead, recreating...")
                    whisperClient = null
                    val retryClient = getOrCreateWhisperClient()
                        ?: throw Exception("Failed to recreate audio client")
                    retryClient.transcribeStreaming(context.contentResolver, uri, callback, language = "en")
                }

                if (!result.status) {
                    withContext(Dispatchers.Main) {
                        isTranscribing = false
                        transcriptionResult = "Error: ${result.error?.message}"
                        statusMessage = "Failed to start streaming"
                        isStatusError = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in streaming transcription", e)
                withContext(Dispatchers.Main) {
                    isTranscribing = false
                    transcriptionResult = "Error: ${e.message}"
                    statusMessage = "Streaming error"
                    isStatusError = true
                }
            }
        }
    }

    private suspend fun getOrCreateWhisperClient(): FoundryAudioTranscriptionClient? {
        whisperClient?.let { return it }

        val catalogRes = foundryManager?.getCatalog()
        val catalog = if (catalogRes?.status == true) catalogRes.data else null
        val modelRes = catalog?.getModel(WHISPER_MODEL_ALIAS)
        val model = if (modelRes?.status == true) modelRes.data else null

        if (model == null) {
            withContext(Dispatchers.Main) {
                statusMessage = "Whisper model not found"
                isStatusError = true
            }
            return null
        }
        whisperModel = model

        val isCached = model.isCached().let { it.status && it.data == true }
        if (!isCached) {
            withContext(Dispatchers.Main) { statusMessage = "Whisper model not downloaded"; isStatusError = true }
            return null
        }
        val isLoaded = model.isLoaded().let { it.status && it.data == true }
        if (!isLoaded) {
            withContext(Dispatchers.Main) { statusMessage = "Whisper model not loaded"; isStatusError = true }
            return null
        }

        val clientRes = model.createAudioClient()
        if (clientRes.status) {
            whisperClient = clientRes.data
            return whisperClient
        }
        withContext(Dispatchers.Main) {
            statusMessage = "createAudioClient error: ${clientRes.error?.message}"
            isStatusError = true
        }
        return null
    }

    // ==================== Nemotron (Real-Time Streaming) ====================

    fun downloadNemotronModel() {
        if (!isConnected) {
            statusMessage = "Not connected to service"
            isStatusError = true
            return
        }
        showNemotronProgress = true
        nemotronProgress = 0f
        statusMessage = ""
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalogRes = foundryManager?.getCatalog()
                val catalog = catalogRes?.data
                if (catalog == null) {
                    Log.e(TAG, "Failed to get catalog: status=${catalogRes?.status}, error=${catalogRes?.error?.message}")
                    withContext(Dispatchers.Main) {
                        statusMessage = "Failed to get catalog: ${catalogRes?.error?.message ?: "unknown error"}"
                        isStatusError = true
                        showNemotronProgress = false
                    }
                    return@launch
                }

                val modelRes = catalog.getModel(NEMOTRON_MODEL_ALIAS)
                val model = modelRes?.data
                if (model == null) {
                    Log.e(TAG, "Nemotron model not found: alias=$NEMOTRON_MODEL_ALIAS, status=${modelRes?.status}, error=${modelRes?.error?.message}")
                    withContext(Dispatchers.Main) {
                        statusMessage = "Model not found in catalog: $NEMOTRON_MODEL_ALIAS"
                        isStatusError = true
                        showNemotronProgress = false
                    }
                    return@launch
                }
                nemotronModel = model

                val ctx = appContext ?: return@launch
                val tapIntent = PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                model.download(ctx, object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Float,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            nemotronProgress = progressPercent
                        }
                    }

                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            showNemotronProgress = false
                            if (successful) {
                                isNemotronDownloaded = true
                                statusMessage = "Nemotron model downloaded"
                                isStatusError = false
                            } else {
                                statusMessage = "Download failed: ${errorMessage ?: "unknown"}"
                                isStatusError = true
                            }
                        }
                    }
                }, tapIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading Nemotron model", e)
                withContext(Dispatchers.Main) {
                    showNemotronProgress = false
                    statusMessage = "Download error: ${e.message}"
                    isStatusError = true
                }
            }
        }
    }

    fun loadNemotronModel() {
        if (!isConnected || !isNemotronDownloaded || nemotronModel == null) return
        showNemotronProgress = true
        nemotronProgress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nemotronModel?.load(object : FoundryOperationProgressCallback {
                    override fun onProgressUpdate(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        status: FoundryOperationProgressCallback.OperationStatus,
                        progressPercent: Float,
                        message: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            nemotronProgress = progressPercent
                        }
                    }

                    override fun onOperationComplete(
                        operationType: FoundryOperationProgressCallback.OperationType,
                        modelAlias: String,
                        successful: Boolean,
                        errorMessage: String?
                    ) {
                        viewModelScope.launch(Dispatchers.Main) {
                            showNemotronProgress = false
                            if (successful) {
                                isNemotronLoaded = true
                                statusMessage = "Nemotron model loaded"
                                isStatusError = false
                            } else {
                                statusMessage = "Load failed: ${errorMessage ?: "unknown"}"
                                isStatusError = true
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Nemotron model", e)
                withContext(Dispatchers.Main) {
                    showNemotronProgress = false
                    statusMessage = "Load error: ${e.message}"
                    isStatusError = true
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun startRealTimeTranscription(context: Context) {
        if (!isConnected || isRealTimeStreaming) return
        if (!isNemotronLoaded) {
            statusMessage = "Load the Nemotron model first"
            isStatusError = true
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusMessage = "RECORD_AUDIO permission required"
            isStatusError = true
            return
        }

        realTimeTranscript = ""
        statusMessage = "Starting real-time transcription..."
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = getOrCreateNemotronClient()
                if (client == null) {
                    withContext(Dispatchers.Main) { statusMessage = "Failed to create streaming client"; isStatusError = true }
                    return@launch
                }

                // Set up AudioRecord
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                if (minBufferSize <= 0) {
                    withContext(Dispatchers.Main) { statusMessage = "Audio input not supported"; isStatusError = true }
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
                    withContext(Dispatchers.Main) { statusMessage = "Failed to initialize AudioRecord"; isStatusError = true }
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
                    Log.w(TAG, "Nemotron client binder dead, recreating...")
                    nemotronClient = null
                    val retryClient = getOrCreateNemotronClient()
                        ?: throw Exception("Failed to recreate streaming client")
                    retryClient.startStream(settings)
                }

                if (!startResult.status || startResult.data == null) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Error starting stream: ${startResult.error?.message}"
                        isStatusError = true
                    }
                    recorder.release()
                    audioRecord = null
                    return@launch
                }

                val sessionHandle = startResult.data!!
                streamSessionHandle = sessionHandle
                isStreamStopped.set(false)

                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                recorder.startRecording()
                isStreamCaptureActive.set(true)

                withContext(Dispatchers.Main) {
                    isRealTimeStreaming = true
                    statusMessage = "Listening... speak now"
                    isStatusError = false
                }

                // Capture coroutine: reads mic → channel
                captureJob = viewModelScope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(bufferSize)
                    try {
                        while (isStreamCaptureActive.get()) {
                            val bytesRead = recorder.read(buffer, 0, bufferSize)
                            if (bytesRead < 0) {
                                Log.e(TAG, "AudioRecord.read() error: $bytesRead")
                                isStreamCaptureActive.set(false)
                                runCatching { recorder.stop() }
                                runCatching { recorder.release() }
                                audioRecord = null
                                break
                            }
                            if (bytesRead == 0) continue
                            channel.send(buffer.copyOf(bytesRead))
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in capture loop", e)
                    } finally {
                        channel.close()
                    }
                }

                // Push coroutine: channel → service
                streamingJob = viewModelScope.launch(Dispatchers.IO) pushLoop@{
                    try {
                        for (chunk in channel) {
                            val activeClient = nemotronClient ?: break
                            val result = activeClient.pushAudioChunk(sessionHandle, chunk)
                            if (result.status) {
                                val partialText = result.data?.text ?: ""
                                if (partialText.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        realTimeTranscript = partialText
                                    }
                                }
                            } else {
                                Log.w(TAG, "pushAudioChunk error: ${result.error?.message}")
                            }
                        }
                    } catch (e: DeadObjectException) {
                        Log.e(TAG, "Service died during streaming", e)
                        isStreamCaptureActive.set(false)
                        withContext(Dispatchers.Main) {
                            statusMessage = "Service connection lost"
                            isStatusError = true
                            isRealTimeStreaming = false
                        }
                    } catch (e: RemoteException) {
                        Log.e(TAG, "Remote exception during streaming", e)
                        isStreamCaptureActive.set(false)
                        withContext(Dispatchers.Main) {
                            statusMessage = "Service error: ${e.message}"
                            isStatusError = true
                            isRealTimeStreaming = false
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in push loop", e)
                    }

                    // Channel closed — stop stream and get final result
                    try {
                        if (isStreamStopped.get()) return@pushLoop
                        val finalClient = nemotronClient ?: return@pushLoop
                        val finalResult = finalClient.stopStream(sessionHandle)
                        isStreamStopped.set(true)
                        withContext(Dispatchers.Main) {
                            if (finalResult.status) {
                                val finalText = finalResult.data?.text ?: ""
                                if (finalText.isNotEmpty()) {
                                    realTimeTranscript = finalText
                                }
                                statusMessage = "Transcription complete"
                                isStatusError = false
                            } else {
                                statusMessage = "Error finalizing: ${finalResult.error?.message}"
                                isStatusError = true
                            }
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping stream", e)
                    } finally {
                        withContext(NonCancellable) {
                            // Ensure server-side stream is stopped (skip if already stopped by stopRealTimeTranscription)
                            if (!isStreamStopped.get()) {
                                try {
                                    val streamClient = nemotronClient
                                    val handle = streamSessionHandle
                                    if (streamClient != null && handle != null) {
                                        streamClient.stopStream(handle)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error stopping stream", e)
                                }
                            }
                            // Ensure mic capture is stopped even if the push loop exits abnormally
                            isStreamCaptureActive.set(false)
                            runCatching { captureJob?.cancel() }
                            runCatching { audioChunkChannel?.close() }
                            try {
                                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) audioRecord?.stop()
                                audioRecord?.release()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error stopping AudioRecord in finally", e)
                            }
                            audioRecord = null
                            streamSessionHandle = null
                            withContext(Dispatchers.Main) {
                                isRealTimeStreaming = false
                            }
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error starting real-time transcription", e)
                runCatching {
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) audioRecord?.stop()
                }
                runCatching { audioRecord?.release() }
                audioRecord = null
                withContext(Dispatchers.Main) {
                    statusMessage = "Error: ${e.message}"
                    isStatusError = true
                    isRealTimeStreaming = false
                }
            }
        }
    }

    fun stopRealTimeTranscription() {
        if (!isRealTimeStreaming) return

        isRealTimeStreaming = false
        statusMessage = "Stopping..."
        isStatusError = false

        // Stop mic capture and close channel to signal push loop to exit
        isStreamCaptureActive.set(false)
        captureJob?.cancel()
        captureJob = null
        audioChunkChannel?.close()

        // Mark stream as stopped BEFORE launching the stop coroutine so the push loop's
        // normal exit path (line 835) and finally block (line 858) both skip their stopStream calls
        val client = nemotronClient
        val handle = streamSessionHandle
        streamSessionHandle = null
        isStreamStopped.set(true)

        // Stop the server-side stream, then cancel the push loop
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            if (client != null && handle != null) {
                try {
                    val finalResult = client.stopStream(handle)
                    withContext(Dispatchers.Main) {
                        if (finalResult.status) {
                            val finalText = finalResult.data?.text ?: ""
                            if (finalText.isNotEmpty()) {
                                realTimeTranscript = finalText
                            }
                            statusMessage = "Transcription complete"
                            isStatusError = false
                        } else {
                            statusMessage = "Error finalizing: ${finalResult.error?.message}"
                            isStatusError = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping stream", e)
                }
            }

            streamingJob?.cancel()
            streamingJob = null
        }

        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
    }

    private suspend fun getOrCreateNemotronClient(): FoundryAudioTranscriptionClient? {
        nemotronClient?.let { return it }

        val catalogRes = foundryManager?.getCatalog()
        val catalog = if (catalogRes?.status == true) catalogRes.data else null
        val modelRes = catalog?.getModel(NEMOTRON_MODEL_ALIAS)
        val model = if (modelRes?.status == true) modelRes.data else null

        if (model == null) {
            withContext(Dispatchers.Main) { statusMessage = "Nemotron model not found"; isStatusError = true }
            return null
        }
        nemotronModel = model

        val isCached = model.isCached().let { it.status && it.data == true }
        if (!isCached) {
            withContext(Dispatchers.Main) { statusMessage = "Nemotron model not downloaded"; isStatusError = true }
            return null
        }
        val isLoaded = model.isLoaded().let { it.status && it.data == true }
        if (!isLoaded) {
            withContext(Dispatchers.Main) { statusMessage = "Nemotron model not loaded"; isStatusError = true }
            return null
        }

        val clientRes = model.createAudioClient()
        if (clientRes.status) {
            nemotronClient = clientRes.data
            return nemotronClient
        }
        withContext(Dispatchers.Main) {
            statusMessage = "createAudioClient error: ${clientRes.error?.message}"
            isStatusError = true
        }
        return null
    }

    // ==================== Helpers ====================

    override fun onCleared() {
        super.onCleared()
        isStreamCaptureActive.set(false)
        captureJob?.cancel()
        streamingJob?.cancel()
        audioChunkChannel?.close()
        val client = nemotronClient
        val handle = streamSessionHandle
        streamSessionHandle = null
        nemotronClient = null
        // Fire-and-forget cleanup to avoid blocking the main thread
        if (client != null && handle != null) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                runCatching { client.stopStream(handle) }
            }
        }
        runCatching {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) audioRecord?.stop()
        }
        runCatching { audioRecord?.release() }
        audioRecord = null
        foundryManager?.disconnect(appContext)
        appContext = null
    }
}
