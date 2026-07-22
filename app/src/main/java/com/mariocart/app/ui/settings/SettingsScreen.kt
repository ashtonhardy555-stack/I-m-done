package com.mariocart.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocart.app.BuildConfig
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.ui.AutoUpdater
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.GreyButton
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims
import com.mariocart.app.ui.util.rememberInitialFocusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen — gives the user control over the app's behaviour that
 * previously had no UI:
 *
 *  • **Preferred streaming server** — pin a specific provider or set to
 *    Auto (let the app pick the best one). Uses [ServerManager].
 *  • **Clear stream availability cache** — wipes the per-title good/bad
 *    provider cache so the next play re-probes from scratch.
 *  • **Check for updates** — manually trigger the AutoUpdater check and
 *    show the result (instead of only the automatic prompt on launch).
 *  • **App version info** — shows the current version + build number.
 *
 * On TV the screen is D-pad navigable with focus rings on every action;
 * on phone it's a normal scrollable settings list.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dims = responsiveDims()

    // ── Server picker state ────────────────────────────────────────────── //
    LaunchedEffect(Unit) {
        ServerManager.initialize(context)
    }
    val servers = remember { ServerManager.allServers() }
    val selectedId = remember { ServerManager.getSelectedServerId() }
    var showServerPicker by remember { mutableStateOf(false) }

    // ── Cache clear state ──────────────────────────────────────────────── //
    var cacheCleared by remember { mutableStateOf(false) }

    // ── Update check state ─────────────────────────────────────────────── //
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }

    val firstFocus = rememberInitialFocusRequester()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = if (dims.isTv) 48.dp else 20.dp,
                end = if (dims.isTv) 48.dp else 20.dp,
                top = dims.topContentPadding,
                bottom = 40.dp
            )
    ) {
        // ── Header ──────────────────────────────────────────────────────── //
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = Red,
                modifier = Modifier.size(if (dims.isTv) 28.dp else 24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Settings",
                color = TextPrimary,
                fontSize = if (dims.isTv) 26.sp else 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Section: Streaming ──────────────────────────────────────────── //
        SettingsSectionLabel("Streaming")

        // Preferred server
        SettingsRow(
            icon = Icons.Default.Security,
            title = "Preferred Server",
            subtitle = if (selectedId == null) "Auto (best available)"
            else servers.firstOrNull { it.id == selectedId }?.name
                ?: "Custom ($selectedId)",
            actionLabel = "Change",
            onClick = { showServerPicker = true },
            dims = dims,
            focusRequester = firstFocus
        )

        // Clear stream availability cache
        SettingsRow(
            icon = Icons.Default.CloudOff,
            title = "Clear Stream Cache",
            subtitle = if (cacheCleared) "Cache cleared — next play will re-probe"
            else "Reset per-title good/bad provider records",
            actionLabel = if (cacheCleared) "Done" else "Clear",
            onClick = {
                if (!cacheCleared) {
                    clearStreamCache(context)
                    cacheCleared = true
                }
            },
            dims = dims
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Section: Updates ────────────────────────────────────────────── //
        SettingsSectionLabel("Updates")

        SettingsRow(
            icon = Icons.Default.Update,
            title = "Check for Updates",
            subtitle = updateResult ?: "Manually check for a newer version",
            actionLabel = if (isCheckingUpdate) "" else "Check",
            onClick = {
                if (!isCheckingUpdate) {
                    isCheckingUpdate = true
                    updateResult = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            AutoUpdater.checkForUpdate()
                        }
                        isCheckingUpdate = false
                        updateResult = when (result) {
                            is AutoUpdater.CheckResult.UpdateAvailable ->
                                "Update available: ${result.releaseName}"
                            AutoUpdater.CheckResult.UpToDate ->
                                "You're up to date!"
                            is AutoUpdater.CheckResult.Error ->
                                "Check failed: ${result.message}"
                        }
                    }
                }
            },
            dims = dims,
            trailingOverride = {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        color = Red,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(if (dims.isTv) 24.dp else 20.dp)
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Section: About ──────────────────────────────────────────────── //
        SettingsSectionLabel("About")

        SettingsInfoRow(
            title = "App Version",
            subtitle = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            dims = dims
        )

        SettingsInfoRow(
            title = "App Name",
            subtitle = "Freeflix",
            dims = dims
        )
    }

    // ── Server picker dialog ────────────────────────────────────────────── //
    if (showServerPicker) {
        ServerPickerDialog(
            servers = servers,
            selectedId = selectedId,
            onDismiss = { showServerPicker = false },
            onSelect = { id ->
                ServerManager.setSelectedServerId(id)
                showServerPicker = false
            },
            dims = dims
        )
    }
}

/** Clears the stream availability cache file from disk. */
private fun clearStreamCache(context: Context) {
    try {
        val cacheFile = java.io.File(context.cacheDir, "stream_availability.json")
        if (cacheFile.exists()) cacheFile.delete()
        // Also clear the in-memory cache if it has one.
        com.mariocart.app.data.cache.StreamAvailabilityCache.clearAll(context)
    } catch (_: Exception) {
        // Best-effort; don't crash if the cache can't be cleared.
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        color = Red,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    dims: com.mariocart.app.ui.util.ResponsiveDims,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    trailingOverride: @Composable (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bg3)
            .then(
                if (focused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (dims.isTv) 20.dp else 16.dp,
                vertical = if (dims.isTv) 14.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(if (dims.isTv) 24.dp else 20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontSize = if (dims.isTv) 16.sp else 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    color = TextMuted,
                    fontSize = if (dims.isTv) 13.sp else 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
        if (trailingOverride != null) {
            trailingOverride()
        } else if (actionLabel.isNotEmpty()) {
            Text(
                actionLabel,
                color = Red,
                fontSize = if (dims.isTv) 14.sp else 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(
    title: String,
    subtitle: String,
    dims: com.mariocart.app.ui.util.ResponsiveDims
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bg3)
            .padding(
                horizontal = if (dims.isTv) 20.dp else 16.dp,
                vertical = if (dims.isTv) 14.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TextMuted,
                fontSize = if (dims.isTv) 13.sp else 12.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                subtitle,
                color = TextPrimary,
                fontSize = if (dims.isTv) 16.sp else 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ServerPickerDialog(
    servers: List<com.mariocart.app.data.server.ServerConfig>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    dims: com.mariocart.app.ui.util.ResponsiveDims
) {
    val autoFocus = rememberInitialFocusRequester()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(if (dims.isTv) 520.dp else 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Bg)
                .border(1.dp, GreyButton, RoundedCornerShape(12.dp))
                .padding(24.dp)
        ) {
            Text(
                "Preferred Server",
                color = TextPrimary,
                fontSize = if (dims.isTv) 20.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Auto option
            ServerOptionRow(
                label = "Auto (best available)",
                isSelected = selectedId == null,
                onClick = { onSelect(null) },
                focusRequester = autoFocus,
                dims = dims
            )
            Spacer(modifier = Modifier.height(6.dp))

            servers.forEach { server ->
                ServerOptionRow(
                    label = server.pickerLabel,
                    isSelected = server.id == selectedId,
                    onClick = { onSelect(server.id) },
                    dims = dims
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Tap outside to close.",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ServerOptionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    dims: com.mariocart.app.ui.util.ResponsiveDims,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bg3)
            .then(
                if (focused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                else Modifier
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TextPrimary,
            fontSize = if (dims.isTv) 15.sp else 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = Red,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
