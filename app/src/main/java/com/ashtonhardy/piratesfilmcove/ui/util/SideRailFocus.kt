package com.ashtonhardy.piratesfilmcove.ui.util

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The TV side-navigation rail should only open when the user presses Up
 * while focus is already at the very TOP of the screen — i.e. on the
 * topmost row's first card, or on the hero banner (which sits at the top).
 * Pressing Left must NEVER open the rail; it always performs the default
 * card-to-card move. Pressing Up mid-screen (a lower row) must also just
 * move focus to the row above — NOT open the rail.
 *
 * The mechanism: every focusable card / hero button reports its on-screen
 * position (X and Y, in pixels) to a shared holder when it gains focus. The
 * AppRoot's outer key handler then checks the reported Y against a
 * top-edge threshold:
 *   • focusedY <= threshold  → we're at the top of the screen → open the
 *     rail (consume the Up event so the focus system does NOT also move).
 *   • focusedY >  threshold  → we're mid-screen (a lower row) → let the
 *     default upward move happen (return false so the event is NOT
 *     consumed and the focus system performs the directional move).
 *
 * Left presses are never intercepted here — they always fall through to
 * the focus system so the user can navigate left/right freely at any row.
 *
 * This is uniform across horizontal content rows, vertical grids (Browse /
 * Search) and the hero banner, and avoids brittle per-card "isFirstRow"
 * plumbing. A CompositionLocal is used so the reporter can be read from any
 * depth in the composition tree without prop threading.
 */

/**
 * A sink that a focused card / button calls with its on-screen X and Y (px)
 * the moment it gains focus. The AppRoot owns the underlying state and
 * reads it in its Up key handler.
 *
 * Pass `null` (the default, via [LocalSideRailXReporter]) to disable
 * reporting — e.g. on phone layouts where the side rail doesn't exist.
 */
typealias SideRailReporter = (Float, Float) -> Unit

/**
 * CompositionLocal for the position reporter. Defaults to a no-op so
 * non-TV (phone) layouts and any component rendered outside a provider
 * simply don't report — they never hit the side-rail key handler anyway.
 */
val LocalSideRailXReporter = staticCompositionLocalOf<SideRailReporter?> { null }
