package com.ashtonhardy.piratesfilmcove.ui.home

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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.model.WatchProgress
import com.ashtonhardy.piratesfilmcove.ui.components.ContentRow
import com.ashtonhardy.piratesfilmcove.ui.components.HeroBanner
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.PureBlack
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims

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
    val canTrending by viewModel.canLoadMoreTrending.collectAsState()
    val canNowPlaying by viewModel.canLoadMoreNowPlaying.collectAsState()
    val canTV by viewModel.canLoadMorePopularTV.collectAsState()
    val canMovies by viewModel.canLoadMorePopularMovies.collectAsState()
    val canRecommended by viewModel.canLoadMoreRecommended.collectAsState()
    val dims = responsiveDims()
    val playFocusRequester = rememberInitialFocusRequester()

    LaunchedEffect(Unit) { viewModel.refreshContinueWatching() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().focusGroup().padding(top = dims.safeAreaTop)
    ) {
        item {
            HeroBanner(
                items = heroItems,
                onPlayClick = onItemClick,
                onMoreInfo = onItemClick,
                playFocusRequester = playFocusRequester
            )
        }
        if (recommended.isNotEmpty()) item {
            ContentRow(
                title = "Recommended for You", emoji = "⭐", items = recommended,
                onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreRecommended() },
                canLoadMore = canRecommended
            )
        }
        item { GenreSuggestionsBar(onGenreClick = onSearchWithGenre) }
        item {
            ContentRow("Trending Now", "🔥", trending, onItemClick,
                onLoadMore = { viewModel.loadMoreTrending() }, canLoadMore = canTrending)
        }
        item {
            ContentRow("New in Theatres", "🎬", nowPlaying, onItemClick,
                onLoadMore = { viewModel.loadMoreNowPlaying() }, canLoadMore = canNowPlaying)
        }
        if (continueWatching.isNotEmpty()) item {
            ContinueWatchingRow(continueWatching, progressMap, onItemClick, onResume)
        }
        item {
            ContentRow("Popular TV Shows", "📺", popularTV, onItemClick,
                onLoadMore = { viewModel.loadMorePopularTV() }, canLoadMore = canTV)
        }
        item {
            ContentRow("Popular Movies", "🎬", popularMovies, onItemClick,
                onLoadMore = { viewModel.loadMorePopularMovies() }, canLoadMore = canMovies)
        }
    }
}

@Composable
private fun GenreSuggestionsBar(onGenreClick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Browse by Category", color = TextPrimary, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GENRE_CHIPS.forEach { chip -> GenreChipItem(chip) { onGenreClick(chip.genreId) } }
        }
    }
}

@Composable
private fun GenreChipItem(chip: GenreChip, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Bg3)
            .then(if (focused) Modifier.border(2.dp, Red, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = "${chip.emoji} ${chip.label}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<TmdbItem>, progressMap: Map<String, WatchProgress>,
    onItemClick: (TmdbItem) -> Unit, onResume: (TmdbItem, Long, Int, Int) -> Unit
) {
    val dims = responsiveDims()
    val state = rememberLazyListState()
    Column(Modifier.padding(bottom = if (dims.isTv) 28.dp else 18.dp)) {
        Text(
            text = "▶️ Continue Watching", color = TextPrimary,
            fontSize = if (dims.isTv) 22.sp else 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.rowPadding, vertical = 8.dp)
        )
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = dims.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing)
        ) {
            items(items, key = { "cw_${it.id}_${it.contentType}" }) { item ->
                val progress = progressMap.values.firstOrNull {
                    it.tmdbId == item.id && it.contentType.equals(item.contentType, ignoreCase = true)
                }
                ContinueWatchingCard(
                    item = item, progress = progress?.progressFraction ?: 0f,
                    resumeLabel = progress?.resumeLabel ?: "", dims = dims,
                    onClick = {
                        if (progress != null) onResume(item, progress.positionMs, progress.season, progress.episode)
                        else onItemClick(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: TmdbItem, progress: Float, resumeLabel: String, dims: ResponsiveDims, onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(
        modifier = Modifier.width(dims.cardWidth).clip(RoundedCornerShape(6.dp)).background(Bg3)
            .then(if (focused) Modifier.border(3.dp, Red, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) {
        Box(Modifier.width(dims.cardWidth).height(dims.cardImageHeight)) {
            AsyncImage(
                model = item.posterUrl, contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(PureBlack)
            )
            if (resumeLabel.isNotEmpty()) {
                Text(
                    text = resumeLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Red, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Text(text = "${(progress * 100).toInt()}% watched", color = Color.White,
                    fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp, bottom = 3.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(PureBlack.copy(alpha = .8f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Red))
                }
            }
        }
        Text(
            text = item.displayTitle, color = TextPrimary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
    }
}
