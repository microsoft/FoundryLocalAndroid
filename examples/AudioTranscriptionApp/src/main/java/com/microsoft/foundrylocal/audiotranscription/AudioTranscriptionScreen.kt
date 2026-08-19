/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.audiotranscription

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

// Color palette (matches SimpleChatApp)
private val Indigo = Color(0xFF6366F1)
private val Purple = Color(0xFF8B5CF6)
private val Green = Color(0xFF22C55E)
private val SlateLight = Color(0xFFF8FAFC)
private val SlateBorder = Color(0xFFE2E8F0)
private val SlateMuted = Color(0xFF94A3B8)
private val SlateSubtle = Color(0xFF64748B)
private val SlateDark = Color(0xFF0F172A)
private val Red = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTranscriptionScreen(viewModel: AudioTranscriptionViewModel = viewModel()) {
    val context = LocalContext.current as? ComponentActivity ?: return

    // --- Permissions ---
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasAudioPermission = granted
            if (!granted) {
                Toast.makeText(context, "Microphone permission is required for real-time transcription", Toast.LENGTH_LONG).show()
            }
        }

    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var hasNotificationPermission by remember {
        mutableStateOf(
            !needsNotificationPermission ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                Toast.makeText(context, "Notification permission is required to continue setup", Toast.LENGTH_LONG).show()
            }
        }

    LaunchedEffect(needsNotificationPermission, hasNotificationPermission) {
        if (needsNotificationPermission && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // File picker
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some providers don't support persistable permissions
                }
                val fileName = getFileName(context, uri)
                viewModel.onAudioFilePicked(uri, fileName)
            }
        }

    // Determine whether we're in setup phase or ready phase
    val isSetupComplete = viewModel.isConnected &&
        (viewModel.isWhisperLoaded || viewModel.isNemotronLoaded)

    // Mode selection (persisted across setup/ready transitions)
    var selectedMode by remember { mutableStateOf(0) } // 0 = File, 1 = Real-Time

    Scaffold(containerColor = SlateLight) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateLight)
                .padding(paddingValues)
        ) {
            if (!isSetupComplete) {
                GradientHeader()
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!hasNotificationPermission && needsNotificationPermission) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    NotificationPermissionPrompt(
                        onOpenSettings = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).apply {
                                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            } else if (!isSetupComplete) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    SetupFlow(
                        viewModel = viewModel,
                        context = context,
                        hasNotificationPermission = hasNotificationPermission || !needsNotificationPermission
                    )
                }
                SetupFooter()
            } else {
                // Ready — show transcription UI
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    TranscriptionArea(
                        viewModel = viewModel,
                        context = context,
                        selectedMode = selectedMode,
                        onModeChange = { selectedMode = it },
                        hasAudioPermission = hasAudioPermission,
                        requestAudioPermission = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        filePickerLauncher = filePickerLauncher
                    )
                }
            }
        }
    }
}

// ==================== Header ====================

@Composable
private fun GradientHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(colors = listOf(Indigo, Purple)),
                shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "On-Device Audio",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Speech-to-text powered by Foundry Local. Transcribe audio files with Whisper or stream from the microphone with Nemotron.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE0E7FF)
            )
        }
    }
}

// ==================== Setup Flow (matches SimpleChatApp) ====================

private enum class StepState { Complete, Active, Inactive }

@Composable
private fun SetupFlow(
    viewModel: AudioTranscriptionViewModel,
    context: ComponentActivity,
    hasNotificationPermission: Boolean
) {
    // Auto-trigger step 1: connect
    var hasTriggeredConnect by remember { mutableStateOf(false) }
    LaunchedEffect(hasNotificationPermission) {
        if (hasNotificationPermission && !viewModel.isConnected && !hasTriggeredConnect) {
            hasTriggeredConnect = true
            viewModel.connect(context)
        }
    }

    // Auto-trigger step 2: download Whisper (primary model)
    LaunchedEffect(hasNotificationPermission, viewModel.isConnected) {
        if (hasNotificationPermission &&
            viewModel.isConnected &&
            !viewModel.isWhisperDownloaded &&
            !viewModel.showWhisperProgress
        ) {
            delay(100)
            if (!viewModel.isWhisperDownloaded && !viewModel.showWhisperProgress) {
                viewModel.downloadWhisperModel()
            }
        }
    }

    // Auto-trigger step 3: load Whisper
    LaunchedEffect(
        hasNotificationPermission,
        viewModel.isWhisperDownloaded,
        viewModel.allowAutoLoad
    ) {
        if (hasNotificationPermission &&
            viewModel.isWhisperDownloaded &&
            !viewModel.isWhisperLoaded &&
            !viewModel.showWhisperProgress &&
            viewModel.allowAutoLoad
        ) {
            delay(100)
            if (!viewModel.isWhisperLoaded &&
                !viewModel.showWhisperProgress &&
                viewModel.allowAutoLoad
            ) {
                viewModel.loadWhisperModel()
            }
        }
    }

    val step1State = if (viewModel.isConnected) StepState.Complete else StepState.Active
    val step2State = when {
        viewModel.isWhisperDownloaded -> StepState.Complete
        viewModel.isConnected -> StepState.Active
        else -> StepState.Inactive
    }
    val step3State = when {
        viewModel.isWhisperLoaded -> StepState.Complete
        viewModel.isConnected && viewModel.isWhisperDownloaded -> StepState.Active
        else -> StepState.Inactive
    }

    val isStep2InProgress = viewModel.showWhisperProgress && !viewModel.isWhisperDownloaded
    val isStep3InProgress = viewModel.showWhisperProgress && viewModel.isWhisperDownloaded && !viewModel.isWhisperLoaded

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Getting Started",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = SlateDark
                )
                Text(
                    text = "Complete the setup steps to start transcribing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateSubtle
                )
            }
        }
        item {
            SetupStepCard(
                stepNumber = 1,
                title = "Connect to Service",
                description = when {
                    viewModel.isConnected -> "Service connection established"
                    else -> "Connecting to service..."
                },
                state = step1State
            )
        }
        item {
            SetupStepCard(
                stepNumber = 2,
                title = "Download AI Model",
                description = when {
                    viewModel.isWhisperDownloaded -> "Whisper model files ready"
                    isStep2InProgress -> "Downloading openai-whisper-tiny"
                    viewModel.isStatusError -> viewModel.statusMessage
                    else -> "Download required"
                },
                state = step2State,
                showProgress = isStep2InProgress,
                progressPercent = viewModel.whisperProgress
            )
        }
        item {
            SetupStepCard(
                stepNumber = 3,
                title = "Load Model",
                description = when {
                    viewModel.isWhisperLoaded -> "Model loaded and ready"
                    isStep3InProgress -> "Loading model into memory..."
                    else -> "Tap to load model"
                },
                state = step3State,
                showProgress = isStep3InProgress,
                progressPercent = viewModel.whisperProgress,
                onClick = if (step3State == StepState.Active && !viewModel.showWhisperProgress) {
                    { viewModel.loadWhisperModel() }
                } else null
            )
        }
    }
}

@Composable
private fun NotificationPermissionPrompt(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enable Notifications",
                style = MaterialTheme.typography.titleLarge,
                color = SlateDark,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Notification permission is required to complete model download.",
                style = MaterialTheme.typography.bodyMedium,
                color = SlateSubtle,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    state: StepState,
    showProgress: Boolean = false,
    progressPercent: Float = 0f,
    onClick: (() -> Unit)? = null
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardColors = CardDefaults.cardColors(containerColor = Color.White)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    val cardModifier = Modifier.fillMaxWidth()

    val cardContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val iconBackground = when (state) {
                StepState.Complete -> Green
                StepState.Active -> Purple
                StepState.Inactive -> SlateBorder
            }
            val iconContentColor = if (state == StepState.Inactive) SlateMuted else Color.White

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                if (state == StepState.Complete) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = iconContentColor
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "STEP $stepNumber",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateMuted
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateDark
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateSubtle
                    )
                }
                if (showProgress) {
                    ProgressRow(progressPercent = progressPercent)
                }
            }
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
            modifier = cardModifier,
            content = cardContent
        )
    } else {
        Card(
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
            modifier = cardModifier,
            content = cardContent
        )
    }
}

@Composable
private fun SetupFooter() {
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
                .background(SlateLight),
            contentAlignment = Alignment.Center
        ) {
            FooterLogoGrid()
        }
        Text(
            text = "Foundry Local Sample",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B)
        )
        Text(
            text = "Complete setup to start transcribing",
            style = MaterialTheme.typography.bodySmall,
            color = SlateSubtle
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

// ==================== Transcription Area (post-setup) ====================

@Composable
private fun TranscriptionArea(
    viewModel: AudioTranscriptionViewModel,
    context: ComponentActivity,
    selectedMode: Int,
    onModeChange: (Int) -> Unit,
    hasAudioPermission: Boolean,
    requestAudioPermission: () -> Unit,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color.White,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with mode tabs
            TranscriptionTopBar(selectedMode, onModeChange)

            Divider(color = SlateBorder)

            // Content for selected mode
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (selectedMode == 0) {
                    // File transcription
                    item {
                        FileTranscriptionContent(viewModel, context, filePickerLauncher)
                    }
                    if (viewModel.statusMessage.isNotEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (viewModel.isStatusError) Color(0xFFFEF2F2) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = viewModel.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (viewModel.isStatusError) Color(0xFF991B1B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Real-time transcription
                    item {
                        RealTimeContent(
                            viewModel, context,
                            hasAudioPermission, requestAudioPermission
                        )
                    }
                    if (viewModel.statusMessage.isNotEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (viewModel.isStatusError) Color(0xFFFEF2F2) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = viewModel.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (viewModel.isStatusError) Color(0xFF991B1B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionTopBar(selectedMode: Int, onModeChange: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Transcription",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = SlateDark
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeTab(
                label = "📁  File",
                isSelected = selectedMode == 0,
                onClick = { onModeChange(0) },
                modifier = Modifier.weight(1f)
            )
            ModeTab(
                label = "🎙️  Real-Time",
                isSelected = selectedMode == 1,
                onClick = { onModeChange(1) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) Indigo else Color.Transparent,
        label = "tabColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else SlateSubtle,
        label = "tabContent"
    )
    val border = if (isSelected) null else BorderStroke(1.dp, SlateBorder)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
        modifier = modifier.height(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ==================== File Transcription Content ====================

@Composable
private fun FileTranscriptionContent(
    viewModel: AudioTranscriptionViewModel,
    context: ComponentActivity,
    filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Model status badge
        if (viewModel.isWhisperLoaded) {
            ModelStatusBadge(name = "Whisper", isReady = true)
        } else {
            ModelSetupInline(
                name = "Whisper",
                subtitle = "openai-whisper-tiny",
                isDownloaded = viewModel.isWhisperDownloaded,
                isLoaded = viewModel.isWhisperLoaded,
                showProgress = viewModel.showWhisperProgress,
                progress = viewModel.whisperProgress,
                onDownload = { viewModel.downloadWhisperModel() },
                onLoad = { viewModel.loadWhisperModel() }
            )
        }

        if (!viewModel.isWhisperLoaded) {
            // Placeholder when model isn't ready
            EmptyStateCard(
                emoji = "📁",
                title = "File Transcription",
                subtitle = "Download and load the Whisper model above to get started"
            )
            return
        }

        // File picker card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SlateLight,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select Audio File",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SlateDark
                )

                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.selectedAudioFileName.isEmpty()) Indigo
                            else Color(0xFFEEF2FF)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (viewModel.selectedAudioFileName.isNotEmpty())
                            "📎  ${viewModel.selectedAudioFileName}" else "Choose File...",
                        color = if (viewModel.selectedAudioFileName.isEmpty()) Color.White
                            else Indigo,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (viewModel.selectedAudioUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.transcribeFile(context) },
                            enabled = !viewModel.isTranscribing,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Indigo,
                                disabledContainerColor = Color(0xFFDADBF8),
                                disabledContentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("Transcribe", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.transcribeFileStreaming(context) },
                            enabled = !viewModel.isTranscribing,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.5.dp,
                                if (viewModel.isTranscribing) SlateBorder else Purple
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(
                                "Stream",
                                fontWeight = FontWeight.SemiBold,
                                color = if (viewModel.isTranscribing) SlateMuted else Purple
                            )
                        }
                    }
                }
            }
        }

        // Progress indicator
        if (viewModel.isTranscribing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Indigo,
                trackColor = SlateBorder
            )
        }

        // Streaming partial results
        if (viewModel.streamingPartialText.isNotEmpty()) {
            ResultCard(
                label = "Streaming...",
                text = viewModel.streamingPartialText,
                labelColor = Color(0xFFB45309),
                backgroundColor = Color(0xFFFFFBEB),
                textColor = Color(0xFF78350F)
            )
        }

        // Final result
        if (viewModel.transcriptionResult.isNotEmpty()) {
            ResultCard(
                label = "Transcription Result",
                text = viewModel.transcriptionResult,
                labelColor = Color(0xFF15803D),
                backgroundColor = Color(0xFFF0FDF4),
                textColor = Color(0xFF14532D)
            )
        }
    }
}

// ==================== Real-Time Content ====================

@Composable
private fun RealTimeContent(
    viewModel: AudioTranscriptionViewModel,
    context: ComponentActivity,
    hasAudioPermission: Boolean,
    requestAudioPermission: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Model status or setup
        if (viewModel.isNemotronLoaded) {
            ModelStatusBadge(name = "Nemotron", isReady = true)
        } else {
            ModelSetupInline(
                name = "Nemotron",
                subtitle = "nemotron-speech-streaming-en-0.6b",
                isDownloaded = viewModel.isNemotronDownloaded,
                isLoaded = viewModel.isNemotronLoaded,
                showProgress = viewModel.showNemotronProgress,
                progress = viewModel.nemotronProgress,
                onDownload = { viewModel.downloadNemotronModel() },
                onLoad = { viewModel.loadNemotronModel() }
            )
        }

        if (!viewModel.isNemotronLoaded) {
            EmptyStateCard(
                emoji = "🎙️",
                title = "Real-Time Transcription",
                subtitle = "Download and load the Nemotron model above to get started"
            )
            return
        }

        // Permission gate
        if (!hasAudioPermission) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFFBEB),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Microphone access required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF92400E)
                    )
                    Button(
                        onClick = requestAudioPermission,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                    ) {
                        Text("Grant Permission", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
            return
        }

        // Mic button area
        MicrophoneControl(viewModel, context)

        // Live/final transcript
        if (viewModel.realTimeTranscript.isNotEmpty()) {
            val isLive = viewModel.isRealTimeStreaming
            ResultCard(
                label = if (isLive) "Live Transcript" else "Final Transcript",
                text = viewModel.realTimeTranscript,
                labelColor = if (isLive) Indigo else Color(0xFF15803D),
                backgroundColor = if (isLive) Color(0xFFEEF2FF) else Color(0xFFF0FDF4),
                textColor = if (isLive) Color(0xFF3730A3) else Color(0xFF14532D)
            )
        }
    }
}

@Composable
private fun MicrophoneControl(
    viewModel: AudioTranscriptionViewModel,
    context: ComponentActivity
) {
    val isStreaming = viewModel.isRealTimeStreaming

    // Pulsing animation when recording
    val pulseScale = if (isStreaming) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        ).value
    } else 1f

    val buttonColor by animateColorAsState(
        targetValue = if (isStreaming) Red else Indigo,
        label = "micColor"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Big mic button
        val micDescription = if (isStreaming) "Stop recording" else "Start recording"
        Surface(
            onClick = {
                if (isStreaming) viewModel.stopRealTimeTranscription()
                else viewModel.startRealTimeTranscription(context)
            },
            shape = CircleShape,
            color = buttonColor,
            shadowElevation = if (isStreaming) 12.dp else 8.dp,
            modifier = Modifier
                .size(96.dp)
                .scale(pulseScale)
                .semantics {
                    contentDescription = micDescription
                    role = Role.Button
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = if (isStreaming) "⏹" else "🎙️",
                    style = MaterialTheme.typography.headlineLarge
                )
            }
        }

        Text(
            text = if (isStreaming) "Tap to stop recording" else "Tap to start recording",
            style = MaterialTheme.typography.bodyMedium,
            color = SlateSubtle
        )

        if (isStreaming) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Red,
                trackColor = Color(0xFFFEE2E2)
            )
        }
    }
}

// ==================== Shared Components ====================

@Composable
private fun ModelStatusBadge(name: String, isReady: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isReady) Color(0xFFF0FDF4) else SlateLight,
        border = BorderStroke(1.dp, if (isReady) Color(0xFFBBF7D0) else SlateBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Green,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "$name model ready",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF15803D)
            )
        }
    }
}

@Composable
private fun ModelSetupInline(
    name: String,
    subtitle: String,
    isDownloaded: Boolean,
    isLoaded: Boolean,
    showProgress: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onLoad: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SlateLight,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$name Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SlateDark
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SlateSubtle
                    )
                }
            }

            if (showProgress) {
                ProgressRow(progressPercent = progress)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloaded && !showProgress,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Indigo,
                        disabledContainerColor = SlateBorder
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isDownloaded) "✓ Downloaded" else "Download")
                }
                Button(
                    onClick = onLoad,
                    enabled = isDownloaded && !isLoaded && !showProgress,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        disabledContainerColor = SlateBorder
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Load")
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(emoji: String, title: String, subtitle: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SlateLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = emoji, style = MaterialTheme.typography.displayMedium)
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = SlateDark,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SlateMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ResultCard(
    label: String,
    text: String,
    labelColor: Color,
    backgroundColor: Color,
    textColor: Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
        }
    }
}

@Composable
private fun ProgressRow(progressPercent: Float) {
    val progressValue = progressPercent.coerceIn(0f, 100f) / 100f
    Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SlateBorder)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressValue)
                    .background(Purple)
            )
        }
        Text(
            text = "${(progressValue * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Purple
        )
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return name ?: uri.lastPathSegment ?: "Unknown file"
}
