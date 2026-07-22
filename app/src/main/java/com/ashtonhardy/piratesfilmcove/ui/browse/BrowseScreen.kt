package com.ashtonhardy.piratesfilmcove.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashtonhardy.piratesfilmcove.data.model.Genre
import com.ashtonhardy.piratesfilmcove.data.model.MOVIE_GENRES
import com.ashtonhardy.piratesfilmcove.data.model.TV_GENRES
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.ui.components.ContentCard
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester

@Composable
fun BrowseScreen(
    onItemClick: (TmdbItem) -> Unit,
    viewModel: BrowseViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val canLoadMore by viewModel.canLoadMore.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val allGenres = MOVIE_GENRES + TV_GENRES
    val dims = responsiveDims()

    // On a no-pointer TV box, land D-pad focus on the first genre pill so the
    // user has a known starting point when they open Browse.
    val firstGenreFocusRequester = rememberInitialFocusRequester()

    // A single LazyVerticalGrid hosts the title, the genre selector, the
    // loading state, and the content cards. Headers / footers span the full
    // grid width via GridItemSpan(maxLineSpan) so they read as normal rows,
    // while the cards flow into dims.gridColumns columns (3 on phone, 5 on TV).
    //
    // focusGroup(): clamps D-pad focus inside the screen so Up from the first
    // genre pill / first card can't escape into empty space (nothing focused,
    // user stranded on a no-pointer remote).
    LazyVerticalGrid(
        columns = GridCells.Fixed(dims.gridColumns),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .padding(top = dims.topContentPadding),
        contentPadding = PaddingValues(
            start = dims.rowPadding,
            end = dims.rowPadding,
            bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(dims.cardSpacing)
    ) {
        // ── Title (full width) ──────────────────────────────────────────
        item(span = { GridItemSpan(dims.gridColumns) }) {
            Text(
                text = "\uD83D\uDDC2\uFE0F Browse by Genre",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // ── Genre selector (full width) ─────────────────────────────────
        item(span = { GridItemSpan(dims.gridColumns) }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                items(allGenres) { genre ->
                    GenrePill(
                        genre = genre,
                        isSelected = selectedGenre == genre || (selectedGenre == null && genre.id.isEmpty()),
                        onClick = {
                            viewModel.loadGenre(
                                if (genre.id.isEmpty()) null else genre,
                                genre.type
                            )
                        },
                        focusRequester = if (genre === allGenres.first()) firstGenreFocusRequester else null
                    )
                }
            }
        }

        // Initial load only: full-screen spinner when there are no items yet.
        // Once items are on screen we keep them visible and reflect an in-flight
        // loadMore() on the "Show More" button, so pressing it never wipes the grid.
        if (isLoading && items.isEmpty()) {
            item(span = { GridItemSpan(dims.gridColumns) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(36.dp))
                }
            }
        }

        // Content cards - always rendered when there are items, regardless of
        // isLoading, so a loadMore() in progress doesn't blank out what's shown.
        items(
            items = items,
            key = { "${it.id}_${it.contentType}" }
        ) { item ->
            ContentCard(
                item = item,
                onClick = { onItemClick(item) },
                fillMaxWidth = true
            )
        }

        // "Show More" footer - shown only when there are cards AND there's
        // more to load (canLoadMore). When the genre catalog is exhausted
        // canLoadMore flips to false and the button disappears. While a
        // loadMore() is in flight the button shows a spinner and is disabled.
        if (items.isNotEmpty() && canLoadMore) {
            item(span = { GridItemSpan(dims.gridColumns) }) {
                ShowMoreButton(
                    isLoading = loadingMore,
                    onClick = { viewModel.loadMore() }
                )
            }
        }
    }
}

/**
 * Full-width "Show More" affordance for the genre grid. Mirrors the styling of
 * the per-row LoadMoreButton (red focus border, centered label + chevron) but
 * spans the grid width. While [isLoading] is true (a loadMore() is in flight)
 * it shows a spinner and won't fire another request.
 */
@Composable
private fun ShowMoreButton(
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val dims = responsiveDims()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Bg3)
                .then(
                    if (isFocused) {
                        Modifier.border(3.dp, Red, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    enabled = !isLoading,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 32.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Red,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Show More",
                        color = if (isFocused) Red else TextPrimary,
                        fontSize = if (dims.isTv) 16.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Show More",
                        tint = if (isFocused) Red else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GenrePill(
    genre: Genre,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Text(
        text = genre.name,
        color = if (isSelected) Color.White else Color(0xFFE5E5E5),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) Red else Bg3)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
