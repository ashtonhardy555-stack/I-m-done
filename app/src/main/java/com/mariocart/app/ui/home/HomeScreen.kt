package com.mariocart.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentRow
import com.mariocart.app.ui.components.HeroBanner
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Red

private data class GenreChip(val emoji: String, val label: String, val genreId: String)

private val GENRE_CHIPS = listOf(
    GenreChip("\uD83D\uDD25", "Trending",    ""),
    GenreChip("\uD83C\uDFAC", "Action",      "28"),
    GenreChip("\uD83D\uDE02", "Comedy",      "35"),
    GenreChip("\uD83D\uDC7B", "Horror",      "27"),
    GenreChip("\uD83D\uDE80", "Sci-Fi",      "878"),
    GenreChip("\uD83C\uDFAD", "Drama",       "18"),
    GenreChip("\uD83D\uDD2A", "Thriller",    "53"),
    GenreChip("\uD83C\uDF00", "Animation",   "16"),
    GenreChip("\uD83D\uDC95", "Romance",     "10749"),
    GenreChip("\uD83D\uDD75", "Crime",       "80"),
    GenreChip("\uD83C\uDF0D", "Adventure",   "12"),
    GenreChip("\uD83D\uDCFA", "TV Action",   "10759"),
    GenreChip("\uD83D\uDCD6", "Documentary", "99"),
)

@Composable
fun HomeScreen(
    onItemClick: (TmdbItem) -> Unit,
    onSearchWithGenre: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val heroItems by viewModel.heroItems.collectAsState()
    val trending by viewModel.trending.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val popularTV by viewModel.popularTV.collectAsState()
    val topRated by viewModel.topRated.collectAsState()
    val popularMovies by viewModel.popularMovies.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            HeroBanner(items = heroItems, onPlayClick = onItemClick)
        }
        item {
            GenreSuggestionsBar(onGenreClick = onSearchWithGenre)
        }
        item {
            ContentRow(
                title = "Trending Now", emoji = "\uD83D\uDD25",
                items = trending, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreTrending() }
            )
        }
        item {
            ContentRow(
                title = "New in Theatres", emoji = "\uD83C\uDFAC",
                items = nowPlaying, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreNowPlaying() }
            )
        }
        item {
            ContentRow(
                title = "Popular TV Shows", emoji = "\uD83D\uDCFA",
                items = popularTV, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMorePopularTV() }
            )
        }
        item {
            ContentRow(
                title = "Top Rated Movies", emoji = "\u2B50",
                items = topRated, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreTopRated() }
            )
        }
        item {
            ContentRow(
                title = "Popular Movies", emoji = "\uD83C\uDF1F",
                items = popularMovies, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMorePopularMovies() }
            )
        }
    }
}

@Composable
private fun GenreSuggestionsBar(onGenreClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Quick Browse",
            color = Color.White,
            fontSize = 14.sp,
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Bg3)
            .clickable(onClick = onClick)
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
