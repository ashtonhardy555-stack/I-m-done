package com.mariocart.app.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentRow
import com.mariocart.app.ui.components.HeroBanner

@Composable
fun HomeScreen(
    onItemClick: (TmdbItem) -> Unit,
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
