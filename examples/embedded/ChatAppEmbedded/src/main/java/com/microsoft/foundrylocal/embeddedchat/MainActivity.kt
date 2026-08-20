/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.embeddedchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat

/**
 * Embedded Chat — demonstrates the embedded (in-process) SDK for on-device AI chat.
 *
 * Unlike the IPC examples, this app does NOT require the Foundry Local service app.
 * The inference runtime runs directly inside this process via JNI.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true

        setContent {
            MaterialTheme {
                EmbeddedChatScreen()
            }
        }
    }
}
