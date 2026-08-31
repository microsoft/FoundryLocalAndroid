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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.microsoft.foundrylocal.api.AudioClient
import com.microsoft.foundrylocal.api.AudioStreamResult
import com.microsoft.foundrylocal.api.AudioStreamSession
import com.microsoft.foundrylocal.api.AudioStreamSettings
import com.microsoft.foundrylocal.api.AudioTranscriptionRequest
import com.microsoft.foundrylocal.api.Configuration
import com.microsoft.foundrylocal.api.FoundryLocalManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
        private const val NEMOTRON_MODEL_ALIAS =
            "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    private data class StreamState(
        val session: AudioStreamSession,
        val finalized: AtomicBoolean = AtomicBoolean(false)
    )

    private var manager: FoundryLocalManager? = null
    private var appContext: Context? = null

    var isConnected by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf("")
        private set
    var isStatusError by mutableStateOf(false)
        private set

    private var whisperModel: Model? = null
    private var whisperClient: AudioClient? = null

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
    private var selectedAudioPath: String? = null

    var transcriptionResult by mutableStateOf("")
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var streamingPartialText by mutableStateOf("")
        private set

    private var nemotronModel: Model? = null
    private var nemotronClient: AudioClient? = null

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

    var allowAutoLoad by mutableStateOf(true)
        private set

    private var streamState: StreamState? = null
    private var connectionJob: Job? = null
    private var filePreparationJob: Job? = null
    private var streamSetupJob: Job? = null
    private var streamingJob: Job? = null
    private var streamCleanupJob: Job? = null
    private var captureJob: Job? = null
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var audioRecord: AudioRecord? = null
    private val isStreamCaptureActive = AtomicBoolean(false)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect(context: Context) {
        if (isConnected || connectionJob?.isActive == true) return

        statusMessage = "Connecting..."
        isStatusError = false
        appContext = context.applicationContext

        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val createdManager = manager?.also {
                    it.reconnect()
                } ?: FoundryLocalManager.create(
                        context = context,
                        config = Configuration(
                            appName = "AudioTranscriptionAppIPC",
                            logLevel = "Debug"
                        ),
                        onDisconnected = {
                            releaseRecorder()
                            viewModelScope.launch(Dispatchers.Main) {
                                isConnected = false
                                isRealTimeStreaming = false
                                streamState = null
                                whisperModel = null
                                whisperClient = null
                                nemotronModel = null
                                nemotronClient = null
                                statusMessage = "Service connection lost"
                                isStatusError = true
                            }
                        }
                    )

                manager = createdManager
                withContext(Dispatchers.Main) {
                    isConnected = createdManager.isConnected
                    statusMessage = "Connected"
                    isStatusError = false
                }
                checkModelStatuses()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Error: ${e.message}"
                    isStatusError = true
                }
            } finally {
                connectionJob = null
            }
        }
    }

    private suspend fun checkModelStatuses() {
        try {
            val catalog = manager?.getCatalog() ?: return
            val whisper = catalog.getModel(WHISPER_MODEL_ALIAS)
            val nemotron = catalog.getModel(NEMOTRON_MODEL_ALIAS)
            whisperModel = whisper
            nemotronModel = nemotron

            val whisperCached = whisper.isCached()
            val whisperLoaded = whisper.isLoaded()
            val nemotronCached = nemotron.isCached()
            val nemotronLoaded = nemotron.isLoaded()

            withContext(Dispatchers.Main) {
                isWhisperDownloaded = whisperCached
                isWhisperLoaded = whisperLoaded
                isNemotronDownloaded = nemotronCached
                isNemotronLoaded = nemotronLoaded
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking model statuses", e)
        }
    }

    fun downloadWhisperModel() {
        if (!isConnected) {
            showStatusError("Not connected to service")
            return
        }
        if (showWhisperProgress) return

        showWhisperProgress = true
        whisperProgress = 0f
        statusMessage = ""
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = getModel(WHISPER_MODEL_ALIAS)
                whisperModel = model
                model.download(
                    progress = { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            whisperProgress = progress
                        }
                    },
                    contentIntent = createDownloadIntent()
                )
                withContext(Dispatchers.Main) {
                    showWhisperProgress = false
                    isWhisperDownloaded = true
                    statusMessage = "Whisper model downloaded"
                    isStatusError = false
                }
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
        if (!isConnected || !isWhisperDownloaded || showWhisperProgress) return

        showWhisperProgress = true
        whisperProgress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = whisperModel ?: getModel(WHISPER_MODEL_ALIAS).also {
                    whisperModel = it
                }
                model.load()
                val client = model.createAudioClient()
                withContext(Dispatchers.Main) {
                    whisperClient = client
                    showWhisperProgress = false
                    isWhisperLoaded = true
                    statusMessage = "Whisper model loaded"
                    isStatusError = false
                }
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

    fun onAudioFilePicked(context: Context, uri: Uri, displayName: String) {
        filePreparationJob?.cancel()
        filePreparationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = sanitizeDisplayName(displayName)
                val cacheFile =
                    File(context.cacheDir, "picked_${System.currentTimeMillis()}_$name")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open selected file")

                withContext(Dispatchers.Main) {
                    selectAudioFile(cacheFile, name)
                }
                Log.i(TAG, "File copied to cache (${cacheFile.length()} bytes)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "File pick error", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Failed to open audio file"
                    isStatusError = true
                }
            }
        }
    }

    fun useSampleAudio(context: Context) {
        filePreparationJob?.cancel()
        filePreparationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = "sample_audio.mp3"
                val cacheFile = File(context.cacheDir, name)
                context.resources.openRawResource(R.raw.sample_audio).use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    selectAudioFile(cacheFile, name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Sample audio error", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Failed to prepare sample audio"
                    isStatusError = true
                }
            }
        }
    }

    fun transcribeFile() {
        val path = selectedAudioPath
        if (path == null) {
            showStatusError("Select an audio file first")
            return
        }
        if (!isWhisperLoaded) {
            showStatusError("Load the Whisper model first")
            return
        }
        if (isTranscribing) return

        isTranscribing = true
        transcriptionResult = ""
        streamingPartialText = ""
        statusMessage = "Transcribing..."
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = getOrCreateWhisperClient().transcribe(
                    AudioTranscriptionRequest(filePath = path, language = "en")
                )
                withContext(Dispatchers.Main) {
                    transcriptionResult = formatTranscription(
                        response.text,
                        response.language,
                        response.duration
                    )
                    statusMessage = "Transcription complete"
                    isStatusError = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing file", e)
                withContext(Dispatchers.Main) {
                    transcriptionResult = "Error: ${e.message}"
                    statusMessage = "Transcription error"
                    isStatusError = true
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isTranscribing = false
                }
            }
        }
    }

    fun transcribeFileStreaming() {
        val path = selectedAudioPath
        if (path == null) {
            showStatusError("Select an audio file first")
            return
        }
        if (!isWhisperLoaded) {
            showStatusError("Load the Whisper model first")
            return
        }
        if (isTranscribing) return

        isTranscribing = true
        transcriptionResult = ""
        streamingPartialText = ""
        statusMessage = "Streaming transcription..."
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                getOrCreateWhisperClient().transcribeStreaming(
                    AudioTranscriptionRequest(filePath = path, language = "en")
                ).collect { event ->
                    withContext(Dispatchers.Main) {
                        if (event.isFinal) {
                            transcriptionResult = formatTranscription(
                                event.text,
                                event.language,
                                duration = null
                            )
                            streamingPartialText = ""
                        } else {
                            streamingPartialText = event.text
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    statusMessage = "Streaming transcription complete"
                    isStatusError = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in streaming transcription", e)
                withContext(Dispatchers.Main) {
                    transcriptionResult = "Error: ${e.message}"
                    statusMessage = "Streaming error"
                    isStatusError = true
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    isTranscribing = false
                }
            }
        }
    }

    fun downloadNemotronModel() {
        if (!isConnected) {
            showStatusError("Not connected to service")
            return
        }
        if (showNemotronProgress) return

        showNemotronProgress = true
        nemotronProgress = 0f
        statusMessage = ""
        isStatusError = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = getModel(NEMOTRON_MODEL_ALIAS)
                nemotronModel = model
                model.download(
                    progress = { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            nemotronProgress = progress
                        }
                    },
                    contentIntent = createDownloadIntent()
                )
                withContext(Dispatchers.Main) {
                    showNemotronProgress = false
                    isNemotronDownloaded = true
                    statusMessage = "Nemotron model downloaded"
                    isStatusError = false
                }
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
        if (!isConnected || !isNemotronDownloaded || showNemotronProgress) return

        showNemotronProgress = true
        nemotronProgress = 0f

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = nemotronModel ?: getModel(NEMOTRON_MODEL_ALIAS).also {
                    nemotronModel = it
                }
                model.load()
                val client = model.createAudioClient()
                withContext(Dispatchers.Main) {
                    nemotronClient = client
                    showNemotronProgress = false
                    isNemotronLoaded = true
                    statusMessage = "Nemotron model loaded"
                    isStatusError = false
                }
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
        if (!isConnected || isRealTimeStreaming || streamSetupJob?.isActive == true) return
        if (!isNemotronLoaded) {
            showStatusError("Load the Nemotron model first")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showStatusError("RECORD_AUDIO permission required")
            return
        }

        realTimeTranscript = ""
        statusMessage = "Starting real-time transcription..."
        isStatusError = false

        streamSetupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize =
                    AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                if (minBufferSize <= 0) {
                    throw IllegalStateException("Audio input not supported")
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
                    recorder.release()
                    audioRecord = null
                    throw IllegalStateException("Failed to initialize AudioRecord")
                }

                val session = getOrCreateNemotronClient().createStreamSession(
                    AudioStreamSettings(
                        sampleRate = sampleRate,
                        channels = 1,
                        bitsPerSample = 16,
                        language = "en"
                    )
                )
                val state = StreamState(session)
                streamState = state
                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                recorder.startRecording()
                isStreamCaptureActive.set(true)
                withContext(Dispatchers.Main) {
                    isRealTimeStreaming = true
                    statusMessage = "Listening... speak now"
                    isStatusError = false
                }

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
                            if (bytesRead > 0) {
                                channel.send(buffer.copyOf(bytesRead))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isStreamCaptureActive.get()) {
                            Log.e(TAG, "Error in capture loop", e)
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
                            if (result.text.isNotEmpty()) {
                                val current = normalizeTranscript(result.text)
                                withContext(Dispatchers.Main) {
                                    realTimeTranscript = current
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        streamError = e
                        Log.e(TAG, "Error in push loop", e)
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
                                realTimeTranscript = normalizeTranscript(it)
                            }
                            if (finalized) {
                                if (streamError == null) {
                                    statusMessage = "Transcription complete"
                                    isStatusError = false
                                } else {
                                    statusMessage = "Real-time transcription failed"
                                    isStatusError = true
                                }
                                if (streamState === state) streamState = null
                                isRealTimeStreaming = false
                            } else if (streamState === state) {
                                statusMessage = "Failed to stop audio stream. Tap Stop to retry."
                                isStatusError = true
                                isRealTimeStreaming = true
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
                Log.e(TAG, "Error starting real-time transcription", e)
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
                        statusMessage = "Error: ${e.message}"
                        isStatusError = true
                        isRealTimeStreaming = false
                    } else if (state != null && streamState === state) {
                        statusMessage = "Failed to stop audio stream. Tap Stop to retry."
                        isStatusError = true
                        isRealTimeStreaming = true
                    }
                }
            } finally {
                streamSetupJob = null
            }
        }
    }

    fun stopRealTimeTranscription() {
        val state = streamState
        if (!isRealTimeStreaming && state == null) return

        statusMessage = "Stopping..."
        isStatusError = false
        isStreamCaptureActive.set(false)
        val setupJob = streamSetupJob
        streamSetupJob = null
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
                            isRealTimeStreaming = false
                            statusMessage = "Transcription complete"
                            isStatusError = false
                        } else {
                            isRealTimeStreaming = true
                            statusMessage = "Failed to stop audio stream. Tap Stop to retry."
                            isStatusError = true
                        }
                    }
                    streamCleanupJob = null
                }
            }
        }
    }

    private suspend fun getModel(alias: String): Model {
        val currentManager = manager
            ?: throw IllegalStateException("Not connected to service")
        return currentManager.getCatalog().getModel(alias)
    }

    private suspend fun getOrCreateWhisperClient(): AudioClient {
        whisperClient?.let { return it }
        val model = whisperModel ?: getModel(WHISPER_MODEL_ALIAS).also {
            whisperModel = it
        }
        if (!model.isLoaded()) {
            throw IllegalStateException("Whisper model not loaded")
        }
        return model.createAudioClient().also { whisperClient = it }
    }

    private suspend fun getOrCreateNemotronClient(): AudioClient {
        nemotronClient?.let { return it }
        val model = nemotronModel ?: getModel(NEMOTRON_MODEL_ALIAS).also {
            nemotronModel = it
        }
        if (!model.isLoaded()) {
            throw IllegalStateException("Nemotron model not loaded")
        }
        return model.createAudioClient().also { nemotronClient = it }
    }

    private fun createDownloadIntent(): PendingIntent? {
        val context = appContext ?: return null
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun selectAudioFile(file: File, name: String) {
        selectedAudioPath = file.absolutePath
        selectedAudioUri = Uri.fromFile(file)
        selectedAudioFileName = name
        transcriptionResult = ""
        streamingPartialText = ""
        statusMessage = ""
        isStatusError = false
    }

    private fun sanitizeDisplayName(displayName: String): String {
        val basename = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return basename.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "picked_audio"
    }

    private fun formatTranscription(
        text: String,
        language: String?,
        duration: Double?
    ): String = buildString {
        appendLine(text.ifBlank { "(no text)" })
        language?.let { appendLine("Language: $it") }
        duration?.let { appendLine("Duration: ${"%.1f".format(it)}s") }
    }

    private fun normalizeTranscript(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun showStatusError(message: String) {
        statusMessage = message
        isStatusError = true
    }

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
        val fileJob = filePreparationJob
        val setupJob = streamSetupJob
        val producerJob = captureJob
        val pushJob = streamingJob
        val retryJob = streamCleanupJob
        cleanupScope.launch {
            runCatching { connectJob?.cancelAndJoin() }
            runCatching { fileJob?.cancelAndJoin() }
            runCatching { setupJob?.cancelAndJoin() }
            runCatching { producerJob?.cancelAndJoin() }
            runCatching { pushJob?.cancelAndJoin() }
            runCatching { retryJob?.cancelAndJoin() }

            val state = streamState
            streamState = null
            if (state != null) {
                runCatching { finalizeSession(state) }
                    .onFailure { Log.e(TAG, "Failed to stop audio stream", it) }
            }
            val currentManager = manager
            manager = null
            currentManager?.close()
            cleanupScope.cancel()
        }
        appContext = null
    }
}
