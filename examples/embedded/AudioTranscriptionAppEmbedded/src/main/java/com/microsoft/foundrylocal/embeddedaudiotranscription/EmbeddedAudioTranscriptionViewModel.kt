/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.embeddedaudiotranscription

import android.Manifest
import android.content.Context
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for the Embedded Audio Transcription sample.
 *
 * Demonstrates the embedded (in-process) SDK audio APIs — no service app required.
 * Two modes:
 *  1. File transcription with Whisper (sync + streaming).
 *  2. Real-time mic transcription with Nemotron streaming.
 *
 * Each model is downloaded and loaded lazily the first time its mode is used.
 */
class EmbeddedAudioTranscriptionViewModel : ViewModel() {

    companion object {
        private const val TAG = "EmbeddedAudioVM"

        // Always use full model ids (name:version) — never aliases.
        private const val WHISPER_MODEL_ID = "openai-whisper-tiny-generic-cpu:4"
        private const val NEMOTRON_MODEL_ID = "nemotron-speech-streaming-en-0.6b-generic-cpu:3"
    }

    // --- SDK state ---
    private var manager: FoundryLocalManager? = null
    private var connectionJob: Job? = null

    private var whisperModel: Model? = null
    private var whisperClient: AudioClient? = null
    private var whisperPrepareJob: Job? = null
    private var transcriptionJob: Job? = null

    private var nemotronModel: Model? = null
    private var nemotronClient: AudioClient? = null
    private var nemotronPrepareJob: Job? = null

    // --- Mic capture state ---
    private data class MicStreamState(
        val session: AudioStreamSession,
        val finalized: AtomicBoolean = AtomicBoolean(false)
    )

    private var streamState: MicStreamState? = null
    private var audioRecord: AudioRecord? = null
    private var audioChunkChannel: Channel<ByteArray>? = null
    private var listenJob: Job? = null
    private var captureJob: Job? = null
    private var streamingJob: Job? = null
    private val isStreamCaptureActive = AtomicBoolean(false)

    // Survives viewModelScope cancellation so teardown (session.stop before
    // manager.close) can complete when the ViewModel is cleared mid-recording.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Connection UI state ---
    var isConnected by mutableStateOf(false)
        private set
    var setupError by mutableStateOf<String?>(null)
        private set

    // --- Whisper (file) UI state ---
    var isWhisperReady by mutableStateOf(false)
        private set
    var whisperStatus by mutableStateOf("")
        private set
    var whisperProgress by mutableStateOf(0f)
        private set
    var showWhisperProgress by mutableStateOf(false)
        private set

    var selectedFileName by mutableStateOf<String?>(null)
        private set
    private var selectedFilePath: String? = null

    var isTranscribing by mutableStateOf(false)
        private set
    var transcriptionResult by mutableStateOf("")
        private set
    var fileError by mutableStateOf<String?>(null)
        private set

    // --- Nemotron (mic) UI state ---
    var isNemotronReady by mutableStateOf(false)
        private set
    var nemotronStatus by mutableStateOf("")
        private set
    var nemotronProgress by mutableStateOf(0f)
        private set
    var showNemotronProgress by mutableStateOf(false)
        private set

    var isListening by mutableStateOf(false)
        private set
    var isStopping by mutableStateOf(false)
        private set
    var micTranscript by mutableStateOf("")
        private set
    var micError by mutableStateOf<String?>(null)
        private set

    // --- Connection ---

    fun connect(context: Context) {
        if (isConnected) return
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { setupError = null }
                val mgr = FoundryLocalManager.create(
                    context.applicationContext,
                    Configuration(appName = "EmbeddedAudioTranscriptionApp")
                )
                manager = mgr
                withContext(Dispatchers.Main) { isConnected = true }
                Log.i(TAG, "Connected to embedded runtime")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                withContext(Dispatchers.Main) {
                    setupError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    // --- Whisper file transcription ---

    /** Downloads + loads Whisper if needed, then creates the audio client. */
    fun prepareWhisper() {
        if (!isConnected || isWhisperReady || showWhisperProgress) return
        showWhisperProgress = true
        fileError = null
        whisperStatus = "Preparing Whisper model…"
        whisperPrepareJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = manager?.getCatalog()
                    ?: throw IllegalStateException("Catalog unavailable")
                val m = catalog.getModel(WHISPER_MODEL_ID)
                whisperModel = m

                if (!m.isCached()) {
                    withContext(Dispatchers.Main) { whisperStatus = "Downloading Whisper model…" }
                    m.download(progress = { p ->
                        viewModelScope.launch(Dispatchers.Main) {
                            whisperProgress = p
                        }
                    })
                }

                if (!m.isLoaded()) {
                    withContext(Dispatchers.Main) { whisperStatus = "Loading Whisper model…" }
                    m.load()
                }

                val client = m.createAudioClient()
                withContext(Dispatchers.Main) {
                    whisperClient = client
                    isWhisperReady = true
                    showWhisperProgress = false
                    whisperStatus = "Whisper ready"
                }
                Log.i(TAG, "Whisper ready: $WHISPER_MODEL_ID")
            } catch (e: Exception) {
                Log.e(TAG, "Whisper prepare error", e)
                withContext(Dispatchers.Main) {
                    showWhisperProgress = false
                    fileError = e.message ?: e.javaClass.simpleName
                    whisperStatus = "Whisper setup failed"
                }
            }
        }
    }

    /**
     * Copies a SAF-picked content [Uri] into app cache and records its path.
     * The embedded transcribe API needs a real filesystem path, not a content Uri.
     */
    fun onFilePicked(context: Context, uri: Uri, displayName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = sanitizeDisplayName(displayName)
                val cacheFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}_$name")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open selected file")

                selectedFilePath = cacheFile.absolutePath
                withContext(Dispatchers.Main) {
                    selectedFileName = name
                    transcriptionResult = ""
                    fileError = null
                }
                Log.i(TAG, "File copied to cache (${cacheFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "File pick error", e)
                withContext(Dispatchers.Main) {
                    fileError = e.message ?: e.javaClass.simpleName
                }
            }
        }
    }

    private fun sanitizeDisplayName(displayName: String?): String {
        val basename = displayName.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return basename.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "picked_audio"
    }

    /** One-shot transcription of the selected file. */
    fun transcribeFile() {
        val path = selectedFilePath
        val client = whisperClient
        if (path == null) {
            fileError = "Select an audio file first"
            return
        }
        if (client == null) {
            fileError = "Whisper model not ready"
            return
        }
        if (isTranscribing) return
        isTranscribing = true
        fileError = null
        transcriptionResult = ""

        transcriptionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = client.transcribe(
                    AudioTranscriptionRequest(filePath = path, language = "en")
                )
                withContext(Dispatchers.Main) {
                    transcriptionResult = response.text
                    isTranscribing = false
                }
                Log.i(TAG, "Transcription complete (${response.text.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Transcribe error", e)
                withContext(Dispatchers.Main) {
                    fileError = e.message ?: e.javaClass.simpleName
                    isTranscribing = false
                }
            }
        }
    }

    /** Streaming transcription of the selected file — updates result incrementally. */
    fun transcribeFileStreaming() {
        val path = selectedFilePath
        val client = whisperClient
        if (path == null) {
            fileError = "Select an audio file first"
            return
        }
        if (client == null) {
            fileError = "Whisper model not ready"
            return
        }
        if (isTranscribing) return
        isTranscribing = true
        fileError = null
        transcriptionResult = ""

        transcriptionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val accumulated = StringBuilder()
                client.transcribeStreaming(
                    AudioTranscriptionRequest(filePath = path, language = "en")
                )
                    .catch { e ->
                        Log.e(TAG, "Streaming transcribe error", e)
                        withContext(Dispatchers.Main) {
                            fileError = e.message ?: e.javaClass.simpleName
                        }
                    }
                    .collect { event ->
                        if (event.text.isNotEmpty()) {
                            // The final event carries the complete transcript, so
                            // replace rather than append to avoid duplication.
                            if (event.isFinal) {
                                accumulated.setLength(0)
                            }
                            accumulated.append(event.text)
                            val current = accumulated.toString()
                            withContext(Dispatchers.Main) { transcriptionResult = current }
                        }
                    }
                withContext(Dispatchers.Main) { isTranscribing = false }
                Log.i(TAG, "Streaming transcription complete")
            } catch (e: Exception) {
                Log.e(TAG, "Streaming transcribe error", e)
                withContext(Dispatchers.Main) {
                    fileError = e.message ?: e.javaClass.simpleName
                    isTranscribing = false
                }
            }
        }
    }

    // --- Nemotron real-time mic ---

    /** Downloads + loads Nemotron if needed, then creates the audio client. */
    fun prepareNemotron() {
        if (!isConnected || isNemotronReady || showNemotronProgress) return
        showNemotronProgress = true
        micError = null
        nemotronStatus = "Preparing mic model…"
        nemotronPrepareJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalog = manager?.getCatalog()
                    ?: throw IllegalStateException("Catalog unavailable")
                val m = catalog.getModel(NEMOTRON_MODEL_ID)
                nemotronModel = m

                if (!m.isCached()) {
                    withContext(Dispatchers.Main) { nemotronStatus = "Downloading mic model…" }
                    m.download(progress = { p ->
                        viewModelScope.launch(Dispatchers.Main) {
                            nemotronProgress = p
                        }
                    })
                }

                if (!m.isLoaded()) {
                    withContext(Dispatchers.Main) { nemotronStatus = "Loading mic model…" }
                    m.load()
                }

                val client = m.createAudioClient()
                withContext(Dispatchers.Main) {
                    nemotronClient = client
                    isNemotronReady = true
                    showNemotronProgress = false
                    nemotronStatus = "Mic model ready"
                }
                Log.i(TAG, "Nemotron ready: $NEMOTRON_MODEL_ID")
            } catch (e: Exception) {
                Log.e(TAG, "Nemotron prepare error", e)
                withContext(Dispatchers.Main) {
                    showNemotronProgress = false
                    micError = e.message ?: e.javaClass.simpleName
                    nemotronStatus = "Mic model setup failed"
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun startMic(context: Context) {
        if (isListening || isStopping) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micError = "Microphone permission required"
            return
        }

        val client = nemotronClient
        if (client == null) {
            micError = "Mic model not ready"
            return
        }

        micError = null
        micTranscript = ""
        isListening = true

        listenJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize =
                    AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
                if (minBufferSize <= 0) {
                    withContext(Dispatchers.Main) {
                        micError = "Audio input not supported"
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
                        micError = "Failed to initialize microphone"
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
                val state = MicStreamState(session)
                streamState = state

                val channel = Channel<ByteArray>(capacity = 64)
                audioChunkChannel = channel

                recorder.startRecording()
                isStreamCaptureActive.set(true)

                val producerJob = viewModelScope.launch(Dispatchers.IO) {
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
                captureJob = producerJob

                streamingJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        for (chunk in channel) {
                            val result = session.pushAudioChunk(chunk)
                            val partialText = result.text
                            if (partialText.isNotEmpty()) {
                                // audio_stream_push returns the latest hypothesis,
                                // not a delta. Replace the display on each result.
                                val current = partialText
                                    .replace(Regex("\\s+"), " ").trim()
                                withContext(Dispatchers.Main) { micTranscript = current }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Push loop error", e)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (streamState === state) isStopping = true
                        }
                        releaseRecorder(recorder, producerJob, channel)
                        var finalized = false
                        try {
                            val finalResult = withContext(NonCancellable + Dispatchers.IO) {
                                finalizeSession(state)
                            }
                            finalized = true
                            if (finalResult != null && finalResult.text.isNotEmpty()) {
                                withContext(NonCancellable + Dispatchers.Main) {
                                    micTranscript = finalResult.text
                                        .replace(Regex("\\s+"), " ").trim()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping stream", e)
                        }
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (streamState === state) {
                                if (finalized) {
                                    streamState = null
                                    isListening = false
                                } else {
                                    micError = "Failed to stop audio stream. Tap Stop to retry."
                                }
                                isStopping = false
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Stop may be requested while the recorder/session pipeline is
                // still being created. Tear down anything created so far; if
                // the push job already owns the session, its finally completes
                // the teardown instead.
                releaseRecorder()
                val state = streamState
                var finalized = state == null
                if (state != null && streamingJob?.isActive != true) {
                    finalized = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { finalizeSession(state) }
                            .onFailure { Log.e(TAG, "Failed to cancel audio stream startup", it) }
                            .isSuccess
                    }
                    if (finalized && streamState === state) {
                        streamState = null
                    }
                }
                if (streamingJob?.isActive != true) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (finalized) {
                            isListening = false
                        } else {
                            isListening = true
                            micError = "Failed to stop audio stream. Tap Stop to retry."
                        }
                        isStopping = false
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startMic error", e)
                // A session may already have been created before the failure
                // (e.g. AudioRecord.startRecording threw) — finalize it so a
                // retry doesn't leak a second native stream.
                val state = streamState
                var finalized = state == null
                if (state != null) {
                    finalized = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { finalizeSession(state) }
                            .onFailure { Log.e(TAG, "Failed to clean up audio stream", it) }
                            .isSuccess
                    }
                    if (finalized && streamState === state) {
                        streamState = null
                    }
                }
                releaseRecorder()
                withContext(NonCancellable + Dispatchers.Main) {
                    if (finalized) {
                        isListening = false
                        micError = e.message ?: e.javaClass.simpleName
                    } else {
                        isListening = true
                        micError = "Failed to stop audio stream. Tap Stop to retry."
                    }
                    isStopping = false
                }
            }
        }
    }

    fun stopMic() {
        if (!isListening || isStopping) return
        isStopping = true
        isStreamCaptureActive.set(false)
        // Cancels the setup coroutine if Stop is tapped before the session and
        // capture jobs have been fully created.
        runCatching { listenJob?.cancel() }
        listenJob = null
        runCatching { audioChunkChannel?.close() }
        releaseRecorder()

        // Do not cancel streamingJob here: a closed channel still drains its
        // buffered chunks, preserving the tail of the recording. The fallback
        // covers the narrow window where a session exists before that job starts.
        val state = streamState
        if (state != null && streamingJob?.isActive != true) {
            cleanupScope.launch {
                val finalized = runCatching {
                    withContext(NonCancellable) { finalizeSession(state) }
                }.onFailure {
                    Log.e(TAG, "Failed to stop audio stream", it)
                }.isSuccess
                withContext(Dispatchers.Main) {
                    if (streamState === state) {
                        if (finalized) {
                            streamState = null
                            isListening = false
                        } else {
                            micError = "Failed to stop audio stream. Tap Stop to retry."
                        }
                        isStopping = false
                    }
                }
            }
        }
    }

    /** Stops capture and releases the recorder. Safe to call repeatedly. */
    private fun releaseRecorder() {
        releaseRecorder(audioRecord, captureJob, audioChunkChannel)
    }

    private fun releaseRecorder(
        recorder: AudioRecord?,
        producerJob: Job?,
        channel: Channel<ByteArray>?
    ) {
        isStreamCaptureActive.set(false)
        runCatching { producerJob?.cancel() }
        runCatching { channel?.close() }
        if (captureJob === producerJob) captureJob = null
        if (audioChunkChannel === channel) audioChunkChannel = null
        if (audioRecord === recorder) audioRecord = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    /**
     * Stops the native stream session at most once. Returns the final result the
     * first time it runs, or `null` if another path already finalized it.
     */
    private suspend fun finalizeSession(state: MicStreamState): AudioStreamResult? {
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
        val startupJob = listenJob
        val connectJob = connectionJob
        val whisperSetupJob = whisperPrepareJob
        val nemotronSetupJob = nemotronPrepareJob
        val fileJob = transcriptionJob

        isListening = false
        releaseRecorder()
        runCatching { audioChunkChannel?.close() }
        runCatching { listenJob?.cancel() }

        // Finalize the session BEFORE closing the manager — closing marks a shared
        // guard that would make a pending session.stop() fail. Do this on a scope
        // that survives ViewModel cancellation. Await startup first because it
        // may still be creating the session, then await the push job's own
        // NonCancellable finally before closing the manager.
        cleanupScope.launch {
            runCatching { connectJob?.cancelAndJoin() }
            val mgr = manager
            manager = null
            runCatching { fileJob?.cancelAndJoin() }
            runCatching { whisperSetupJob?.cancelAndJoin() }
            runCatching { nemotronSetupJob?.cancelAndJoin() }
            runCatching { startupJob?.cancelAndJoin() }
            val state = streamState
            streamState = null
            val pushJob = streamingJob
            runCatching { pushJob?.cancelAndJoin() }
            if (state != null) {
                withContext(NonCancellable) {
                    runCatching { finalizeSession(state) }
                        .onFailure { Log.e(TAG, "Failed to finalize audio stream", it) }
                }
            }
            runCatching {
                whisperModel?.let { model ->
                    if (model.isLoaded()) model.unload()
                }
            }.onFailure { Log.e(TAG, "Failed to unload Whisper model", it) }
            runCatching {
                nemotronModel?.let { model ->
                    if (model.isLoaded()) model.unload()
                }
            }.onFailure { Log.e(TAG, "Failed to unload Nemotron model", it) }
            runCatching { mgr?.close() }
            Log.i(TAG, "ViewModel cleared, models unloaded and manager closed")
            cleanupScope.cancel()
        }
    }
}
