package com.mariocart.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Gold
import com.mariocart.app.ui.theme.GreyButton
import com.mariocart.app.ui.theme.GreyButtonHover
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims
import kotlinx.coroutines.delay

/**
 * Netflix-style hero / featured banner.
 *
 * Replicates the real Netflix home hero:
 *  - A full-bleed backdrop that crossfades between featured titles every ~8s.
 *  - A left-to-right dark gradient (content reads on the left) plus a
 *    bottom-to-black gradient that blends the hero into the row list below.
 *  - The #N-ranked featured title, large black-bold title, match % / rating /
 *    year line, short overview, and the two signature buttons: a white
 *    Play and a translucent-grey More Info.
 *  - On TV, both buttons are D-pad focusable with the red focus ring, and the
 *    whole hero gets a red left-edge accent + soft glow when either button is
 *    focused so the user can clearly tell the featured title is "selected".
 */
@Composable
fun HeroBanner(
    items: List<TmdbItem>,
    onPlayClick: (TmdbItem) -> Unit,
    onMoreInfo: ((TmdbItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
    playFocusRequester: FocusRequester? = null
) {
    if (items.isEmpty()) return

    val dims = responsiveDims()
    var currentIndex by remember { mutableIntStateOf(0) }
    val currentItem = items[currentIndex]

    // Whether EITHER hero button currently holds D-pad focus. When true the
    // hero shows a red left-edge accent bar + soft red glow so the user has a
    // clear, good-looking indication that the featured title is "selected" -
    // not just the small ring on the button itself. We track each button's
    // focus separately and OR them, so focus moving from Play to More Info
    // (one reports false, the other true) never momentarily drops the accent.
    var playFocused by remember { mutableStateOf(false) }
    var moreInfoFocused by remember { mutableStateOf(false) }
    val heroFocused = playFocused || moreInfoFocused

    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(8000)
                currentIndex = (currentIndex + 1) % items.size
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dims.heroHeight)
    ) {
        // Crossfading backdrop.
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                fadeIn(tween(900)) togetherWith fadeOut(tween(900))
            },
            label = "heroBg"
        ) { idx ->
            AsyncImage(
                model = items[idx].backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Left-to-right dark gradient (Netflix content legibility).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Bottom-to-black gradient (blend into the row list).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Bg.copy(alpha = 0.6f),
                            Bg
                        ),
                        startY = 200f
                    )
                )
        )

        // -- Hero "selected" indication ---------------------------------- //
        // A thin red bar pinned to the left edge + a soft red border glow
        // around the whole hero, shown only when a hero button is focused.
        // This is the "make sure the user knows they have it selected
        // properly but make it look good" affordance. Animated so it fades
        // in/out smoothly instead of popping.
        val accentAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (heroFocused) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "heroAccent"
        )
        if (dims.isTv && accentAlpha > 0.01f) {
            // Soft red outer glow (top + bottom edges).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Red.copy(alpha = 0.10f * accentAlpha),
                                Color.Transparent,
                                Color.Transparent,
                                Red.copy(alpha = 0.10f * accentAlpha)
                            )
                        )
                    )
            )
            // Left-edge accent bar (the "this is selected" marker).
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(6.dp)
                    .background(Red.copy(alpha = accentAlpha))
            )
        }

        // Content (bottom-left, Netflix-style). On TV we add the safe-area
        // bottom inset so the buttons are never clipped by TV overscan, and
        // give the text block a touch more bottom room so the full hero
        // (title + overview + buttons) is always fully visible.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = dims.rowPadding,
                    bottom = if (dims.isTv) 40.dp + dims.safeAreaBottom else 36.dp,
                    end = 120.dp
                )
        ) {
            // Featured ranking badge (#1, #2 ...) like Netflix's "Top 10".
            if (items.size > 1) {
                Text(
                    text = "#${currentIndex + 1}  Featured",
                    color = Red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    fadeIn(tween(600)) togetherWith fadeOut(tween(300))
                },
                label = "heroText"
            ) { idx ->
                val item = items[idx]
                Column {
                    Text(
                        text = item.displayTitle,
                        color = Color.White,
                        fontSize = dims.heroTitleSize.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = if (dims.isTv) 44.sp else 32.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.ratingText.isNotEmpty()) {
                            Text(
                                text = "\u2B50 ${item.ratingText}",
                                color = Gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = item.year,
                            color = TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    if (!item.overview.isNullOrBlank()) {
                        Text(
                            text = item.overview,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Play - white, like Netflix.
                HeroButton(
                    text = "Play",
                    icon = Icons.Default.PlayArrow,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    isTv = dims.isTv,
                    onClick = { onPlayClick(currentItem) },
                    focusRequester = playFocusRequester,
                    onFocusChanged = { focused -> playFocused = focused }
                )
                // More Info - translucent grey, like Netflix.
                HeroButton(
                    text = "More Info",
                    icon = Icons.Default.Info,
                    containerColor = GreyButton.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    isTv = dims.isTv,
                    onClick = { onMoreInfo?.invoke(currentItem) ?: onPlayClick(currentItem) },
                    onFocusChanged = { focused -> moreInfoFocused = focused }
                )
            }
        }

        // -- Scroll-down hint -------------------------------------------- //
        // A subtle "scroll down for more" indicator pinned to the bottom-center
        // of the hero so the user knows there's more content below.  A small
        // chevron that gently bounces up and down + a tiny caption, both
        // semi-transparent so they never fight the hero artwork for attention.
        // Fades out once a hero button is focused (the user has "arrived") so
        // it's never in the way.
        val scrollHintAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (heroFocused) 0f else 1f,
            animationSpec = androidx.compose.animation.core.tween(400),
            label = "scrollHintAlpha"
        )
        if (scrollHintAlpha > 0.01f) {
            // Bouncing offset for the chevron (2-second loop).
            val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(
                label = "scrollHintBounce"
            )
            val bounceOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 8f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(
                        durationMillis = 800,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "bounceOffset"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (dims.isTv) 12.dp + dims.safeAreaBottom else 8.dp)
            ) {
                Text(
                    text = "Scroll down for more",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.45f * scrollHintAlpha))
                        .alpha(scrollHintAlpha)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll down",
                    tint = TextMuted.copy(alpha = scrollHintAlpha),
                    modifier = Modifier
                        .size(28.dp)
                        .alpha(scrollHintAlpha)
                        .offset(y = bounceOffset.dp)
                )
            }
        }
    }
}

/** A Netflix-style hero button: rounded, bold, focusable for D-pad on TV. */
@Composable
private fun HeroButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    isTv: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Report focus state up to the hero so it can show the "selected" accent.
    // Each button reports its own focus boolean; the parent ORs the two so
    // the accent stays on while focus is on EITHER button.
    LaunchedEffect(isFocused) {
        onFocusChanged?.invoke(isFocused)
    }

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isFocused) GreyButtonHover else containerColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (isFocused && isTv) {
                    Modifier.border(2.dp, Red, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}
