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
import androidx.compose.runtime.mutableIntStateOf
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

/** Netflix-style horizontally scrollable content row. */
@Composable
fun ContentRow(
    title: String,
    emoji: String,
    items: List<TmdbItem>,
    onItemClick: (TmdbItem) -> Unit,
    onLoadMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    canLoadMore: Boolean = true,
    firstCardFocusRequester: FocusRequester? = null
) {
    val dims = responsiveDims()
    val listState = rememberLazyListState()
    val previousSize = remember { mutableIntStateOf(0) }

    LaunchedEffect(items.size) {
        if (items.size > previousSize.intValue && previousSize.intValue > 0) {
            listState.animateScrollToItem(previousSize.intValue)
        }
        previousSize.intValue = items.size
    }

    Column(modifier = modifier.padding(bottom = if (dims.isTv) 28.dp else 18.dp)) {
        Text(
            text = "$emoji $title",
            color = TextPrimary,
            fontSize = if (dims.isTv) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.rowPadding, vertical = 8.dp)
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = dims.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            items(count = items.size, key = { idx ->
                val item = items[idx]
                "${item.id}_${item.contentType}"
            }) { idx ->
                val item = items[idx]
                ContentCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    dims = dims,
                    focusRequester = if (idx == 0) firstCardFocusRequester else null
                )
            }

            // The affordance remains rendered whenever this row has a loader.
            // Paging state is enforced by the ViewModel after an authoritative
            // empty response, never by filtering or a short intermediate page.
            if (onLoadMore != null && (canLoadMore || items.isNotEmpty())) {
                item(key = "load_more") {
                    LoadMoreButton(onClick = onLoadMore, dims = dims)
                }
            }
        }
    }
}

@Composable
private fun LoadMoreButton(onClick: () -> Unit, dims: ResponsiveDims) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .width(dims.cardWidth)
            .height(dims.cardHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(Bg3)
            .then(if (isFocused) Modifier.border(3.dp, Red, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
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
