/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.embeddedaudiotranscription

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

/**
 * Embedded Audio Transcription — demonstrates the embedded (in-process) SDK audio APIs.
 *
 * Unlike the IPC AudioTranscriptionApp, this app does NOT require the Foundry Local
 * service app. The inference runtime runs directly inside this process via JNI.
 *
 * Two modes:
 *  1. File transcription with Whisper (sync + streaming).
 *  2. Real-time mic transcription with Nemotron streaming.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                EmbeddedAudioTranscriptionScreen()
            }
        }
    }
}
