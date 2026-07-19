package com.mariocart.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.onFocusChanged
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
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Gold
import com.mariocart.app.ui.theme.PureBlack
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.theme.netflixCardScaleSpec
import com.mariocart.app.ui.util.ResponsiveDims
import com.mariocart.app.ui.util.responsiveDims

@Composable
fun ContentCard(
    item: TmdbItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = responsiveDims()
    ContentCard(item = item, onClick = onClick, modifier = modifier, dims = dims)
}

/**
 * Netflix-style content card.
 *
 * Signature behaviours that match the real Netflix:
 *  • On focus / hover the card scales UP (~1.08×) with a spring and lifts
 *    with a red-tinted shadow — the "card grows toward you" effect.
 *  • A red focus ring (border) appears when the D-pad lands on it (TV).
 *  • A subtle dark gradient at the bottom of the poster so titles stay
 *    readable when the info strip is collapsed.
 *  • The poster fills the whole card; title/year sit in a thin caption bar
 *    beneath, exactly like Netflix's browse rows.
 */
@Composable
fun ContentCard(
    item: TmdbItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dims: ResponsiveDims
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
            .width(dims.cardWidth)
            .clip(RoundedCornerShape(6.dp))
            .scale(scale)
            // Heavy shadow only on active cards — drawing a coloured shadow
            // on every idle card during fast scroll is the #1 GPU bottleneck.
            // Idle cards get a cheap 0dp elevation (no shadow render pass).
            .then(
                if (active) {
                    Modifier.shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(6.dp),
                        ambientColor = Red.copy(alpha = 0.35f),
                        spotColor = Red.copy(alpha = 0.45f),
                    )
                } else {
                    Modifier
                }
            )
            .background(Bg3)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .then(
                if (active) {
                    Modifier.border(3.dp, Red, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
    ) {
        Box(
            modifier = Modifier
                .width(dims.cardWidth)
                .height(dims.cardImageHeight)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(dims.cardWidth)
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
