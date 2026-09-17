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
import com.ashtonhardy.piratesfilmcove.ui.components.ContentRow
import com.ashtonhardy.piratesfilmcove.ui.components.HeroBanner
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.ashtonhardy.piratesfilmcove.ui.theme.PureBlack
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon

private data class GenreChip(val emoji: String, val label: String, val genreId: String)

private val GENRE_CHIPS = listOf(
    GenreChip("🔥", "Trending", ""), GenreChip("🎬", "Action", "28"),
    GenreChip("😂", "Comedy", "35"), GenreChip("👻", "Horror", "27"),
    GenreChip("🚀", "Sci-Fi", "878"), GenreChip("🎭", "Drama", "18"),
    GenreChip("🔪", "Thriller", "53"), GenreChip("🌀", "Animation", "16"),
    GenreChip("💕", "Romance", "10749"), GenreChip("🕵", "Crime", "80"),
    GenreChip("🌍", "Adventure", "12"), GenreChip("📺", "TV Action", "10759"),
    GenreChip("📖", "Documentary", "99")
)

@Composable
fun HomeScreen(
    onItemClick: (TmdbItem) -> Unit,
    onSearchWithGenre: (String) -> Unit = {},
    onResume: (TmdbItem, Long, Int, Int) -> Unit = { _, _, _, _ -> },
    viewModel: HomeViewModel = viewModel()
) {
    val heroItems by viewModel.heroItems.collectAsState()
    val trending by viewModel.trending.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val popularTV by viewModel.popularTV.collectAsState()
    val popularMovies by viewModel.popularMovies.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val recommended by viewModel.recommended.collectAsState()
    val progressMap by viewModel.progressMap.collectAsState()
    val canLoadMoreTrending by viewModel.canLoadMoreTrending.collectAsState()
    val canLoadMoreNowPlaying by viewModel.canLoadMoreNowPlaying.collectAsState()
    val canLoadMorePopularTV by viewModel.canLoadMorePopularTV.collectAsState()
    val canLoadMorePopularMovies by viewModel.canLoadMorePopularMovies.collectAsState()
    val canLoadMoreRecommended by viewModel.canLoadMoreRecommended.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshContinueWatching() }
    val playFocusRequester = rememberInitialFocusRequester()
    val dims = responsiveDims()

    LazyColumn(
        modifier = Modifier.fillMaxSize().focusGroup().padding(top = dims.safeAreaTop)
    ) {
        item { HeroBanner(heroItems, onItemClick, onItemClick, playFocusRequester) }
        if (recommended.isNotEmpty()) item {
            ContentRow("Recommended for You", "⭐", recommended, onItemClick,
                onLoadMore = { viewModel.loadMoreRecommended() }, canLoadMore = canLoadMoreRecommended)
        }
        item { GenreSuggestionsBar(onSearchWithGenre) }
        item {
            ContentRow("Trending Now", "🔥", trending, onItemClick,
                onLoadMore = { viewModel.loadMoreTrending() }, canLoadMore = canLoadMoreTrending)
        }
        item {
            ContentRow("New in Theatres", "🎬", nowPlaying, onItemClick,
                onLoadMore = { viewModel.loadMoreNowPlaying() }, canLoadMore = canLoadMoreNowPlaying)
        }
        // Continue Watching is deliberately placed in the middle of the feed,
        // before TV discovery, so it is visible without scrolling to the end.
        if (continueWatching.isNotEmpty()) item {
            ContinueWatchingRow(continueWatching, progressMap, onItemClick, onResume)
        }
        item {
            ContentRow("Popular TV Shows", "📺", popularTV, onItemClick,
                onLoadMore = { viewModel.loadMorePopularTV() }, canLoadMore = canLoadMorePopularTV)
        }
        item {
            ContentRow("Popular Movies", "🎬", popularMovies, onItemClick,
                onLoadMore = { viewModel.loadMorePopularMovies() }, canLoadMore = canLoadMorePopularMovies)
        }
    }
}

@Composable
private fun GenreSuggestionsBar(onGenreClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Browse by Category", color = TextPrimary, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GENRE_CHIPS.forEach { chip -> GenreChipItem(chip) { onGenreClick(chip.genreId) } }
        }
    }
}

@Composable
private fun GenreChipItem(chip: GenreChip, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Bg3)
        .then(if (focused) Modifier.border(2.dp, Red, RoundedCornerShape(20.dp)) else Modifier)
        .clickable(source, indication = null, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("${chip.emoji} ${chip.label}", Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ContinueWatchingRow(items: List<TmdbItem>, progressMap: Map<String, WatchProgress>, onItemClick: (TmdbItem) -> Unit, onResume: (TmdbItem, Long, Int, Int) -> Unit) {
    val dims = responsiveDims()
    val state = rememberLazyListState()
    Column(Modifier.padding(bottom = if (dims.isTv) 28.dp else 18.dp)) {
        Text("▶️ Continue Watching", color = TextPrimary, fontSize = if (dims.isTv) 22.sp else 18.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(horizontal = dims.rowPadding, vertical = 8.dp))
        LazyRow(state = state, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dims.rowPadding), horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing)) {
            items(items, key = { "cw_${it.id}_${it.contentType}" }) { item ->
                val wp = progressMap.values.firstOrNull { p -> p.tmdbId == item.id && p.contentType.equals(item.contentType, true) }
                ContinueWatchingCard(item, wp?.progressFraction ?: 0f, wp?.resumeLabel ?: "", dims) {
                    if (wp != null) onResume(item, wp.positionMs, wp.season, wp.episode) else onItemClick(item)
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(item: TmdbItem, progress: Float, resumeLabel: String, dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims, onClick: () -> Unit) {
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(Modifier.width(dims.cardWidth).clip(RoundedCornerShape(6.dp)).background(Bg3)
        .then(if (focused) Modifier.border(3.dp, Red, RoundedCornerShape(6.dp)) else Modifier)
        .clickable(source, indication = null, onClick = onClick)) {
        Box(Modifier.width(dims.cardWidth).height(dims.cardImageHeight)) {
            AsyncImage(model = remember(item.posterUrl) { ImageRequest.Builder(context).data(item.posterUrl).crossfade(200).build() },
                contentDescription = item.displayTitle, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(PureBlack))
            if (resumeLabel.isNotEmpty()) Text(resumeLabel, Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Red, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Icon(Icons.Default.PlayArrow, "Resume", tint = Color.White, modifier = Modifier.align(Alignment.Center).background(PureBlack.copy(alpha = .65f), RoundedCornerShape(50)).padding(10.dp))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Text("${(progress * 100).toInt()}% watched", Color.White, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(PureBlack.copy(alpha = .8f))) { Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Red)) }
            }
        }
        Text(item.displayTitle, TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
    }
}
