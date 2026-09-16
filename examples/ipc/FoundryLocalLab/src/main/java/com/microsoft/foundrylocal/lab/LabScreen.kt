package com.microsoft.foundrylocal.lab

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

internal val LabBackground = Color(0xFF06141B)
internal val LabSurface = Color(0xFF0B1F28)
internal val LabSurfaceRaised = Color(0xFF102A35)
internal val LabLine = Color(0xFF244550)
internal val LabText = Color(0xFFE6F6FA)
internal val LabMuted = Color(0xFF9CB6BE)
internal val LabAccent = Color(0xFF67E8F9)
internal val LabGreen = Color(0xFF5EE6A8)
internal val LabAmber = Color(0xFFFFCD70)

private enum class LabDestination(val label: String, val icon: ImageVector) {
    MODELS("Models", Icons.AutoMirrored.Rounded.ViewList),
    PLAYGROUND("Playground", Icons.Rounded.AutoAwesome),
    RUNTIME("Runtime", Icons.Outlined.Terminal)
}

private enum class CatalogFilter(val label: String) {
    ALL("All"),
    CHAT("Chat"),
    AUDIO("Audio"),
    FILE("Audio file"),
    MICROPHONE("Microphone")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(viewModel: LabViewModel) {
    val context = LocalContext.current as ComponentActivity
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(LabDestination.MODELS) }
    var inputMode by rememberSaveable { mutableStateOf(LabInputMode.TEXT) }
    var catalogFilter by rememberSaveable { mutableStateOf(CatalogFilter.ALL) }
    var lastSelectedAlias by rememberSaveable { mutableStateOf("") }
    var modelPickerMode by rememberSaveable { mutableStateOf<LabInputMode?>(null) }
    LaunchedEffect(state.selectedModelAlias) {
        if (state.selectedModel != null && lastSelectedAlias != state.selectedModelAlias) {
            inputMode = LabInputMode.forModel(state.selectedModel)
            lastSelectedAlias = state.selectedModelAlias
        }
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) viewModel.downloadModel(context)
        else Toast.makeText(context, "Notification permission is required for model downloads", Toast.LENGTH_LONG).show()
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startLiveTranscription(context)
        else Toast.makeText(context, "Microphone permission is required for live transcription", Toast.LENGTH_LONG).show()
    }
    val audioFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.onAudioFilePicked(context, it, it.lastPathSegment ?: "selected_audio")
        }
    }

    LaunchedEffect(Unit) { viewModel.initialize(context) }
    LaunchedEffect(state.errorMessage, state.successMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessages()
        }
        state.successMessage?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessages()
        }
    }

    val requestDownload = {
        if (hasNotificationPermission) viewModel.downloadModel(context)
        else permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = LabBackground,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding())
        },
        topBar = { LabHeader(state) },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = Color(0xFF081A22),
                tonalElevation = 0.dp
            ) {
                LabDestination.values().forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LabAccent,
                            selectedTextColor = LabAccent,
                            indicatorColor = Color(0xFF123743),
                            unselectedIconColor = LabMuted,
                            unselectedTextColor = LabMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = destination,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "lab-destination",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) { current ->
            when (current) {
                LabDestination.MODELS -> ModelsWorkspace(
                    state = state,
                    filter = catalogFilter,
                    onFilterChange = { catalogFilter = it },
                    onRefresh = viewModel::refreshCatalog,
                    onReconnect = { viewModel.reconnect(context) },
                    onSelect = viewModel::selectModel,
                    onDownload = requestDownload,
                    onLoad = viewModel::loadModel,
                    onUnload = viewModel::unloadModel,
                    onRemoveCache = viewModel::removeModelFromCache,
                    onCancelOperation = viewModel::cancelModelOperation
                )

                LabDestination.PLAYGROUND -> LabPlayground(
                    state = state,
                    viewModel = viewModel,
                    mode = inputMode,
                    onModeChange = { inputMode = it },
                    onChooseModel = { modelPickerMode = it },
                    onReconnect = { viewModel.reconnect(context) },
                    onDownload = requestDownload,
                    onPickFile = { audioFileLauncher.launch(arrayOf("audio/*")) },
                    onStartMicrophone = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startLiveTranscription(context)
                        } else {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                LabDestination.RUNTIME -> RuntimeWorkspace(
                    state = state,
                    onRefresh = viewModel::refreshCatalog,
                    onClearEvents = viewModel::clearRuntimeEvents
                )
            }
        }
    }
    modelPickerMode?.let { mode ->
        CompatibleModelPicker(state, mode, onDismiss = { modelPickerMode = null }) { alias ->
            viewModel.selectModel(alias)
            modelPickerMode = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompatibleModelPicker(state: LabUiState, mode: LabInputMode, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable(mode) { mutableStateOf("") }
    val models = state.availableModels.filter {
        mode.accepts(it) && (query.isBlank() || it.alias.contains(query, true) || it.displayName.contains(query, true))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LabSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Choose ${mode.label.lowercase(Locale.ROOT)} model", color = LabText,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Selection does not download a model. Load or download it when ready.", color = LabMuted,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Search compatible models") }, shape = RoundedCornerShape(12.dp))
            Text("${models.size} compatible models", color = LabMuted,
                modifier = Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(models, key = { it.alias }) { model ->
                    ModelCatalogRow(model, model.alias == state.selectedModelAlias) {
                        if (!state.isBusy && !state.isCapabilityRunning) onSelect(model.alias)
                    }
                    HorizontalDivider(color = LabLine)
                }
                if (models.isEmpty()) item { Text("No compatible models match this search.", color = LabMuted) }
            }
        }
    }
}

@Composable
private fun LabHeader(state: LabUiState) {
    val statusColor by animateColorAsState(
        targetValue = when {
            state.isChatReady || state.isAudioReady -> LabGreen
            state.isConnected -> LabAmber
            else -> Color(0xFFFF8A8A)
        },
        animationSpec = tween(300),
        label = "runtime-status"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "FOUNDRY LOCAL",
                    color = LabAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("Lab", color = LabText, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        state.isChatReady || state.isAudioReady -> "Ready"
                        state.isConnected -> "Connected"
                        else -> "Offline"
                    },
                    color = LabMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
        Text(
            state.selectedModel?.displayName ?: "Discovering the local model catalog…",
            color = LabMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsWorkspace(
    state: LabUiState,
    filter: CatalogFilter,
    onFilterChange: (CatalogFilter) -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemoveCache: () -> Unit,
    onCancelOperation: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visibleModels = state.availableModels.filter { model ->
        val matchesQuery = query.isBlank() ||
            model.displayName.contains(query, ignoreCase = true) ||
            model.alias.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            CatalogFilter.ALL -> true
            CatalogFilter.CHAT -> model.capability == LabModelCapability.CHAT
            CatalogFilter.AUDIO -> model.capability == LabModelCapability.AUDIO
            CatalogFilter.FILE -> model.supportsFileTranscription
            CatalogFilter.MICROPHONE -> model.supportsLiveTranscription
        }
        matchesQuery && matchesFilter
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)
    ) {
        item {
            Text("Model workspace", color = LabText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Discover capabilities, control lifecycle, and keep one runtime loaded.",
                color = LabMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(18.dp))
            RuntimeSummaryLine(state)
            Spacer(modifier = Modifier.height(18.dp))
            SelectedModelControl(
                state,
                onReconnect,
                onDownload,
                onLoad,
                onUnload,
                onRemoveCache,
                onCancelOperation
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live catalog", color = LabText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${state.availableModels.size} models · ${state.chatModelCount} chat · ${state.audioModelCount} audio",
                        color = LabMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onRefresh, enabled = state.isConnected && !state.isBusy) {
                    if (state.isCatalogRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LabAccent)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh live catalog", tint = LabAccent)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Search model name or alias") }
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogFilter.values().filter { it != CatalogFilter.FILE && it != CatalogFilter.MICROPHONE || it == filter }.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { onFilterChange(item) },
                        label = { Text(item.label) },
                        leadingIcon = if (filter == item) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        items(visibleModels, key = { it.alias }) { model ->
            ModelCatalogRow(
                model = model,
                selected = model.alias == state.selectedModelAlias,
                onClick = { onSelect(model.alias) }
            )
            HorizontalDivider(color = LabLine)
        }

        if (visibleModels.isEmpty()) {
            item {
                Text(
                    "No catalog models match this view.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    color = LabMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RuntimeSummaryLine(state: LabUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF081A22))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Memory, contentDescription = null, tint = LabAccent, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text("IPC", color = LabText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(10.dp))
        Text("API ${state.apiVersion}", color = LabMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            state.activeModelAlias?.let { "1 active" } ?: "0 active",
            color = if (state.activeModelAlias != null) LabGreen else LabMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SelectedModelControl(
    state: LabUiState,
    onReconnect: () -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemoveCache: () -> Unit,
    onCancelOperation: () -> Unit
) {
    val selected = state.selectedModel
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = LabSurfaceRaised
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SELECTED MODEL", color = LabAccent, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text(
                        selected?.displayName ?: "No model selected",
                        color = LabText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(selected?.alias.orEmpty(), color = LabMuted, style = MaterialTheme.typography.bodySmall)
                }
                selected?.let { CapabilityBadge(it.capability) }
            }

            Spacer(modifier = Modifier.height(14.dp))
            DetailLine("Provider", selected?.executionProvider?.ifBlank { "Default" } ?: "—")
            DetailLine("Download", selected?.let { "${it.fileSizeMb} MB" } ?: "—")
            DetailLine("Active", state.activeModelAlias ?: "None")

            AnimatedVisibility(visible = state.isBusy && state.operationLabel != null) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = LabAccent,
                        trackColor = LabLine
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "${state.operationLabel} · ${state.progressPercent.coerceIn(0, 100)}%",
                        color = LabMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    TextButton(onClick = onCancelOperation, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel operation")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val actionLabel = when {
                !state.isConnected -> "Reconnect"
                !state.isDownloaded -> "Download model"
                !state.isLoaded -> "Load model"
                else -> "Unload model"
            }
            val actionIcon = when {
                !state.isConnected -> Icons.Rounded.Refresh
                !state.isDownloaded -> Icons.Rounded.CloudDownload
                !state.isLoaded -> Icons.Rounded.RocketLaunch
                else -> Icons.Rounded.PowerSettingsNew
            }
            val action = when {
                !state.isConnected -> onReconnect
                !state.isDownloaded -> onDownload
                !state.isLoaded -> onLoad
                else -> onUnload
            }
            Button(
                onClick = action,
                enabled = !state.isBusy && selected?.capability?.isRunnableInCurrentBuild == true,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LabAccent, contentColor = Color(0xFF00242C)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(actionIcon, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = onRemoveCache,
                enabled = state.isDownloaded && !state.isLoaded && !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Remove cached files")
            }
            Text(
                "Single-model guard: switching always unloads the active runtime first.",
                color = LabMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ModelCatalogRow(model: LabModelChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Color(0xFF0E2B35) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (model.capability == LabModelCapability.CHAT) Color(0xFF123743) else Color(0xFF322D23)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (model.capability == LabModelCapability.AUDIO) Icons.Outlined.GraphicEq else Icons.Rounded.Code,
                contentDescription = null,
                tint = if (model.capability == LabModelCapability.CHAT) LabAccent else LabAmber,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(model.displayName, color = LabText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${model.alias} · ${model.executionProvider.ifBlank { "Default provider" }}",
                color = LabMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!model.capability.isRunnableInCurrentBuild) {
                Text(model.capability.availabilityNote, color = LabAmber, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = LabGreen)
        else if (!model.capability.isRunnableInCurrentBuild) Icon(Icons.Outlined.Lock, contentDescription = "Unavailable", tint = LabMuted)
    }
}

@Composable
private fun CapabilityBadge(capability: LabModelCapability) {
    val color = if (capability == LabModelCapability.CHAT) LabGreen else LabAmber
    Surface(shape = RoundedCornerShape(20.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            capability.label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RuntimeWorkspace(state: LabUiState, onRefresh: () -> Unit, onClearEvents: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Runtime inspector", color = LabText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("What the current Android artifact can actually do.", color = LabMuted)
                }
                IconButton(onClick = onRefresh, enabled = state.isConnected && !state.isBusy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh runtime", tint = LabAccent)
                }
            }
        }

        item {
            InspectorSection("SESSION") {
                DetailLine("Deployment", "IPC service")
                DetailLine("Connection", if (state.isConnected) "Connected" else "Disconnected")
                DetailLine("API version", state.apiVersion)
                DetailLine("SDK version", state.sdkVersion)
                DetailLine("Runtime version", state.runtimeVersion)
                DetailLine(
                    "Compatibility",
                    when (state.isCompatible) {
                        true -> "Compatible"
                        false -> "Update required"
                        null -> "Not checked"
                    }
                )
                DetailLine("Compatibility detail", state.compatibilityMessage)
                DetailLine("Selected model", state.selectedModelAlias.ifBlank { "None" })
                DetailLine("Selected downloaded", if (state.isDownloaded) "Yes" else "No")
                DetailLine("Active model", state.activeModelAlias ?: "None")
                DetailLine("SDK cache report", "${state.cachedModelCount} models")
                DetailLine("Cache location", state.cacheLocation)
                DetailLine("Last inference", state.lastInferenceDurationMs?.let { "$it ms" } ?: "Not run")
            }
        }

        item {
            InspectorSection("CAPABILITY MATRIX") {
                CapabilityLine("Live model catalog", true, "${state.availableModels.size} models discovered")
                CapabilityLine("Streaming chat", true, "Flow-based token streaming with cancellation")
                CapabilityLine("Single-model lifecycle", true, "Serialized download, load, switch, unload, and removal")
                CapabilityLine("File transcription", true, "Whisper file and streaming-file APIs")
                CapabilityLine("Live audio", true, "Nemotron microphone streaming with explicit finalization")
                CapabilityLine("Embedded mode", false, "Requires the embedded Android artifact")
            }
        }

        if (state.lastRawResponse.isNotBlank()) {
            item {
                InspectorSection("LAST RAW RESPONSE") {
                    Text(
                        state.lastRawResponse,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF031016))
                            .padding(12.dp),
                        color = Color(0xFFB9F6E5),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            InspectorSection("RUNTIME EVENTS") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearEvents, enabled = state.runtimeEvents.isNotEmpty()) { Text("Clear") }
                }
                if (state.runtimeEvents.isEmpty()) {
                    Text("No runtime events yet.", color = LabMuted)
                } else {
                    state.runtimeEvents.reversed().forEachIndexed { index, event ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                String.format(Locale.US, "%02d", state.runtimeEvents.size - index),
                                color = LabLine,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(event, color = LabMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = LabAccent, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = LabLine)
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = LabMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.38f))
        Text(
            value,
            color = LabText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CapabilityLine(label: String, available: Boolean, note: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (available) LabGreen else LabAmber)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(label, color = LabText, fontWeight = FontWeight.SemiBold)
            Text(note, color = LabMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
