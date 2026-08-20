/*
 * Copyright (c) Microsoft Corporation.
 * Licensed under the MIT License.
 */
package com.microsoft.foundrylocal.apiexplorer.ipc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiExplorerScreen(viewModel: ApiExplorerViewModel = viewModel()) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val logListState = rememberLazyListState()
    val lastLogLine = viewModel.outputLog.lastOrNull().orEmpty()

    LaunchedEffect(viewModel.outputLog.size, lastLogLine) {
        if (viewModel.outputLog.isNotEmpty()) {
            logListState.animateScrollToItem(viewModel.outputLog.lastIndex)
        }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+ for download progress
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                activity,
                "Notification permission denied — download progress won't show in notifications.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "API Explorer IPC",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            item {
                SectionCard(title = "Connection") {
                    Text(
                        text = if (viewModel.isConnected) {
                            "Status: Connected"
                        } else {
                            "Status: Disconnected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.connect(activity) },
                            enabled = !viewModel.isConnected && !viewModel.hasManager && !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect")
                        }
                        Button(
                            onClick = { viewModel.reconnect() },
                            enabled = !viewModel.isConnected && viewModel.hasManager && !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Reconnect")
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Catalog",
                    onClear = if (viewModel.catalogModels.isNotEmpty()) {
                        { viewModel.clearCatalog() }
                    } else {
                        null
                    }
                ) {
                    Button(
                        onClick = { viewModel.listCatalog() },
                        enabled = viewModel.isConnected && !viewModel.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("List Catalog")
                    }

                    if (viewModel.catalogModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        viewModel.catalogModels.forEach { model ->
                            Text(
                                text = "${model.alias} — ${model.displayName} (${model.fileSizeMb} MB)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = "Model") {
                    Text(
                        text = "Demo model: qwen2.5-coder-0.5b-instruct-generic-cpu:4",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cached: ${if (viewModel.isCached) "Yes" else "No"}")
                    Text("Loaded: ${if (viewModel.isLoaded) "Yes" else "No"}")
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.downloadModel(activity) },
                            enabled = viewModel.isConnected && !viewModel.isBusy && !viewModel.isDownloading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Download")
                        }
                        Button(
                            onClick = { viewModel.cancelDownload() },
                            enabled = viewModel.isDownloading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                    }

                    if (viewModel.isDownloading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { viewModel.downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${"%.2f".format(viewModel.downloadProgress)}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.loadModel() },
                            enabled = viewModel.isConnected && !viewModel.isLoaded && !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Load")
                        }
                        Button(
                            onClick = { viewModel.unloadModel() },
                            enabled = viewModel.isConnected && viewModel.isLoaded && !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unload")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.removeCachedModel() },
                        enabled = viewModel.isConnected && viewModel.isCached &&
                            !viewModel.isLoaded && !viewModel.isBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remove from Cache")
                    }
                }
            }

            item {
                SectionCard(title = "Chat") {
                    OutlinedTextField(
                        value = viewModel.chatInput,
                        onValueChange = { viewModel.chatInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = viewModel.isConnected && viewModel.isLoaded && !viewModel.isBusy,
                        placeholder = { Text("Ask the model something…") },
                        maxLines = 3
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.sendChat() },
                            enabled = viewModel.isConnected &&
                                viewModel.isLoaded &&
                                viewModel.chatInput.isNotBlank() &&
                                !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Chat")
                        }
                        Button(
                            onClick = { viewModel.sendChatStreaming() },
                            enabled = viewModel.isConnected &&
                                viewModel.isLoaded &&
                                viewModel.chatInput.isNotBlank() &&
                                !viewModel.isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stream")
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "Output Log",
                    onClear = if (viewModel.outputLog.isNotEmpty() && !viewModel.isBusy) {
                        { viewModel.clearLog() }
                    } else {
                        null
                    }
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (viewModel.outputLog.isEmpty()) {
                                item {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = "No output yet. Connect to get started.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            items(viewModel.outputLog) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
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
private fun SectionCard(
    title: String,
    onClear: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}
