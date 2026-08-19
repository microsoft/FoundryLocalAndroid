/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.apiexplorer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.microsoft.foundrylocal.api.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-level singleton that owns the download coroutine and tracks progress.
 *
 * The download runs in a process-level [CoroutineScope] that is NOT tied to any
 * ViewModel or Activity lifecycle. This means:
 * - Swiping the app away does NOT cancel the download (the DownloadForegroundService
 *   keeps the process alive and the coroutine keeps running).
 * - Reopening the app creates a new ViewModel that observes [state] and picks up
 *   live progress immediately.
 */
object ActiveDownloadState {
    private const val TAG = "ActiveDownloadState"

    data class DownloadProgress(
        val isActive: Boolean = false,
        val progressPercent: Float = 0f,
        val progressText: String = "No operation in progress",
        val isIndeterminate: Boolean = false,
        val successful: Boolean = false
    )

    // Process-level scope — survives ViewModel/Activity destruction.
    // SupervisorJob ensures a failure doesn't cancel the scope itself.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DownloadProgress())
    val state: StateFlow<DownloadProgress> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.isActive

    @Volatile private var downloadJob: Job? = null
    @Volatile private var _completionMessage: String? = null

    // Guards against a late progress callback re-arming an already-completed download.
    // Progress and completion can race across the IPC boundary; once [complete] runs,
    // further progress writes are dropped so the terminal (inactive) state is authoritative.
    private val stateLock = Any()
    private var terminated = false

    /**
     * Starts a download in the process-level scope.
     * The coroutine survives Activity/ViewModel destruction because:
     * 1. It runs in [scope] (not viewModelScope)
     * 2. The DownloadForegroundService keeps the process alive
     */
    fun startDownload(model: Model, context: Context) {
        if (isActive) return

        synchronized(stateLock) {
            terminated = false
            _state.value = DownloadProgress(
                isActive = true,
                progressText = "Starting download...",
                isIndeterminate = true
            )
        }

        val tapIntent = PendingIntent.getActivity(
            context.applicationContext,
            0,
            Intent(context.applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        downloadJob = scope.launch {
            try {
                model.download(
                    progress = { percent ->
                        val formattedPercent = "%.2f".format(percent)
                        synchronized(stateLock) {
                            if (!terminated) {
                                _state.value = DownloadProgress(
                                    isActive = true,
                                    progressPercent = percent,
                                    progressText = "Download progress: $formattedPercent%"
                                )
                            }
                        }
                    },
                    contentIntent = tapIntent,
                    timeoutMinutes = 30
                )
                complete("Download completed successfully", successful = true)
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    complete("Download cancelled", successful = false)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Download failed", error)
                complete(
                    "Download failed: ${error.message ?: "Unknown error"}",
                    successful = false
                )
            }
        }
    }

    fun cancelDownload() {
        val job = downloadJob ?: return
        job.cancel()
        _state.value = DownloadProgress(
            isActive = true,
            progressPercent = _state.value.progressPercent,
            progressText = "Cancelling download...",
            isIndeterminate = true
        )
    }

    private fun complete(message: String, successful: Boolean) {
        synchronized(stateLock) {
            terminated = true
            _state.value = DownloadProgress(
                isActive = false,
                progressPercent = if (successful) 100f else 0f,
                progressText = message,
                successful = successful
            )
        }
        _completionMessage = message
        downloadJob = null
    }

    /** Returns and clears the completion message (consumed once by ViewModel). */
    fun consumeCompletionMessage(): String? {
        val msg = _completionMessage
        _completionMessage = null
        return msg
    }

    fun reset() {
        synchronized(stateLock) {
            terminated = false
            _state.value = DownloadProgress()
        }
        _completionMessage = null
        downloadJob = null
    }
}
