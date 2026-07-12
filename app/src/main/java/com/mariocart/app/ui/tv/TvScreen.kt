package com.mariocart.app.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentRow

@Composable
fun TvScreen(
    onItemClick: (TmdbItem) -> Unit,
    viewModel: TvViewModel = viewModel()
) {
    val popular by viewModel.popular.collectAsState()
    val airingToday by viewModel.airingToday.collectAsState()
    val topRated by viewModel.topRated.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        item {
            ContentRow(
                title = "Popular Shows", emoji = "\uD83D\uDCFA",
                items = popular, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMore() }
            )
        }
        item {
            ContentRow(
                title = "Airing Today", emoji = "\uD83D\uDCE1",
                items = airingToday, onItemClick = onItemClick
            )
        }
        item {
            ContentRow(
                title = "Top Rated Shows", emoji = "\uD83C\uDFC6",
                items = topRated, onItemClick = onItemClick
            )
        }
    }
}
