package com.mariocart.app.ui.util

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * TV focus helpers.
 *
 * On a no-pointer Android TV box the user navigates purely with the D-pad.
 * When they land on a new screen NOTHING is focused, so they have no idea
 * where they are on the screen. These helpers let each screen declare a
 * "starting" element that grabs D-pad focus the moment the screen appears,
 * so the red focus ring always shows on a known button/card.
 *
 * They are no-ops on touch devices (phones) where there is a pointer and
 * initial focus is irrelevant.
 */

/**
 * Creates and remembers a [FocusRequester], then requests focus on it once
 * (when [enabled] is true) after the composition settles. Safe to call at
 * the top of any screen composable.
 *
 * Pass the returned [FocusRequester] into a focusable element via
 * `Modifier.focusRequester(focusRequester)`.
 *
 * @param enabled Only requests focus on TV / no-touch devices. Defaults to
 *                [needsFocusIndicators] so callers don't have to think about
 *                it — on a phone this does nothing.
 */
@Composable
fun rememberInitialFocusRequester(enabled: Boolean = needsFocusIndicators()): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    if (enabled) {
        LaunchedEffect(Unit) {
            // A tiny delay lets the host layout (LazyColumn / Box) attach its
            // children before we ask the framework to move focus. Without it
            // the request can race the first layout pass on some TVs and be
            // silently dropped.
            kotlinx.coroutines.delay(120)
            runCatching { focusRequester.requestFocus() }
        }
    }
    return focusRequester
}
