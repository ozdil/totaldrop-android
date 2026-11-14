// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package app.totaldrop.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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

private enum class TvReceiveStage {
    Entering,
    Connecting,
    Offered,
    Transferring,
    Done,
    Failed,
}

/**
 * 10-foot Android TV / Google TV Receive screen:
 * - Oversized 2-word (and optional 3-word strong) transfer code slot display (`7 - harbor - rocket`).
 * - Full D-Pad directional focus traversal across remote number buttons, keyboard text box,
 *   and Connect / Clear controls.
 * - Strict CPace 4-word verification display and `revealControls` BiDi filename protection before
 *   saving anything to TV storage.
 * - Floor-rounded regional progress indicator (#483) with matching TalkBack `stateDescription`.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TvReceiveScreen(
    bridge: TotalDropRustBridge,
    serverUrl: String,
    privacyRelayOnly: Boolean,
    downloadsDirectory: File,
    externalPrefillCode: String?,
    onExternalPrefillConsumed: () -> Unit,
    onCanAcceptLinkChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(TvReceiveStage.Entering) }
    var rawCodeInput by remember { mutableStateOf("") }
    var invalidCodeWarning by remember { mutableStateOf(false) }
    var activeStopper by remember { mutableStateOf<TvStopperHandle?>(null) }
    var offer by remember { mutableStateOf<TvIncomingOffer?>(null) }
    var bytesDone by remember { mutableLongStateOf(0L) }
    var bytesTotal by remember { mutableLongStateOf(0L) }
    var summary by remember { mutableStateOf<TvTransferSummary?>(null) }
    var failure by remember { mutableStateOf<TvTransferError?>(null) }

    val connectButtonFocus = remember { FocusRequester() }
    val declineButtonFocus = remember { FocusRequester() }

    // Report whether incoming App Links (#445, T19) may safely replace the input box:
    // never during active connection/transfer, pending offer, or when showing received text.
    val canAcceptLink = stage == TvReceiveStage.Entering ||
        stage == TvReceiveStage.Failed ||
        (stage == TvReceiveStage.Done && summary?.text == null)

    LaunchedEffect(canAcceptLink) {
        onCanAcceptLinkChanged(canAcceptLink)
    }

    LaunchedEffect(externalPrefillCode) {
        val code = externalPrefillCode ?: return@LaunchedEffect
        onExternalPrefillConsumed()
        if (canAcceptLink) {
            stage = TvReceiveStage.Entering
            summary = null
            failure = null
            rawCodeInput = code
            invalidCodeWarning = code.isEmpty()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeStopper?.stop()
            activeStopper?.close()
        }
    }

    fun resetToEntering() {
        activeStopper?.stop()
        activeStopper?.close()
        activeStopper = null
        offer = null
        summary = null
        failure = null
        bytesDone = 0L
        bytesTotal = 0L
        invalidCodeWarning = false
        stage = TvReceiveStage.Entering
    }

    fun startConnect() {
        val normalized = bridge.normalizeCode(rawCodeInput)
        if (normalized == null) {
            invalidCodeWarning = true
            return
        }
        invalidCodeWarning = false
        rawCodeInput = normalized
        val stopper = bridge.newStopper()
        activeStopper?.close()
        activeStopper = stopper
        stage = TvReceiveStage.Connecting
        scope.launch {
            val result = bridge.connectReceive(
                server = serverUrl,
                code = normalized,
                privacy = privacyRelayOnly,
                stopper = stopper,
            )
            result.fold(
                onSuccess = { incoming ->
                    offer = incoming
                    stage = TvReceiveStage.Offered
                },
                onFailure = { err ->
                    failure = (err as? TvTransferError)
                        ?: TvTransferError(TvErrorKind.Other, err.message.orEmpty())
                    stage = TvReceiveStage.Failed
                },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Primary 10-foot interactive pane
        Column(
            modifier = Modifier
                .weight(0.66f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (stage) {
                TvReceiveStage.Entering -> {
                    Text(
                        text = "Enter Sender's 2-Word Code",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Type the number and words shown on the sending phone, tablet, or computer.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Oversized 10-foot segmented preview pills (Number - Word 1 - Word 2 [+ Word 3])
                    OversizedCodeSlots(rawInput = rawCodeInput)

                    // Remote-friendly text field with visible focus border
                    var textFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = if (textFocused) 3.dp else 1.dp,
                                color = if (textFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                shape = RoundedCornerShape(16.dp),
                            )
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 24.dp, vertical = 18.dp)
                            .onFocusChanged { textFocused = it.isFocused },
                    ) {
                        if (rawCodeInput.isEmpty()) {
                            Text(
                                text = "Example: 7-harbor-rocket",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = rawCodeInput,
                            onValueChange = {
                                rawCodeInput = it
                                invalidCodeWarning = false
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { startConnect() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // D-Pad quick digit & separator bar so users with basic 5-way remotes can start fast
                    Text(
                        text = "D-Pad Quick Prefix Digits",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        (1..9).forEach { digit ->
                            OutlinedButton(
                                onClick = {
                                    rawCodeInput = if (rawCodeInput.isEmpty()) {
                                        "$digit-"
                                    } else {
                                        "$rawCodeInput$digit"
                                    }
                                    invalidCodeWarning = false
                                },
                            ) {
                                Text(text = "$digit")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (rawCodeInput.isNotEmpty() && !rawCodeInput.endsWith("-")) {
                                    rawCodeInput = "$rawCodeInput-"
                                }
                            },
                        ) {
                            Text(text = "Hyphen (-)")
                        }
                        OutlinedButton(
                            onClick = {
                                if (rawCodeInput.isNotEmpty()) {
                                    rawCodeInput = rawCodeInput.dropLast(1)
                                }
                            },
                        ) {
                            Text(text = "Backspace")
                        }
                    }

                    if (invalidCodeWarning) {
                        Text(
                            text = "Enter a valid TotalDrop code (a number followed by two or three words).",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = ::startConnect,
                            modifier = Modifier.focusRequester(connectButtonFocus),
                        ) {
                            Text(
                                text = "Connect",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        if (rawCodeInput.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    rawCodeInput = ""
                                    invalidCodeWarning = false
                                },
                            ) {
                                Text(text = "Clear")
                            }
                        }
                    }
                }

                TvReceiveStage.Connecting -> {
                    Text(
                        text = "Connecting with ${rawCodeInput}...",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Negotiating end-to-end encrypted CPace + ML-KEM-768 session.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = ::resetToEntering) {
                        Text(text = "Stop")
                    }
                }

                TvReceiveStage.Offered -> {
                    val incoming = offer
                    LaunchedEffect(incoming?.handle) {
                        // Focus Decline first on TV so an accidental double-tap on OK never auto-saves.
                        declineButtonFocus.requestFocus()
                    }
                    if (incoming != null) {
                        Text(
                            text = if (incoming.isText) "Incoming Text Note" else "Incoming File Offer",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        VerificationWordsBanner(words = incoming.words)

                        Text(
                            text = if (incoming.isText) {
                                "Text message (${formatSiBytes(incoming.totalBytes)}) — displayed in memory, not written to disk."
                            } else {
                                "${incoming.files} file(s), ${incoming.folders} folder(s) • ${formatSiBytes(incoming.totalBytes)}"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )

                        // Reveal BiDi/control characters on every offered filename before display.
                        incoming.names.take(6).forEach { rawName ->
                            Text(
                                text = "• ${bridge.revealControls(rawName)}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (incoming.names.size > 6) {
                            Text(
                                text = "…and ${incoming.names.size - 6} more",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Button(
                                onClick = {
                                    val stopper = activeStopper ?: bridge.newStopper().also { activeStopper = it }
                                    stage = TvReceiveStage.Transferring
                                    bytesDone = 0L
                                    bytesTotal = incoming.totalBytes
                                    scope.launch {
                                        val flow = if (incoming.isText) {
                                            bridge.acceptText(incoming, stopper)
                                        } else {
                                            bridge.acceptOffer(incoming, downloadsDirectory.absolutePath, stopper)
                                        }
                                        flow.collect { ev ->
                                            when (ev.kind) {
                                                TvEventKind.Progress -> {
                                                    bytesDone = ev.done
                                                    bytesTotal = ev.total
                                                }
                                                TvEventKind.Finished -> {
                                                    summary = ev.summary
                                                    stage = TvReceiveStage.Done
                                                }
                                                TvEventKind.Failed -> {
                                                    failure = ev.error
                                                    stage = TvReceiveStage.Failed
                                                }
                                                else -> Unit
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    text = if (incoming.isText) "Show Text" else "Accept & Save",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        bridge.declineOffer(incoming)
                                        resetToEntering()
                                    }
                                },
                                modifier = Modifier.focusRequester(declineButtonFocus),
                            ) {
                                Text(text = "Decline")
                            }
                        }
                    }
                }

                TvReceiveStage.Transferring -> {
                    Text(
                        text = "Receiving Encrypted Transfer",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    TvTransferProgressBar(done = bytesDone, total = bytesTotal)
                    OutlinedButton(onClick = ::resetToEntering) {
                        Text(text = "Stop Transfer")
                    }
                }

                TvReceiveStage.Done -> {
                    val result = summary
                    Text(
                        text = if (result?.text != null) "Received Text" else "Transfer Complete",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (result?.text != null) {
                        val safeText = remember(result.text) { bridge.shownText(result.text) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(20.dp),
                        ) {
                            Text(
                                text = safeText.text,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (safeText.hidden) {
                            Text(
                                text = "Note: hidden control or direction characters were revealed as <U+XXXX>.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (safeText.short) {
                            Text(
                                text = "Note: showing the first 16,384 characters on screen.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (result != null) {
                        Text(
                            text = "${result.filesOk} file(s) verified (${formatSiBytes(result.bytes)}) saved to ${downloadsDirectory.absolutePath}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Button(onClick = ::resetToEntering) {
                        Text(text = "Done")
                    }
                }

                TvReceiveStage.Failed -> {
                    Text(
                        text = "Could Not Complete Receive",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "${failure?.kind ?: TvErrorKind.Other}: ${failure?.detail.orEmpty()}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = ::resetToEntering) {
                        Text(text = "Try Again")
                    }
                }
            }
        }

        // Right-hand 10-foot companion QR & security card
        Surface(
            modifier = Modifier
                .weight(0.34f)
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
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Send from Your Phone",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TvQrPairingCard(
                    content = "https://totaldrop.app",
                    accessibilityLabel = "QR code for https://totaldrop.app",
                    size = 210.dp,
                )
                Text(
                    text = "1. Open TotalDrop on your phone or visit totaldrop.app.\n" +
                        "2. Choose Send and pick files or text.\n" +
                        "3. Enter the 2-word code on the left and check the 4 verification words.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun OversizedCodeSlots(rawInput: String) {
    val tokens = rawInput
        .trim()
        .split(Regex("[\\s-]+"))
        .filter { it.isNotEmpty() }
    val slotLabels = listOf(
        tokens.getOrNull(0) ?: "#",
        tokens.getOrNull(1) ?: "word-one",
        tokens.getOrNull(2) ?: "word-two",
    ) + if (tokens.size >= 4) listOf(tokens[3]) else emptyList()

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slotLabels.forEachIndexed { index, part ->
            val filled = index < tokens.size
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (filled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(
                    text = part,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (filled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (index < slotLabels.lastIndex) {
                Text(
                    text = "–",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VerificationWordsBanner(words: List<String>) {
    if (words.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Verification Words (must match the other screen)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = words.joinToString("   •   "),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Floor-rounded regional progress bar (#483):
 * TalkBack `stateDescription` reports the exact integer `wholePercent` shown visually,
 * preventing screen readers from announcing `100` when `995 / 1000` bytes have arrived.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvTransferProgressBar(done: Long, total: Long) {
    val pct = computeFloorPercent(done, total)
    val fraction = if (total > 0L) (pct.wholePercent / 100f).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = pct.wholePercent.toString()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = pct.wholePercent.toFloat(),
                    range = 0f..100f,
                    steps = 99,
                )
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${formatSiBytes(done)} / ${formatSiBytes(total)}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = pct.regionalLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
