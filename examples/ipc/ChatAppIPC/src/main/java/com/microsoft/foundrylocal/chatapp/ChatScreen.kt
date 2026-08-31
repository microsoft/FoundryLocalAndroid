/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.chatapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val context = LocalContext.current as ComponentActivity
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val backgroundColor = Color(0xFFF8FAFC)
    val needsNotificationPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var hasNotificationPermission by remember {
        mutableStateOf(
            !needsNotificationPermission ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasNotificationPermission = granted
            if (!granted) {
                Toast.makeText(
                    context,
                    "Notification permission is required to continue setup",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Audio permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasAudioPermission = granted
            if (granted) {
                viewModel.startVoiceInput(context)
            } else {
                Toast.makeText(
                    context,
                    "Microphone permission is required for voice input",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    LaunchedEffect(needsNotificationPermission, hasNotificationPermission) {
        if (needsNotificationPermission && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(viewModel.chatMessages.size) {
        if (viewModel.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.chatMessages.size - 1)
        }
    }

    // Trigger voice setup once when chat model is loaded
    LaunchedEffect(viewModel.isLoaded) {
        if (viewModel.isLoaded) {
            viewModel.startVoiceSetupIfNeeded(context)
        }
    }

    Scaffold(
        containerColor = backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(paddingValues)
        ) {
            if (!viewModel.isFullyReady) {
                GradientHeader()

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!hasNotificationPermission && needsNotificationPermission) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    NotificationPermissionPrompt()
                }
            } else if (!viewModel.isFullyReady) {
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ChatArea(
                        viewModel = viewModel,
                        listState = listState,
                        keyboardController = keyboardController,
                        onMicTap = {
                            if (hasAudioPermission) {
                                viewModel.startVoiceInput(context)
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GradientHeader() {
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
                text = "On-Device AI Chat",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
                            )
                            Text(
                text = "A simple app to demonstrate Foundry Local Android APIs for developing an on-device AI chatbot.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE0E7FF)
            )
        }
    }
}

private enum class StepState {
    Complete,
    Active,
    Inactive
}

@Composable
private fun SetupFlow(
    viewModel: ChatViewModel,
    context: ComponentActivity,
    hasNotificationPermission: Boolean
) {
    // Auto-trigger step 1 when screen loads
    var hasTriggeredConnect by remember { mutableStateOf(false) }
    LaunchedEffect(hasNotificationPermission) {
        if (hasNotificationPermission &&
            !viewModel.isConnected &&
            !hasTriggeredConnect
        ) {
            hasTriggeredConnect = true
            viewModel.connect(context)
        }
    }

    // Auto-trigger step 2 when step 1 completes (only once)
    LaunchedEffect(hasNotificationPermission, viewModel.isConnected) {
        if (hasNotificationPermission &&
            viewModel.isConnected &&
            (!viewModel.isDownloaded || !viewModel.isVoiceModelDownloaded) &&
            !viewModel.showProgress &&
            !viewModel.showVoiceProgress
        ) {
            // Small delay to ensure step 1 is fully complete
            delay(100)
            if (!viewModel.isDownloaded || !viewModel.isVoiceModelDownloaded) {
                viewModel.downloadModel(context)
            }
        }
    }

    // Auto-trigger step 3 when both downloads complete (only once)
    LaunchedEffect(hasNotificationPermission, viewModel.isDownloaded, viewModel.isVoiceModelDownloaded, viewModel.allowAutoLoad) {
        if (hasNotificationPermission &&
            viewModel.isDownloaded &&
            viewModel.isVoiceModelDownloaded &&
            !viewModel.isLoaded &&
            !viewModel.showProgress &&
            viewModel.allowAutoLoad
        ) {
            // Small delay to ensure step 2 is fully complete
            delay(100)
            if (!viewModel.isLoaded && !viewModel.showProgress && viewModel.allowAutoLoad) {
                viewModel.loadModel()
            }
        }
    }

    val step1State = if (viewModel.isConnected) StepState.Complete else StepState.Active
    val step2State = when {
        viewModel.isDownloaded && viewModel.isVoiceModelDownloaded -> StepState.Complete
        viewModel.isDownloaded -> StepState.Active // chat done, voice still downloading
        viewModel.isConnected -> StepState.Active
        else -> StepState.Inactive
    }
    val step3State = when {
        viewModel.isLoaded -> StepState.Complete
        viewModel.isConnected && viewModel.isDownloaded && viewModel.isVoiceModelDownloaded -> StepState.Active
        else -> StepState.Inactive
    }
    val step4State = when {
        viewModel.isVoiceInputReady -> StepState.Complete
        viewModel.isLoaded && viewModel.isVoiceModelDownloaded -> StepState.Active
        else -> StepState.Inactive
    }

    val isStep2InProgress = (viewModel.showProgress && !viewModel.isDownloaded) ||
        (viewModel.showVoiceProgress && !viewModel.isVoiceModelDownloaded)
    val isStep3InProgress = viewModel.showProgress && viewModel.isDownloaded && !viewModel.isLoaded
    val isStep4InProgress = (viewModel.showVoiceProgress || viewModel.isVoiceModelLoaded) &&
        viewModel.isLoaded && !viewModel.isVoiceInputReady

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                            Text(
                                text = "Getting Started",
                                style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "Complete the setup steps to start chatting",
                                style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B)
                )
            }
        }
        item {
            SetupStepCard(
                stepNumber = 1,
                title = "Connect to Service",
                description = when {
                    viewModel.isConnected -> "Service connection established"
                    viewModel.setupError != null ->
                        "${viewModel.setupError}. Tap to reconnect"
                    else -> "Connecting to service..."
                },
                state = step1State,
                onClick = if (!viewModel.isConnected && viewModel.setupError != null) {
                    { viewModel.connect(context) }
                } else {
                    null
                }
            )
        }
        item {
            SetupStepCard(
                stepNumber = 2,
                title = "Download AI Models",
                description = when {
                    viewModel.isDownloaded && viewModel.isVoiceModelDownloaded -> "All model files ready"
                    viewModel.isDownloaded && viewModel.showVoiceProgress -> "Voice model downloading..."
                    isStep2InProgress -> "Downloading models..."
                    viewModel.isDownloaded && viewModel.voiceError != null ->
                        "Voice download error: ${viewModel.voiceError}. Tap to retry"
                    viewModel.setupError != null -> "Error: ${viewModel.setupError}"
                    step2State == StepState.Active -> "Tap to download models"
                    else -> "Waiting for service connection"
                },
                state = step2State,
                showProgress = isStep2InProgress,
                progressPercent = if (!viewModel.isDownloaded) viewModel.progressPercent
                    else viewModel.voiceProgressPercent,
                onClick = if (step2State == StepState.Active &&
                    !viewModel.showProgress && !viewModel.showVoiceProgress) {
                    { viewModel.downloadModel(context) }
                } else null
            )
        }
        item {
            SetupStepCard(
                stepNumber = 3,
                title = "Load Chat Model",
                description = when {
                    viewModel.isLoaded -> "Chat model loaded and ready"
                    isStep3InProgress -> "Loading chat model into memory..."
                    step3State == StepState.Active && viewModel.setupError != null -> "Error: ${viewModel.setupError}"
                    step3State == StepState.Active -> "Tap to load chat model"
                    else -> "Available after downloads complete"
                },
                state = step3State,
                showProgress = isStep3InProgress,
                progressPercent = viewModel.progressPercent,
                onClick = if (step3State == StepState.Active && !viewModel.showProgress) {
                    { viewModel.loadModel() }
                } else null
            )
        }
        item {
            SetupStepCard(
                stepNumber = 4,
                title = "Prepare Voice Input",
                description = when {
                    viewModel.isVoiceInputReady -> "Voice input ready"
                    isStep4InProgress -> "Loading voice model..."
                    viewModel.voiceError != null -> "Error: ${viewModel.voiceError}. Tap to retry"
                    step4State == StepState.Active -> "Preparing voice recognition..."
                    else -> "Available after chat model loads"
                },
                state = step4State,
                showProgress = isStep4InProgress,
                progressPercent = viewModel.voiceProgressPercent,
                onClick = if (viewModel.voiceError != null && step4State == StepState.Active) {
                    { viewModel.retryVoiceSetup(context) }
                } else null
            )
        }
    }
}

@Composable
private fun NotificationPermissionPrompt() {
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
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Notification permission is required to complete model download.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
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
    val cardModifier = Modifier.fillMaxWidth()
    val cardColors = CardDefaults.cardColors(containerColor = Color.White)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = 6.dp)

    if (onClick != null) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = cardColors,
            elevation = cardElevation,
            modifier = cardModifier,
            onClick = onClick
        ) {
            SetupStepCardContent(
                stepNumber = stepNumber,
                title = title,
                description = description,
                state = state,
                showProgress = showProgress,
                progressPercent = progressPercent
            )
        }
    } else {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = cardColors,
            elevation = cardElevation,
            modifier = cardModifier
        ) {
            SetupStepCardContent(
                stepNumber = stepNumber,
                title = title,
                description = description,
                state = state,
                showProgress = showProgress,
                progressPercent = progressPercent
            )
        }
    }
}

@Composable
private fun SetupStepCardContent(
    stepNumber: Int,
    title: String,
    description: String,
    state: StepState,
    showProgress: Boolean,
    progressPercent: Float
) {
    Row(
        modifier = Modifier.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val iconBackground = when (state) {
            StepState.Complete -> Color(0xFF22C55E)
            StepState.Active -> Color(0xFF8B5CF6)
            StepState.Inactive -> Color(0xFFE2E8F0)
        }
        val iconContentColor = if (state == StepState.Inactive) Color(0xFF94A3B8) else Color.White

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
                color = Color(0xFF94A3B8)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0F172A)
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
            if (showProgress) {
                ProgressRow(progressPercent = progressPercent)
            }
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
                .background(Color(0xFFE2E8F0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressValue)
                    .background(Color(0xFF8B5CF6))
            )
        }
        Text(
            text = "${(progressValue * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8B5CF6)
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
                .background(Color(0xFFF8FAFC)),
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
            text = "Complete setup to start chatting",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B)
        )
    }
}

@Composable
private fun FooterLogoGrid() {
    val logoColors = listOf(
        Color(0xFFF25022),
        Color(0xFF7FBA00),
        Color(0xFF00A4EF),
        Color(0xFFFFB900)
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FooterLogoSquare(logoColors[0])
            FooterLogoSquare(logoColors[1])
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FooterLogoSquare(logoColors[2])
            FooterLogoSquare(logoColors[3])
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
private fun ChatArea(
    viewModel: ChatViewModel,
    listState: LazyListState,
    keyboardController: SoftwareKeyboardController?,
    onMicTap: () -> Unit
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
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.returnToSetup() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back to setup",
                            tint = Color(0xFF6366F1)
                        )
                    }
                    Text(
                        text = "Chat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }

                Divider(color = Color(0xFFE2E8F0))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (viewModel.chatMessages.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                text = "👋",
                                    style = MaterialTheme.typography.displayMedium
                                )
                                Text(
                                text = "Start a conversation",
                                    style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF0F172A)
                                )
                                Text(
                                text = "Ask me anything!",
                                    style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8)
                                )
                        }
                        }
                    }

                    items(viewModel.chatMessages) { message ->
                        ChatBubble(message)
                    }
                }

            Divider(color = Color(0xFFE2E8F0))

                    // Live transcript display when listening
                    if (viewModel.isListening && viewModel.voiceTranscript.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Text(
                                text = viewModel.voiceTranscript,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF92400E),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (viewModel.isListening) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEE2E2))
                        ) {
                            Text(
                                text = "🎙️ Listening… speak now",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF991B1B)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Mic / Stop button
                        if (viewModel.isListening) {
                            IconButton(
                                onClick = { viewModel.stopVoiceInput() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop listening",
                                    tint = Color(0xFFDC2626)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onMicTap,
                                enabled = viewModel.isVoiceInputReady
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = when {
                                        viewModel.isVoiceInputReady -> "Voice input"
                                        viewModel.voiceError != null -> "Voice unavailable: ${viewModel.voiceError}"
                                        else -> "Voice input preparing"
                                    },
                                    tint = when {
                                        viewModel.isVoiceInputReady -> Color(0xFF6366F1)
                                        viewModel.voiceError != null -> Color(0xFFEF4444)
                                        else -> Color(0xFFCBD5E1)
                                    }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = viewModel.inputText,
                            onValueChange = { viewModel.inputText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Type a message...") },
                            maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                        )

                Button(
                            onClick = {
                                viewModel.sendMessage()
                                keyboardController?.hide()
                            },
                            enabled = viewModel.inputText.isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6366F1),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFDADBF8),
                        disabledContentColor = Color.White
                    ),
                    modifier = Modifier.height(56.dp)
                        ) {
                            Text(
                        text = "Send",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessageUI) {
    val bubbleColor = if (message.isUser) Color(0xFF6366F1) else Color(0xFFF8FAFC)
    val contentColor = if (message.isUser) Color.White else Color(0xFF0F172A)
    val border = if (message.isUser) null else BorderStroke(1.dp, Color(0xFFE2E8F0))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (message.isUser) 20.dp else 8.dp,
                bottomEnd = if (message.isUser) 8.dp else 20.dp
            ),
            color = bubbleColor,
            contentColor = contentColor,
            border = border,
            tonalElevation = if (message.isUser) 4.dp else 0.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
