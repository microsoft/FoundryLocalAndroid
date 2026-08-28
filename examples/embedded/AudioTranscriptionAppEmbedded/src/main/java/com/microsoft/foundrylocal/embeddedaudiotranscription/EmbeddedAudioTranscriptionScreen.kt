/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.embeddedaudiotranscription

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

private val ScreenBackground = Color(0xFFF8FAFC)
private val Primary = Color(0xFF6200EE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddedAudioTranscriptionScreen(
    viewModel: EmbeddedAudioTranscriptionViewModel = viewModel()
) {
    val context = LocalContext.current

    // Connect to the embedded runtime once.
    LaunchedEffect(Unit) { viewModel.connect(context) }

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = ScreenBackground,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
                .padding(paddingValues)
        ) {
            Header()

            if (!viewModel.isConnected) {
                ConnectingState(error = viewModel.setupError)
                return@Column
            }

            TabRow(selectedTabIndex = selectedTab, containerColor = Color.White) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        if (viewModel.isListening) {
                            viewModel.stopMic()
                        } else {
                            selectedTab = 0
                        }
                    },
                    enabled = !viewModel.isStopping,
                    text = { Text("File (Whisper)") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Mic (Nemotron)") }
                )
            }

            when (selectedTab) {
                0 -> Box(Modifier.weight(1f)) { FileTranscriptionTab(viewModel) }
                else -> Box(Modifier.weight(1f)) { MicTranscriptionTab(viewModel) }
            }

            BrandFooter()
        }
    }
}

@Composable
private fun BrandFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(vertical = 20.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF8FAFC)),
            contentAlignment = Alignment.Center
        ) {
            FooterLogoGrid()
        }
        Text(
            text = "Foundry Local — Embedded",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B)
        )
        Text(
            text = "Runs in-process — no service app required",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B)
        )
    }
}

@Composable
private fun FooterLogoGrid() {
    val logoColors = listOf(
        Color(0xFFF25022), Color(0xFF7FBA00),
        Color(0xFF00A4EF), Color(0xFFFFB900)
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FooterLogoSquare(logoColors[0]); FooterLogoSquare(logoColors[1])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FooterLogoSquare(logoColors[2]); FooterLogoSquare(logoColors[3])
        }
    }
}

@Composable
private fun FooterLogoSquare(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

@Composable
private fun Header() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                ),
                shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "On-Device Transcription",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Embedded mode — inference runs directly in-process, no service app needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ConnectingState(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (error == null) {
            CircularProgressIndicator(color = Primary)
            Spacer(Modifier.height(16.dp))
            Text("Connecting to embedded runtime…")
        } else {
            Text(
                "Connection failed",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FileTranscriptionTab(viewModel: EmbeddedAudioTranscriptionViewModel) {
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var name: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx)
                }
            }
            viewModel.onFilePicked(context, uri, name)
        }
    }

    // Prepare Whisper as soon as this tab is shown.
    LaunchedEffect(Unit) { viewModel.prepareWhisper() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModelStatusCard(
            title = "Whisper model",
            ready = viewModel.isWhisperReady,
            status = viewModel.whisperStatus,
            showProgress = viewModel.showWhisperProgress,
            progress = viewModel.whisperProgress
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("audio/*")) },
                    enabled = !viewModel.isTranscribing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(viewModel.selectedFileName ?: "Select audio file")
                }

                TextButton(
                    onClick = { viewModel.useSampleAudio(context) },
                    enabled = !viewModel.isTranscribing,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Use sample audio")
                }

                Text(
                    "Supported: MP3, M4A, AAC, FLAC, OGG, and most WAV files.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.transcribeFile() },
                        enabled = viewModel.isWhisperReady &&
                            viewModel.selectedFileName != null &&
                            !viewModel.isTranscribing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("Transcribe") }

                    OutlinedButton(
                        onClick = { viewModel.transcribeFileStreaming() },
                        enabled = viewModel.isWhisperReady &&
                            viewModel.selectedFileName != null &&
                            !viewModel.isTranscribing,
                        modifier = Modifier.weight(1f)
                    ) { Text("Streaming") }
                }

                if (viewModel.isTranscribing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
                }
            }
        }

        viewModel.fileError?.let { ErrorCard(it) }

        ResultCard(
            title = "Transcription",
            text = viewModel.transcriptionResult,
            placeholder = "Transcription will appear here."
        )
    }
}

@Composable
private fun MicTranscriptionTab(viewModel: EmbeddedAudioTranscriptionViewModel) {
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.startMic(context)
        } else {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { viewModel.prepareNemotron() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModelStatusCard(
            title = "Nemotron model",
            ready = viewModel.isNemotronReady,
            status = viewModel.nemotronStatus,
            showProgress = viewModel.showNemotronProgress,
            progress = viewModel.nemotronProgress
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!viewModel.isListening) {
                    Button(
                        onClick = {
                            if (hasAudioPermission) {
                                viewModel.startMic(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = viewModel.isNemotronReady,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start listening")
                    }
                } else {
                    Button(
                        onClick = { viewModel.stopMic() },
                        enabled = !viewModel.isStopping,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.isStopping) "Stopping…" else "Stop")
                    }
                }
            }
        }

        viewModel.micError?.let { ErrorCard(it) }

        ResultCard(
            title = "Live transcript",
            text = viewModel.micTranscript,
            placeholder = "Speak after starting to see the transcript."
        )
    }
}

@Composable
private fun ModelStatusCard(
    title: String,
    ready: Boolean,
    status: String,
    showProgress: Boolean,
    progress: Float
) {
    val progressPercent = progress.coerceIn(0f, 100f)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Color(0xFFE8F5E9) else Color.White
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (ready) "Ready" else status.ifBlank { "Preparing…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showProgress && progress > 0f) {
                    Text(
                        "${progressPercent.roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showProgress) {
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Primary
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Primary)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, text: String, placeholder: String) {
    val displayedText = text.trim().ifBlank { placeholder }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                displayedText,
                color = if (text.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = if (text.isBlank()) TextAlign.Start else TextAlign.Justify,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            color = Color(0xFFB71C1C),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
