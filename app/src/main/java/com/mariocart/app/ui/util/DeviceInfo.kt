package com.mariocart.app.ui.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Detects the device form factor so the UI can adapt its layout between
 * phones and Android TV boxes.
 *
 * TV detection uses two signals:
 *  1. [UiModeManager] current mode == [Configuration.UI_MODE_TYPE_TELEVISION]
 *     — the official Android way; set by the system on certified TV boxes
 *     and Fire TV / Android TV devices.
 *  2. The [Configuration.UI_MODE_TYPE_TELEVISION] flag on the resources
 *     configuration — a fallback that covers some OEM TVs that don't report
 *     via UiModeManager.
 *
 * A device is considered a TV if EITHER signal is true. This avoids the
 * false-negative where a TV box that ships a touch-enabled launcher (e.g.
 * some Chinese TV boxes) reports as a normal phone but is clearly being
 * used on a TV.
 */
object DeviceInfo {

    fun isTv(context: Context): Boolean {
        // Signal 1: UiModeManager (most reliable for certified Android TV).
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        // Signal 2: Resource configuration fallback.
        return context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Whether the device has a touchscreen. TV boxes don't, so this is a
     * secondary signal for TV detection and also tells us whether to show
     * D-pad-friendly focus indicators.
     */
    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
}

/**
 * Compose-friendly wrapper for TV detection.
 *
 * Marked @ReadOnlyComposable so the Compose compiler knows this function
 * only reads from the composition environment (LocalContext) and never
 * writes state — enabling aggressive skipping. We intentionally do NOT
 * wrap the result in remember(): DeviceInfo.isTv() is a cheap, pure
 * function of the Context (which is itself stable across recompositions),
 * and remember inside a @ReadOnlyComposable is illegal because remember
 * writes to the composition.
 */
@Composable
@ReadOnlyComposable
fun isTvDevice(): Boolean {
    val context = LocalContext.current
    return DeviceInfo.isTv(context)
}

/**
 * Whether the current device should use focus indicators and D-pad-friendly
 * hit targets (true on TV, false on phones with a touchscreen).
 */
@Composable
@ReadOnlyComposable
fun needsFocusIndicators(): Boolean {
    val context = LocalContext.current
    return DeviceInfo.isTv(context) || !DeviceInfo.hasTouchscreen(context)
}

// ─────────────────────────────────────────────────────────────────── //
//  Responsive dimension helpers                                       //
//  Centralised so every card / row / banner scales consistently       //
//  between phone and TV without scattering magic numbers.             //
// ─────────────────────────────────────────────────────────────────── //

/**
 * Holds all the responsive dimensions the UI uses. A single object is
 * cheaper to pass around than many individual Dp values and keeps the
 * phone/TV scaling in one place.
 */
data class ResponsiveDims(
    val isTv: Boolean,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val cardImageHeight: Dp,
    val cardSpacing: Dp,
    val rowPadding: Dp,
    val heroHeight: Dp,
    val heroTitleSize: Int,
    val navIconSize: Dp,
    val navLabelSize: Int,
    val gridColumns: Int,
    /**
     * Vertical space to reserve at the top of a non-hero screen so its
     * title / first content row is NOT hidden behind the transparent
     * overlay top bar (phone layout only). On TV the side rail is used
     * instead of an overlay top bar, so this is 0.
     */
    val topContentPadding: Dp,
) {
    companion object {
        val Phone = ResponsiveDims(
            isTv = false,
            cardWidth = 140.dp,
            cardHeight = 210.dp,
            cardImageHeight = 210.dp,
            cardSpacing = 10.dp,
            rowPadding = 16.dp,
            heroHeight = 420.dp,
            heroTitleSize = 28,
            navIconSize = 24.dp,
            navLabelSize = 11,
            gridColumns = 3,
            // Status-bar inset (~24dp) + top bar vertical padding (10dp) +
            // tab text + underline (~26dp) + a little breathing room.
            topContentPadding = 72.dp,
        )

        val TV = ResponsiveDims(
            isTv = true,
            // Cards on TV are ~50% larger so they're legible from the couch.
            cardWidth = 200.dp,
            cardHeight = 300.dp,
            cardImageHeight = 300.dp,
            cardSpacing = 16.dp,
            rowPadding = 32.dp,
            heroHeight = 560.dp,
            heroTitleSize = 40,
            navIconSize = 32.dp,
            navLabelSize = 14,
            // TV screens are wide — 5 columns fills the space nicely.
            gridColumns = 5,
            // No overlay top bar on TV (side rail layout) — nothing to clear.
            topContentPadding = 0.dp,
        )
    }
}

@Composable
@ReadOnlyComposable
fun responsiveDims(): ResponsiveDims {
    return if (isTvDevice()) ResponsiveDims.TV else ResponsiveDims.Phone
}
