package com.ashtonhardy.piratesfilmcove.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims

/**
 * Netflix-style content row.
 *
 * The row title is white, bold, and left-aligned. Cards are spaced like
 * Netflix's browse rows and use the focus-scale behaviour from [ContentCard].
 *
 * When [onLoadMore] is supplied AND [canLoadMore] is true, a "Load More ›"
 * button is rendered as the LAST item inside the horizontal row (after all
 * the cards) instead of floating next to the title. This keeps the top of the
 * row clean and puts the affordance where the user naturally lands after
 * scrolling to the end. The button is only shown when the ViewModel reports
 * that there are more pages available — once the catalog is exhausted
 * ([canLoadMore] == false) the button disappears so the user never sees a
 * dead "Load More" with nothing behind it.
 */
@Composable
fun ContentRow(
    title: String,
    emoji: String,
    items: List<TmdbItem>,
    onItemClick: (TmdbItem) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * Whether there are more items available to load. The "Load More" button
     * is only rendered when this is true (and [onLoadMore] is non-null).
     * When false (or null) the row ends cleanly after the last card — no
     * dead button. Defaults to true for backward compatibility so existing
     * callers that pass [onLoadMore] without a flag keep their button until
     * they wire up proper end-of-catalog detection.
     */
    canLoadMore: Boolean = true,
    /** If provided, attached to the FIRST card so a screen can land D-pad
     *  focus there when it appears (no-pointer TV boxes). */
    firstCardFocusRequester: FocusRequester? = null
) {
    val dims = responsiveDims()
    val listState = rememberLazyListState()

    // Brief settle so the first card peeks from the left edge consistently.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) kotlinx.coroutines.delay(80)
    }

    Column(modifier = modifier.padding(bottom = if (dims.isTv) 28.dp else 18.dp)) {
        // Title only — the "Load More" affordance now lives at the END of the
        // row (as the last item in the LazyRow), not next to the title.
        Text(
            text = "$emoji $title",
            color = TextPrimary,
            fontSize = if (dims.isTv) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.rowPadding, vertical = 8.dp)
        )

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = dims.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            // Netflix rows don't clip the focused (scaled-up) card — give
            // vertical headroom so the scale + shadow aren't cut off.
            verticalAlignment = Alignment.CenterVertically,
            // focusGroup(): makes this row OWN its horizontal D-pad traversal.
            // Without it, a Left press mid-row bubbles up to the screen-level
            // focusGroup (which only traps vertical nav) and then to the
            // content Box's onKeyEvent — which opens the side rail even though
            // the user was just trying to move left one card. With focusGroup
            // on the row, Left/Right is consumed here for card-to-card
            // movement, and only a Left press on the FIRST card (nothing
            // further left to move to) reaches the outer handler → rail.
            modifier = Modifier.focusGroup()
        ) {
            items(
                count = items.size,
                key = { idx ->
                    val item = items[idx]
                    "${item.id}_${item.contentType}"
                }
            ) { idx ->
                val item = items[idx]
                ContentCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    dims = dims,
                    focusRequester = if (idx == 0) firstCardFocusRequester else null
                )
            }

            // "Load More" tile — last item in the row. Matches a card's
            // footprint so the row scrolls naturally to reveal it, and is
            // fully D-pad focusable with the same red highlight as cards.
            // Only rendered when the ViewModel reports more pages are
            // available ([canLoadMore]); once the catalog is exhausted the
            // button is gone so there's no dead "Load More" at the end.
            if (onLoadMore != null && canLoadMore) {
                item(key = "load_more") {
                    LoadMoreButton(
                        onClick = onLoadMore,
                        dims = dims
                    )
                }
            }
        }
    }
}

/**
 * The "Load More ›" affordance rendered as the trailing item of a content
 * row. It mirrors a [ContentCard]'s width and height so it lines up with the
 * cards, and uses the red focus border so it's obvious on TV.
 */
@Composable
private fun LoadMoreButton(
    onClick: () -> Unit,
    dims: ResponsiveDims
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .width(dims.cardWidth)
            .height(dims.cardHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(Bg3)
            .then(
                if (isFocused) {
                    Modifier.border(3.dp, Red, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Load More",
                color = if (isFocused) Red else TextPrimary,
                fontSize = if (dims.isTv) 16.sp else 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Load More",
                tint = if (isFocused) Red else TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
