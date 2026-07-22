package com.freeflix.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.freeflix.app.data.model.TmdbItem
import com.freeflix.app.ui.theme.Bg3
import com.freeflix.app.ui.theme.Gold
import com.freeflix.app.ui.theme.PureBlack
import com.freeflix.app.ui.theme.Red
import com.freeflix.app.ui.theme.TextMuted
import com.freeflix.app.ui.theme.TextPrimary
import com.freeflix.app.ui.theme.netflixCardScaleSpec
import com.freeflix.app.ui.util.ResponsiveDims
import com.freeflix.app.ui.util.responsiveDims

@Composable
fun ContentCard(
    item: TmdbItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false
) {
    val dims = responsiveDims()
    ContentCard(item = item, onClick = onClick, modifier = modifier, dims = dims, fillMaxWidth = fillMaxWidth)
}

/**
 * Optional [FocusRequester] lets a parent screen ask the system to land D-pad
 * focus on a specific card (e.g. the first card in a row) so the user always
 * has a known starting point when they enter a screen on a no-pointer TV box.
 *
 * @param fillMaxWidth When true the card expands to fill its parent's width
 *                     constraint instead of pinning to [ResponsiveDims.cardWidth].
 *                     Use this inside a grid (e.g. Browse) so cards fill every
 *                     column evenly on both phone and TV. Horizontal content
 *                     rows leave it false so cards keep their fixed Netflix
 *                     card footprint.
 */
@Composable
fun ContentCard(
    item: TmdbItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dims: ResponsiveDims,
    focusRequester: FocusRequester? = null,
    fillMaxWidth: Boolean = false
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val active = isFocused || isHovered

    // Netflix card scale-up on hover/focus, tiny press shrink.
    // Only animate when the card is actually interacted with — when idle the
    // target is a constant 1.0f so animateFloatAsState is a no-op and adds
    // zero overhead to scroll. This is the key scroll-smoothness win.
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 1.02f
            active -> 1.08f
            else -> 1.0f
        },
        animationSpec = netflixCardScaleSpec(),
        label = "cardScale"
    )

    // ── Stable modifier chain (focus-ring flutter fix) ─────────────────
    // The #1 cause of the red focus ring disappearing a fraction of a second
    // after landing on a card was that the modifier CHAIN itself mutated on
    // focus: a conditional `.border(...)` / `.shadow(...)` node was inserted
    // and removed every time `active` flipped. Inserting/removing a modifier
    // node re-creates the clickable's focusable node, which transiently
    // drops focus → isFocused flips false → the ring vanishes → recompose →
    // focus returns → the ring comes back, forever oscillating.
    //
    // Fix: keep the chain structurally IDENTICAL at all times and only vary
    // *values* (colour / elevation) that don't add or remove nodes:
    //   • border is ALWAYS present; its colour is Color.Transparent when idle
    //     and Red when focused — one node, swapped colour, no chain mutation.
    //   • shadow is ALWAYS present; its elevation animates 0 → 24dp — one
    //     node, animated value, no chain mutation.
    val focusBorderColor by animateColorAsState(
        targetValue = if (active) Red else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "cardFocusBorder"
    )
    val shadowElevation by animateFloatAsState(
        targetValue = if (active) 24f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "cardShadowElevation"
    )

    // Reuse a single ImageRequest data object per card so Coil's cache key is
    // stable and the request isn't rebuilt on every recomposition.
    val imageRequest = remember(item.posterUrl) {
        ImageRequest.Builder(context)
            .data(item.posterUrl)
            .crossfade(200)
            .build()
    }

    Column(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(dims.cardWidth))
            .clip(RoundedCornerShape(6.dp))
            .scale(scale)
            // Shadow is ALWAYS in the chain (structural stability — see the
            // note above). Its elevation animates 0 → 24dp so idle cards pay
            // effectively zero shadow cost (0 elevation = no shadow pass),
            // while focused cards get the Netflix red glow. Keeping the node
            // constant means focus is never dropped by a chain mutation.
            .shadow(
                elevation = shadowElevation.dp,
                shape = RoundedCornerShape(6.dp),
                ambientColor = Red.copy(alpha = 0.35f),
                spotColor = Red.copy(alpha = 0.45f),
            )
            .background(Bg3)
            // Single focusable node: .clickable() registers the one focusable
            // node for D-pad navigation. (A previously-present redundant
            // .focusable(interactionSource) was removed because it created a
            // SECOND focusable node that shared the interaction source and
            // made focus oscillate. Do NOT re-add .focusable() here.)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            // Focus ring border is ALWAYS in the chain (structural stability).
            // Its colour animates Transparent → Red so the node is constant
            // and focus is never dropped by a chain mutation. This is what
            // makes the red outline STAY until the user moves to the next
            // card instead of flickering off after a fraction of a second.
            .border(3.dp, focusBorderColor, RoundedCornerShape(6.dp))
    ) {
        Box(
            modifier = Modifier
                .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(dims.cardWidth))
                .height(dims.cardImageHeight)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.width(dims.cardWidth))
                    .height(dims.cardImageHeight)
                    .background(PureBlack)
            )

            // Bottom gradient for legibility — only drawn when the card is
            // active (focused/hovered). Netflix only shows the dark scrim on
            // the hovered card; idle cards show the clean poster. This
            // removes a full-size gradient render from every off-screen card.
            if (active) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dims.cardImageHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    PureBlack.copy(alpha = 0.55f)
                                )
                            )
                        )
                )
            }

            // Play icon overlay that fades in when the card is active —
            // mirrors Netflix's hover play affordance.
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(PureBlack.copy(alpha = 0.6f))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // TV badge (top-left)
            if (!item.isMovie) {
                Text(
                    text = "TV",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Red, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Rating badge (top-right)
            if (item.ratingText.isNotEmpty()) {
                Text(
                    text = "\u2B50 ${item.ratingText}",
                    color = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Caption bar beneath the poster — Netflix shows title + year here.
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.displayTitle,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.year,
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}
