// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package app.totaldrop.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import java.io.File

private enum class TvSendStage {
    Picking,
    Preparing,
    WaitingForPeer,
    Transferring,
    Done,
    Failed,
}

/**
 * 10-foot Android TV / Google TV Send screen:
 * - D-Pad navigable local TV folder/file picker (`Downloads`) + quick text-note sender.
 * - Optional 3-word strong code toggle (`strong = true`).
 * - Oversized 2-word / 3-word pairing code banner paired side-by-side with a crisp vector QR
 *   code (`https://totaldrop.app/r#<code>`) for instant phone/tablet camera scanning across the room.
 * - CPace 4-word verification display and floor-rounded regional progress bar (#483).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSendScreen(
    bridge: TotalDropRustBridge,
    serverUrl: String,
    privacyRelayOnly: Boolean,
    browseRoot: File,
) {
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(TvSendStage.Picking) }
    var sendTextMode by remember { mutableStateOf(false) }
    var textPayload by remember { mutableStateOf("") }
    var strongCode by remember { mutableStateOf(false) }
    val selectedPaths = remember { mutableStateListOf<String>() }

    var activeStopper by remember { mutableStateOf<TvStopperHandle?>(null) }
    var session by remember { mutableStateOf<TvSendSession?>(null) }
    var verificationWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var bytesDone by remember { mutableLongStateOf(0L) }
    var bytesTotal by remember { mutableLongStateOf(0L) }
    var summary by remember { mutableStateOf<TvTransferSummary?>(null) }
    var failure by remember { mutableStateOf<TvTransferError?>(null) }

    val availableFiles = remember(browseRoot.absolutePath, stage) {
        browseRoot.listFiles()
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    DisposableEffect(Unit) {
        onDispose {
            activeStopper?.stop()
            activeStopper?.close()
        }
    }

    fun resetToPicking() {
        activeStopper?.stop()
        activeStopper?.close()
        activeStopper = null
        session = null
        verificationWords = emptyList()
        bytesDone = 0L
        bytesTotal = 0L
        summary = null
        failure = null
        stage = TvSendStage.Picking
    }

    fun beginSend() {
        val stopper = bridge.newStopper()
        activeStopper?.close()
        activeStopper = stopper
        stage = TvSendStage.Preparing
        scope.launch {
            val preparedResult = if (sendTextMode) {
                bridge.prepareSendText(
                    server = serverUrl,
                    text = textPayload,
                    strong = strongCode,
                    privacy = privacyRelayOnly,
                    stopper = stopper,
                )
            } else {
                bridge.prepareSend(
                    server = serverUrl,
                    paths = selectedPaths.toList(),
                    strong = strongCode,
                    privacy = privacyRelayOnly,
                    stopper = stopper,
                )
            }

            preparedResult.fold(
                onSuccess = { prepared ->
                    session = prepared
                    bytesTotal = prepared.totalBytes
                    stage = TvSendStage.WaitingForPeer
                    bridge.runSend(prepared, stopper).collect { ev ->
                        when (ev.kind) {
                            TvEventKind.Waiting -> stage = TvSendStage.WaitingForPeer
                            TvEventKind.Connected -> {
                                verificationWords = ev.words
                                stage = TvSendStage.Transferring
                            }
                            TvEventKind.Progress -> {
                                bytesDone = ev.done
                                bytesTotal = ev.total
                                stage = TvSendStage.Transferring
                            }
                            TvEventKind.Finished -> {
                                summary = ev.summary
                                stage = TvSendStage.Done
                            }
                            TvEventKind.Failed -> {
                                failure = ev.error
                                stage = TvSendStage.Failed
                            }
                        }
                    }
                },
                onFailure = { err ->
                    failure = (err as? TvTransferError)
                        ?: TvTransferError(TvErrorKind.Other, err.message.orEmpty())
                    stage = TvSendStage.Failed
                },
            )
        }
    }

    when (stage) {
        TvSendStage.Picking, TvSendStage.Preparing -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(32.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Send from TV to Phone or Computer",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { sendTextMode = !sendTextMode }) {
                            Text(text = if (sendTextMode) "Mode: Text Note" else "Mode: TV Files")
                        }
                        OutlinedButton(onClick = { strongCode = !strongCode }) {
                            Text(text = if (strongCode) "Code: 3 Words" else "Code: 2 Words")
                        }
                    }
                }

                if (sendTextMode) {
                    var focused by remember { mutableStateOf(false) }
                    Text(
                        text = "Enter text to share (up to ${formatSiBytes(bridge.maxTextBytes())}, never written to receiver's disk):",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = if (focused) 3.dp else 1.dp,
                                color = if (focused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                shape = RoundedCornerShape(16.dp),
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(20.dp)
                            .onFocusChanged { focused = it.isFocused },
                    ) {
                        if (textPayload.isEmpty()) {
                            Text(
                                text = "Type or paste a URL, Wi-Fi note, or snippet with your TV remote…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = textPayload,
                            onValueChange = { textPayload = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Text(
                        text = "Select files in ${browseRoot.absolutePath}:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (availableFiles.isEmpty()) {
                        Text(
                            text = "No files in TV Downloads yet. Receive files onto this TV first or switch to Mode: Text Note.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        availableFiles.forEach { file ->
                            val path = file.absolutePath
                            val isSelected = path in selectedPaths
                            OutlinedButton(
                                onClick = {
                                    if (isSelected) selectedPaths.remove(path) else selectedPaths.add(path)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "${if (isSelected) "[✓]" else "[ ]"} ${file.name} (${formatSiBytes(file.length())})",
                                )
                            }
                        }
                    }
                }

                val canStart = stage == TvSendStage.Picking &&
                    ((sendTextMode && textPayload.isNotBlank()) || (!sendTextMode && selectedPaths.isNotEmpty()))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { if (canStart) beginSend() },
                        enabled = canStart,
                    ) {
                        Text(
                            text = if (stage == TvSendStage.Preparing) {
                                "Preparing & Reserving Code…"
                            } else {
                                "Generate Pairing Code & QR"
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (stage == TvSendStage.Preparing) {
                        OutlinedButton(onClick = ::resetToPicking) {
                            Text(text = "Cancel")
                        }
                    }
                }
            }
        }

        TvSendStage.WaitingForPeer, TvSendStage.Transferring -> {
            val activeSession = session
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                // Left: Oversized 10-foot 2-word / 3-word code + verification words + progress
                Column(
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        text = if (stage == TvSendStage.WaitingForPeer) {
                            "Ready to Pair — Enter Code or Scan QR"
                        } else {
                            "Sending Encrypted Data"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    if (activeSession != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(26.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "One-Time Transfer Code",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = activeSession.segments.joinToString("  –  "),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    if (verificationWords.isNotEmpty()) {
                        VerificationWordsBanner(words = verificationWords)
                    }

                    if (stage == TvSendStage.Transferring) {
                        TvTransferProgressBar(done = bytesDone, total = bytesTotal)
                    } else if (activeSession != null) {
                        Text(
                            text = "${activeSession.items} item(s) • ${formatSiBytes(activeSession.totalBytes)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = ::resetToPicking) {
                        Text(text = "Stop Transfer")
                    }
                }

                // Right: High-contrast fragment-isolated QR pairing display (#445, T19)
                Surface(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (activeSession != null) {
                            TvQrPairingCard(
                                content = activeSession.pairingUrl,
                                accessibilityLabel = "QR pairing code for transfer ${activeSession.code}",
                                size = 256.dp,
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Scan with Phone Camera",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Opens TotalDrop directly via verified App Links. The code stays in the #fragment and never touches the web server.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }

        TvSendStage.Done -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Sent Intact",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${summary?.filesOk ?: 1L} item(s) (${formatSiBytes(summary?.bytes ?: bytesTotal)}) verified end-to-end by the receiver.",
                    style = MaterialTheme.typography.titleLarge,
                )
                Button(onClick = ::resetToPicking) {
                    Text(text = "Send More")
                }
            }
        }

        TvSendStage.Failed -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Send Ended",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "${failure?.kind ?: TvErrorKind.Other}: ${failure?.detail.orEmpty()}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = ::resetToPicking) {
                    Text(text = "Back to Send")
                }
            }
        }
    }
}
