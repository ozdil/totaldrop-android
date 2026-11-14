// SPDX-FileCopyrightText: 2026 The TotalDrop Contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package app.totaldrop.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import java.io.File

/**
 * 10-foot Android TV / Google TV entry point (`LEANBACK_LAUNCHER`).
 *
 * Security & Threat Mitigation (`#445`, `T19`):
 * - Accepts only verified `https://totaldrop.app/r#<code>` App Links and `totaldrop://r/<code>`.
 * - Parses transfer codes exclusively through `TotalDropRustBridge.codeFromLink`
 *   (`totaldrop_proto::TransferCode::from_link`).
 * - Pre-fills the Receive screen without auto-connecting, and refuses to clobber an active
 *   transfer, an unanswered incoming offer, or a displayed received text.
 */
class MainActivity : ComponentActivity() {
    private val bridge: TotalDropRustBridge by lazy { NativeTotalDropRustBridge() }
    private var incomingLinkState by mutableStateOf<PendingReceiveLink?>(null)
    private var linkSequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            consumeReceiveIntent(intent)
        }

        val defaultDownloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "Downloads").apply { mkdirs() }

        setContent {
            var themePreference by remember { mutableStateOf(TvThemeMode.Dark) }
            val useDark = when (themePreference) {
                TvThemeMode.Dark -> true
                TvThemeMode.Light -> false
                TvThemeMode.System -> isSystemInDarkTheme()
            }

            TotalDropTvTheme(darkTheme = useDark) {
                TotalDropTvApp(
                    bridge = bridge,
                    downloadsDirectory = defaultDownloadsDir,
                    pendingLink = incomingLinkState,
                    onConsumePendingLink = { incomingLinkState = null },
                    themeMode = themePreference,
                    onCycleThemeMode = { themePreference = themePreference.next() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeReceiveIntent(intent)
    }

    private fun consumeReceiveIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!isSupportedReceiveLink(uri)) return
        linkSequence += 1L
        incomingLinkState = PendingReceiveLink(id = linkSequence, rawUri = uri.toString())
    }

    /**
     * Explicit intents can bypass manifest filters on exported activities (T19):
     * enforce the exact two receive link shapes before forwarding to Rust.
     */
    private fun isSupportedReceiveLink(uri: Uri): Boolean =
        (uri.scheme == "totaldrop" && uri.host == "r") ||
            (uri.scheme == "https" && uri.host == "totaldrop.app" && uri.path == "/r")
}

data class PendingReceiveLink(
    val id: Long,
    val rawUri: String,
)

enum class TvThemeMode(val label: String) {
    Dark("Dark"),
    Light("Light"),
    System("Auto"),
    ;

    fun next(): TvThemeMode = when (this) {
        Dark -> Light
        Light -> System
        System -> Dark
    }
}

/**
 * Dynamic Material 3 for TV theme (`minSdk = 31` Android 12+):
 * extracts system wallpaper dynamic tonal palettes via `dynamicDarkColorScheme` /
 * `dynamicLightColorScheme` and maps them into `androidx.tv.material3.MaterialTheme`
 * with 10-foot legible typography and high-contrast focus states.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TotalDropTvTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val tvColors = if (dynamicColor) {
        if (darkTheme) {
            val dyn = dynamicDarkColorScheme(context)
            darkColorScheme(
                primary = dyn.primary,
                onPrimary = dyn.onPrimary,
                primaryContainer = dyn.primaryContainer,
                onPrimaryContainer = dyn.onPrimaryContainer,
                secondary = dyn.secondary,
                onSecondary = dyn.onSecondary,
                secondaryContainer = dyn.secondaryContainer,
                onSecondaryContainer = dyn.onSecondaryContainer,
                tertiary = dyn.tertiary,
                onTertiary = dyn.onTertiary,
                background = dyn.background,
                onBackground = dyn.onBackground,
                surface = dyn.surface,
                onSurface = dyn.onSurface,
                surfaceVariant = dyn.surfaceVariant,
                onSurfaceVariant = dyn.onSurfaceVariant,
                error = dyn.error,
                onError = dyn.onError,
            )
        } else {
            val dyn = dynamicLightColorScheme(context)
            lightColorScheme(
                primary = dyn.primary,
                onPrimary = dyn.onPrimary,
                primaryContainer = dyn.primaryContainer,
                onPrimaryContainer = dyn.onPrimaryContainer,
                secondary = dyn.secondary,
                onSecondary = dyn.onSecondary,
                secondaryContainer = dyn.secondaryContainer,
                onSecondaryContainer = dyn.onSecondaryContainer,
                tertiary = dyn.tertiary,
                onTertiary = dyn.onTertiary,
                background = dyn.background,
                onBackground = dyn.onBackground,
                surface = dyn.surface,
                onSurface = dyn.onSurface,
                surfaceVariant = dyn.surfaceVariant,
                onSurfaceVariant = dyn.onSurfaceVariant,
                error = dyn.error,
                onError = dyn.onError,
            )
        }
    } else if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF5AD1B8),
            onPrimary = Color(0xFF00382E),
            primaryContainer = Color(0xFF005143),
            onPrimaryContainer = Color(0xFF7BF8DC),
            background = Color(0xFF0B1219),
            onBackground = Color(0xFFE1E6EB),
            surface = Color(0xFF131D27),
            onSurface = Color(0xFFE1E6EB),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006B59),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF7BF8DC),
            onPrimaryContainer = Color(0xFF002019),
            background = Color(0xFFF5FBF8),
            onBackground = Color(0xFF171D1B),
            surface = Color(0xFFE9F2EE),
            onSurface = Color(0xFF171D1B),
        )
    }

    val tvTypography = Typography().let { base ->
        base.copy(
            displayLarge = base.displayLarge.copy(
                fontSize = 52.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            headlineMedium = base.headlineMedium.copy(
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            titleLarge = base.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            bodyLarge = base.bodyLarge.copy(
                fontSize = 20.sp,
                lineHeight = 28.sp,
            ),
            labelLarge = base.labelLarge.copy(
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }

    MaterialTheme(
        colorScheme = tvColors,
        typography = tvTypography,
        content = content,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TotalDropTvApp(
    bridge: TotalDropRustBridge,
    downloadsDirectory: File,
    pendingLink: PendingReceiveLink?,
    onConsumePendingLink: () -> Unit,
    themeMode: TvThemeMode,
    onCycleThemeMode: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var privacyRelayOnly by remember { mutableStateOf(false) }
    var receiveCanAcceptLink by remember { mutableStateOf(true) }
    var prefilledReceiveCode by remember { mutableStateOf<String?>(null) }
    var linkBannerMessage by remember { mutableStateOf<String?>(null) }

    val receiveTabFocus = remember { FocusRequester() }
    val tabs = listOf("Receive", "Send")

    // Route incoming App Links (#445, T19) strictly via Rust codeFromLink.
    LaunchedEffect(pendingLink?.id) {
        val link = pendingLink ?: return@LaunchedEffect
        onConsumePendingLink()
        if (!receiveCanAcceptLink) {
            linkBannerMessage =
                "Link ignored while a transfer, pending offer, or received text is active."
            return@LaunchedEffect
        }
        val parsed = bridge.codeFromLink(link.rawUri)
        selectedTab = 0
        if (parsed != null) {
            prefilledReceiveCode = parsed
            linkBannerMessage = "Code filled from link. Press Connect on your remote when ready."
        } else if (!hasEmptyCodePortion(link.rawUri)) {
            prefilledReceiveCode = ""
            linkBannerMessage = "That link did not contain a valid TotalDrop code."
        }
    }

    LaunchedEffect(Unit) {
        receiveTabFocus.requestFocus()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            // 10-foot Top Bar: Brand + D-Pad TabRow + Relay-Only Privacy & Theme controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TotalDrop TV",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(32.dp))
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onFocus = { selectedTab = index },
                                onClick = { selectedTab = index },
                                modifier = if (index == 0) {
                                    Modifier.focusRequester(receiveTabFocus)
                                } else {
                                    Modifier
                                },
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedButton(onClick = { privacyRelayOnly = !privacyRelayOnly }) {
                        Text(
                            text = if (privacyRelayOnly) {
                                "Privacy: Relay-Only"
                            } else {
                                "Route: Direct + Relay"
                            },
                        )
                    }
                    OutlinedButton(onClick = onCycleThemeMode) {
                        Text(text = "Theme: ${themeMode.label}")
                    }
                }
            }

            if (linkBannerMessage != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = linkBannerMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> TvReceiveScreen(
                        bridge = bridge,
                        serverUrl = bridge.defaultServer(),
                        privacyRelayOnly = privacyRelayOnly,
                        downloadsDirectory = downloadsDirectory,
                        externalPrefillCode = prefilledReceiveCode,
                        onExternalPrefillConsumed = { prefilledReceiveCode = null },
                        onCanAcceptLinkChanged = { receiveCanAcceptLink = it },
                    )
                    else -> TvSendScreen(
                        bridge = bridge,
                        serverUrl = bridge.defaultServer(),
                        privacyRelayOnly = privacyRelayOnly,
                        browseRoot = downloadsDirectory,
                    )
                }
            }
        }
    }
}

private fun hasEmptyCodePortion(link: String): Boolean =
    link == "totaldrop://r" ||
        link == "totaldrop://r/" ||
        link == "https://totaldrop.app/r" ||
        link == "https://totaldrop.app/r#"
