package com.microsoft.foundrylocal.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** All capabilities follow model -> parameters -> input -> action -> result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabPlayground(
    state: LabUiState,
    viewModel: LabViewModel,
    mode: LabInputMode,
    onModeChange: (LabInputMode) -> Unit,
    onChooseModel: (LabInputMode) -> Unit,
    onReconnect: () -> Unit,
    onDownload: () -> Unit,
    onPickFile: () -> Unit,
    onStartMicrophone: () -> Unit
) {
    var streamFile by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val working = state.isCapabilityRunning
    val matchesModel = mode.accepts(state.selectedModel)
    val ready = matchesModel && state.isConnected && !state.isBusy &&
        if (mode == LabInputMode.TEXT) state.isChatReady else state.isAudioReady
    val valid = when (mode) {
        LabInputMode.TEXT -> state.chatParameters.errors.isEmpty()
        LabInputMode.FILE -> state.audioParameters.errors.isEmpty()
        LabInputMode.MICROPHONE -> true
    }
    val hasInput = when (mode) {
        LabInputMode.TEXT -> state.chatInput.isNotBlank()
        LabInputMode.FILE -> state.selectedAudioFileName.isNotBlank()
        LabInputMode.MICROPHONE -> true
    }
    val transcript = state.streamingPartialText.ifBlank { state.transcriptionText }
    val resultText = if (mode == LabInputMode.TEXT) {
        state.messages.lastOrNull { it.role == LabMessageRole.ASSISTANT }?.content.orEmpty()
    } else transcript
    val error = if (mode == LabInputMode.TEXT) state.chatError else state.audioError
    val duration = if (mode == LabInputMode.TEXT) state.lastInferenceDurationMs else state.audioDurationMs
    val run = {
        focus.clearFocus()
        when (mode) {
            LabInputMode.TEXT -> viewModel.runPrompt(state.chatInput)
            LabInputMode.FILE -> viewModel.transcribeFile(streamFile)
            LabInputMode.MICROPHONE -> onStartMicrophone()
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item("mode") {
                Text("Playground", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LabText)
                Text("Choose an input. Tune the model. Run on device.", color = LabMuted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabInputMode.values().forEach { input ->
                        FilterChip(
                            selected = mode == input,
                            onClick = { focus.clearFocus(); onModeChange(input) },
                            enabled = !working && !state.isBusy,
                            label = { Text(input.label) }
                        )
                    }
                }
            }
            item("model") {
                PlaygroundCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Model", color = LabMuted, style = MaterialTheme.typography.labelMedium)
                            Text(state.selectedModel?.alias ?: "Choose a model", color = LabText, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { onChooseModel(mode) }, enabled = !working && !state.isBusy) {
                            Text("Change")
                        }
                    }
                    val status = when {
                        !state.isConnected -> "Connect to Foundry Local to continue."
                        state.isBusy -> state.operationLabel ?: "Preparing model…"
                        !matchesModel -> mode.modelHint
                        ready -> "Ready · ${mode.label}"
                        state.isDownloaded -> "Downloaded · load to run"
                        else -> "Download required · ${state.selectedModel?.fileSizeMb ?: 0} MB"
                    }
                    Text(status, color = if (ready) LabGreen else LabAmber, style = MaterialTheme.typography.bodySmall)
                    when {
                        state.isBusy -> TextButton(onClick = viewModel::cancelModelOperation) { Text("Cancel operation") }
                        !state.isConnected -> OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
                        !matchesModel -> OutlinedButton(onClick = { onChooseModel(mode) }, enabled = !working) { Text("Choose compatible model") }
                        !ready -> OutlinedButton(
                            onClick = if (state.isDownloaded) viewModel::loadModel else onDownload,
                            enabled = !working
                        ) { Text(if (state.isDownloaded) "Load model" else "Download model") }
                    }
                }
            }
            item("parameters") {
                PlaygroundParameters(state, mode, viewModel, enabled = !working && !state.isBusy)
            }
            item("input") {
                PlaygroundCard {
                    Text("Input", color = LabText, fontWeight = FontWeight.SemiBold)
                    when (mode) {
                        LabInputMode.TEXT -> {
                            OutlinedTextField(
                                value = state.chatInput,
                                onValueChange = viewModel::updateChatInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Prompt") },
                                placeholder = { Text("What would you like to try?") },
                                minLines = 2,
                                maxLines = 6,
                                enabled = !working && !state.isBusy,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (ready && valid && hasInput && !working) run() }),
                                shape = RoundedCornerShape(12.dp)
                            )
                            TextButton(
                                onClick = { viewModel.updateChatInput("Explain why local inference matters in two sentences.") },
                                enabled = !working && !state.isBusy
                            ) { Text("Try an example") }
                        }
                        LabInputMode.FILE -> {
                            Text(state.selectedAudioFileName.ifBlank { "Choose an audio file from this device." }, color = LabMuted)
                            OutlinedButton(onClick = onPickFile, enabled = !working && !state.isBusy) {
                                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.selectedAudioFileName.isBlank()) "Choose audio file" else "Replace file")
                            }
                            Text("Result delivery", color = LabMuted, style = MaterialTheme.typography.labelMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(false to "When complete", true to "Stream").forEach { (stream, label) ->
                                    FilterChip(selected = streamFile == stream, onClick = { streamFile = stream }, enabled = !working, label = { Text(label) })
                                }
                            }
                        }
                        LabInputMode.MICROPHONE -> {
                            Text(
                                if (state.isListening) "Listening. Stop to finalize the transcript."
                                else "Use your microphone to transcribe speech. Recording starts when you press Run on device.",
                                color = LabMuted
                            )
                        }
                    }
                }
            }
            item("results-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Results", color = LabText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (duration != null && !working) Text("${duration} ms", color = LabMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (resultText.isNotBlank() || error != null || state.messages.isNotEmpty() && mode == LabInputMode.TEXT) {
                        TextButton(onClick = {
                            if (mode == LabInputMode.TEXT) viewModel.clearConversation() else viewModel.clearAudioResult()
                        }, enabled = !working) { Text("Clear") }
                    }
                }
                if (error != null) Text(error, color = Color(0xFFFF8A8A))
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LabAccent)
                        Text(if (state.isAudioFilePreparing) "Opening file…" else "Running on device…", color = LabMuted)
                    }
                } else if (resultText.isBlank() && error == null) {
                    Text("Your response or transcript will appear here.", color = LabMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (mode == LabInputMode.TEXT) {
                itemsIndexed(state.messages, key = { index, _ -> "message-$index" }) { _, message ->
                    PlaygroundCard {
                        Text(if (message.role == LabMessageRole.USER) "You" else "Model", color = LabAccent, style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(message.content.ifBlank { "…" }, color = LabText) }
                    }
                }
            } else if (transcript.isNotBlank()) {
                item("transcript") { PlaygroundCard { SelectionContainer { Text(transcript, color = LabText) } } }
            }
            if (resultText.isNotBlank() && !working) {
                item("result-actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(resultText)) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy result")
                        }
                        if (mode != LabInputMode.TEXT) {
                            OutlinedButton(onClick = {
                                viewModel.updateChatInput(transcript)
                                onModeChange(LabInputMode.TEXT)
                            }) { Text("Use in text prompt") }
                        }
                    }
                }
            }
        }
        Surface(color = LabBackground) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                HorizontalDivider(color = LabLine)
                Spacer(Modifier.height(8.dp))
                if (!valid) Text("Check the parameters above to continue.", color = LabAmber, style = MaterialTheme.typography.bodySmall)
                val stoppable = state.isGenerating || state.isTranscribing || state.isListening
                Button(
                    onClick = {
                        when {
                            state.isGenerating -> viewModel.cancelGeneration()
                            state.isTranscribing -> viewModel.cancelTranscription()
                            state.isListening -> viewModel.stopLiveTranscription()
                            else -> run()
                        }
                    },
                    enabled = stoppable || ready && valid && hasInput && !working,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (stoppable) Color(0xFF8F2D35) else LabAccent, contentColor = if (stoppable) Color.White else Color(0xFF00242C)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(if (stoppable) Icons.Rounded.Stop else if (mode == LabInputMode.MICROPHONE) Icons.Rounded.Mic else Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        state.isListening -> "Stop and finalize"
                        state.isTranscribing -> "Stop transcription"
                        state.isGenerating -> "Stop generation"
                        else -> "Run on device"
                    }, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlaygroundCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = LabSurfaceRaised) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun PlaygroundParameters(state: LabUiState, mode: LabInputMode, viewModel: LabViewModel, enabled: Boolean) {
    var expanded by rememberSaveable(mode) { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val summary = when (mode) {
        LabInputMode.TEXT -> "Temperature ${String.format(Locale.US, "%.1f", state.temperature)} · ${state.maxTokens} tokens"
        LabInputMode.FILE -> "Language: ${state.audioParameters.language.ifBlank { "model default" }} · Temperature: ${state.audioParameters.temperature.ifBlank { "default" }}"
        LabInputMode.MICROPHONE -> "English · 16 kHz mono"
    }
    PlaygroundCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Parameters", color = LabText, fontWeight = FontWeight.SemiBold)
                Text(summary, color = LabMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse parameters" else "Expand parameters")
        }
        if (expanded) {
            when (mode) {
                LabInputMode.TEXT -> {
                    OutlinedTextField(state.systemPrompt, viewModel::updateSystemPrompt, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("System instruction") }, minLines = 2, maxLines = 5, shape = RoundedCornerShape(12.dp))
                    Text("Temperature · ${String.format(Locale.US, "%.1f", state.temperature)}", color = LabText)
                    Slider(value = state.temperature, onValueChange = { viewModel.updateTemperature((it * 10).roundToInt() / 10f) },
                        enabled = enabled, valueRange = 0f..1f, steps = 9)
                    val limit = state.selectedModel?.maxOutputTokens?.takeIf { it >= 64 } ?: 2048
                    Text("Maximum output · ${state.maxTokens} tokens", color = LabText)
                    Slider(value = state.maxTokens.coerceIn(64, limit).toFloat(), enabled = enabled,
                        onValueChange = { viewModel.updateMaxTokens(((it / 64f).roundToInt() * 64).coerceIn(64, limit)) }, valueRange = 64f..limit.toFloat())
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
                    if (advanced) {
                        Text("Leave blank to use model defaults. Option support and ranges can vary by model.", color = LabMuted, style = MaterialTheme.typography.bodySmall)
                        val p = state.chatParameters
                        ParameterField("Top P", p.topP, { viewModel.updateChatParameters(p.copy(topP = it)) }, enabled, p.errors["Top P"], "Optional · 0 to 1")
                        ParameterField("Top K", p.topK, { viewModel.updateChatParameters(p.copy(topK = it)) }, enabled, p.errors["Top K"], "Optional · positive integer")
                        ParameterField("Presence penalty", p.presencePenalty, { viewModel.updateChatParameters(p.copy(presencePenalty = it)) }, enabled, p.errors["Presence penalty"], "Optional · model default")
                        ParameterField("Frequency penalty", p.frequencyPenalty, { viewModel.updateChatParameters(p.copy(frequencyPenalty = it)) }, enabled, p.errors["Frequency penalty"], "Optional · model default")
                        OutlinedTextField(p.stopSequences, { viewModel.updateChatParameters(p.copy(stopSequences = it)) }, Modifier.fillMaxWidth(),
                            enabled = enabled, label = { Text("Stop sequences") }, supportingText = { Text("Optional · one sequence per line") },
                            minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp))
                    }
                }
                LabInputMode.FILE -> {
                    val p = state.audioParameters
                    ParameterField("Language", p.language, { viewModel.updateAudioParameters(p.copy(language = it)) }, enabled, p.errors["Language"], "Optional · en, ur, or model default", numeric = false)
                    ParameterField("Temperature", p.temperature, { viewModel.updateAudioParameters(p.copy(temperature = it)) }, enabled, p.errors["Temperature"], "Optional · model default")
                    Text("The selected speech model determines supported languages and temperature range.", color = LabMuted, style = MaterialTheme.typography.bodySmall)
                }
                LabInputMode.MICROPHONE -> {
                    Text("English speech", color = LabText, fontWeight = FontWeight.SemiBold)
                    Text("The current live speech model uses English. The recording format is fixed to 16 kHz, mono, 16-bit PCM.", color = LabMuted)
                    Text("Live transcription has no temperature or output-token setting.", color = LabMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (mode != LabInputMode.MICROPHONE) TextButton(onClick = { viewModel.resetPlaygroundParameters(mode) }, enabled = enabled) { Text("Reset parameters") }
        }
    }
}

@Composable
private fun ParameterField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?, hint: String, numeric: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(label) }, placeholder = { Text(hint) }, isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(12.dp))
}
