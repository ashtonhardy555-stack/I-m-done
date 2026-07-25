package com.ashtonhardy.piratesfilmcove.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.model.WatchProgress
import com.ashtonhardy.piratesfilmcove.ui.components.ContentCard
import com.ashtonhardy.piratesfilmcove.ui.components.ContentRow
import com.ashtonhardy.piratesfilmcove.ui.components.HeroBanner
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import com.ashtonhardy.piratesfilmcove.ui.theme.PureBlack
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon

private data class GenreChip(val emoji: String, val label: String, val genreId: String)

private val GENRE_CHIPS = listOf(
    GenreChip("\uD83D\uDD25", "Trending", ""),
    GenreChip("\uD83C\uDFAC", "Action", "28"),
    GenreChip("\uD83D\uDE02", "Comedy", "35"),
    GenreChip("\uD83D\uDC7B", "Horror", "27"),
    GenreChip("\uD83D\uDE80", "Sci-Fi", "878"),
    GenreChip("\uD83C\uDFAD", "Drama", "18"),
    GenreChip("\uD83D\uDD2A", "Thriller", "53"),
    GenreChip("\uD83C\uDF00", "Animation", "16"),
    GenreChip("\uD83D\uDC95", "Romance", "10749"),
    GenreChip("\uD83D\uDD75", "Crime", "80"),
    GenreChip("\uD83C\uDF0D", "Adventure", "12"),
    GenreChip("\uD83D\uDCFA", "TV Action", "10759"),
    GenreChip("\uD83D\uDCD6", "Documentary", "99"),
)

@Composable
fun HomeScreen(
    onItemClick: (TmdbItem) -> Unit,
    onSearchWithGenre: (String) -> Unit = {},
    onResume: (TmdbItem, Long) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = viewModel()
) {
    val heroItems by viewModel.heroItems.collectAsState()
    val trending by viewModel.trending.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val popularTV by viewModel.popularTV.collectAsState()
    val topRated by viewModel.topRated.collectAsState()
    val popularMovies by viewModel.popularMovies.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val recommended by viewModel.recommended.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()

    // canLoadMore flags — each is true while there are more pages available
    // for that row. Passed into ContentRow so the "Load More" button hides
    // once the catalog is exhausted (no dead button at the end of a row).
    val canLoadMoreTrending by viewModel.canLoadMoreTrending.collectAsState()
    val canLoadMoreNowPlaying by viewModel.canLoadMoreNowPlaying.collectAsState()
    val canLoadMorePopularTV by viewModel.canLoadMorePopularTV.collectAsState()
    val canLoadMoreTopRated by viewModel.canLoadMoreTopRated.collectAsState()
    val canLoadMorePopularMovies by viewModel.canLoadMorePopularMovies.collectAsState()
    val canLoadMoreRecommended by viewModel.canLoadMoreRecommended.collectAsState()

    // Refresh Continue Watching whenever the Home screen is (re)composed to
    // the foreground — the user may have just finished / partially watched a
    // title in the player, so the row should reflect that immediately.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshContinueWatching()
    }

    // On a no-pointer TV box, land D-pad focus on the hero Play button the
    // moment Home appears so the user always knows where they are.
    val playFocusRequester = rememberInitialFocusRequester()

    // focusGroup(): keeps D-pad focus clamped inside the screen's content.
    // Without it, pressing Up from the hero / first row can move focus above
    // the LazyColumn into empty space — nothing focused, user stranded on a
    // no-pointer TV remote. focusGroup() makes the focusable children a single
    // unit so Up stops at the top element and Down stops at the bottom.
    // On TV we add the top safe-area inset (overscan margin) so the hero
    // banner's top edge is never clipped by the TV bezel — this fixes the
    // "can't see the full top banner" issue where the top of the hero was
    // cut off on Android TV displays.
    val dims = responsiveDims()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .padding(top = dims.safeAreaTop)
    ) {
        item {
            HeroBanner(
                items = heroItems,
                onPlayClick = onItemClick,
                onMoreInfo = onItemClick,
                playFocusRequester = playFocusRequester
            )
        }
        // -- Recommended for You -----------------------------------------
        // Genre-based recommendations from the user's watch history. Only
        // rendered once there's a watch history to base recommendations on.
        // Now has its own Load More button so the user can page through
        // every recommended title without leaving Home.
        if (recommended.isNotEmpty()) {
            item {
                ContentRow(
                    title = "Recommended for You", emoji = "\u2B50",
                    items = recommended, onItemClick = onItemClick,
                    onLoadMore = { viewModel.loadMoreRecommended() },
                    canLoadMore = canLoadMoreRecommended
                )
            }
        }
        item {
            GenreSuggestionsBar(onGenreClick = onSearchWithGenre)
        }
        item {
            ContentRow(
                title = "Trending Now", emoji = "\uD83D\uDD25",
                items = trending, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreTrending() },
                canLoadMore = canLoadMoreTrending
            )
        }
        item {
            ContentRow(
                title = "New in Theatres", emoji = "\uD83C\uDFAC",
                items = nowPlaying, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreNowPlaying() },
                canLoadMore = canLoadMoreNowPlaying
            )
        }
        item {
            ContentRow(
                title = "Popular TV Shows", emoji = "\uD83D\uDCFA",
                items = popularTV, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMorePopularTV() },
                canLoadMore = canLoadMorePopularTV
            )
        }
        item {
            ContentRow(
                title = "Top Rated Movies", emoji = "\u2B50",
                items = topRated, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreTopRated() },
                canLoadMore = canLoadMoreTopRated
            )
        }
        // NOTE: a second "Popular Movies" row used to live here but was
        // removed -- it was near-identical to "Top Rated Movies" (both are
        // movie rows ranked by a popularity/rating signal) and the user
        // flagged the duplication. Trending Now + New in Theatres already
        // cover the "popular" angle, so the home page no longer repeats it.
        // -- Continue Watching ------------------------------------------
        // MOVED to the bottom of the Home feed (was 2nd, right under the
        // hero). Placing it last means the fresh content rows get the
        // prime "above the fold" real estate and the continue-watching
        // cards act as a natural "pick up where you left off" footer.
        if (continueWatching.isNotEmpty()) {
            item {
                ContinueWatchingRow(
                    items = continueWatching,
                    progressMap = progressMap,
                    onItemClick = onItemClick,
                    onResume = onResume
                )
            }
        }
    }
}

@Composable
private fun GenreSuggestionsBar(onGenreClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Browse by Category",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GENRE_CHIPS.forEach { chip ->
                GenreChipItem(chip = chip, onClick = { onGenreClick(chip.genreId) })
            }
        }
    }
}

@Composable
private fun GenreChipItem(chip: GenreChip, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "chipScale"
    )

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(Bg3)
            .then(
                if (isFocused) Modifier.border(2.dp, Red, RoundedCornerShape(20.dp))
                else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${chip.emoji} ${chip.label}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * "Continue Watching" row.
 *
 * A Netflix-style horizontal content row built from the user's unfinished
 * titles. Each card shows the poster with a red progress bar across the
 * bottom (how much has been watched) and a "▶ Resume" affordance. Clicking a
 * card launches the player at the saved position via [onResume] instead of
 * opening the detail screen — so the user picks up exactly where they left
 * off with one click, just like Netflix's Continue Watching row.
 *
 * A long-press / secondary action ([onItemClick]) still opens the detail
 * screen, but the primary click is always resume.
 */
@Composable
private fun ContinueWatchingRow(
    items: List<TmdbItem>,
    progressMap: Map<String, WatchProgress>,
    onItemClick: (TmdbItem) -> Unit,
    onResume: (TmdbItem, Long) -> Unit
) {
    val dims = responsiveDims()
    val listState = rememberLazyListState()

    Column(modifier = Modifier.padding(bottom = if (dims.isTv) 28.dp else 18.dp)) {
        Text(
            text = "\u25B6\uFE0F Continue Watching",
            color = TextPrimary,
            fontSize = if (dims.isTv) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.rowPadding, vertical = 8.dp)
        )

        LazyRow(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dims.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                count = items.size,
                key = { idx ->
                    val item = items[idx]
                    "cw_${item.id}_${item.contentType}"
                }
            ) { idx ->
                val item = items[idx]
                // The progressMap is keyed by WatchProgress.key, which for TV
                // shows includes the _S{season}_E{episode} suffix (e.g.
                // "tv_123_S1_E3"). Since the TmdbItem only carries the id +
                // contentType (not the season/episode), we scan the map values
                // and pick the record whose contentType + tmdbId match. For TV
                // this yields the most-recent active episode (activeItems() is
                // sorted by timestamp desc, so the first match is newest).
                val ct = item.contentType
                val wp = progressMap.values.firstOrNull { p ->
                    p.tmdbId == item.id &&
                        p.contentType.equals(ct, ignoreCase = true)
                }
                ContinueWatchingCard(
                    item = item,
                    progress = wp?.progressFraction ?: 0f,
                    resumeLabel = wp?.resumeLabel ?: "",
                    dims = dims,
                    onClick = {
                        // Primary action = resume from saved position.
                        if (wp != null) {
                            onResume(item, wp.positionMs)
                        } else {
                            onItemClick(item)
                        }
                    }
                )
            }
        }
    }
}

/**
 * A content card variant for the Continue Watching row. Identical to
 * [ContentCard] in shape/focus behaviour, but overlays a red progress bar
 * across the bottom of the poster (how much has been watched) and a small
 * "Resume" label, so the user can see at a glance how far they got.
 */
@Composable
private fun ContinueWatchingCard(
    item: TmdbItem,
    progress: Float,
    resumeLabel: String,
    dims: ResponsiveDims,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1.0f,
        label = "cwCardScale"
    )

    val imageRequest = remember(item.posterUrl) {
        ImageRequest.Builder(context)
            .data(item.posterUrl)
            .crossfade(200)
            .build()
    }

    Column(
        modifier = Modifier
            .width(dims.cardWidth)
            .clip(RoundedCornerShape(6.dp))
            .scale(scale)
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

            // Bottom gradient scrim for the progress bar + label legibility.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dims.cardImageHeight)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                PureBlack.copy(alpha = 0.7f)
                            )
                        )
                    )
            )

            // Resume label (top-left) — e.g. "S2 · E5" or the year.
            if (resumeLabel.isNotEmpty()) {
                Text(
                    text = resumeLabel,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Red, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Play icon overlay — always visible on continue-watching cards
            // (the affordance is "resume", not "preview").
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
                    contentDescription = "Resume",
                    tint = Color.White,
                    modifier = Modifier.width(20.dp).height(20.dp)
                )
            }

            // ── Progress bar (the signature Continue Watching affordance) ── //
            // A thin red bar pinned to the bottom of the poster, filled to the
            // watched fraction. Unwatched remainder is a darker track. This is
            // exactly Netflix's continue-watching progress indicator.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
            ) {
                // Time-remaining hint above the bar.
                val pct = (progress * 100).toInt()
                Text(
                    text = "$pct% watched",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp, bottom = 3.dp)
                )
                // The bar itself: a track + a filled portion.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(PureBlack.copy(alpha = 0.8f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(Red)
                    )
                }
            }
        }

        // Caption bar beneath the poster.
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.displayTitle,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
