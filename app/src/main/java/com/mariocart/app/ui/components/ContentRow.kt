package com.mariocart.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims

/**
 * Netflix-style content row.
 *
 * The row title is white, bold, and left-aligned. An "Explore All ›"
 * affordance sits on the right (Netflix uses "Explore All"). Cards are
 * spaced like Netflix's browse rows and use the focus-scale behaviour from
 * [ContentCard].
 */
@Composable
fun ContentRow(
    title: String,
    emoji: String,
    items: List<TmdbItem>,
    onItemClick: (TmdbItem) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.rowPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$emoji $title",
                color = TextPrimary,
                fontSize = if (dims.isTv) 22.sp else 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (onLoadMore != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onLoadMore) {
                        Text(
                            "Explore All",
                            color = Red,
                            fontSize = if (dims.isTv) 15.sp else 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Red,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = dims.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            // Netflix rows don't clip the focused (scaled-up) card — give
            // vertical headroom so the scale + shadow aren't cut off.
            verticalAlignment = Alignment.CenterVertically
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
        }
    }
}
