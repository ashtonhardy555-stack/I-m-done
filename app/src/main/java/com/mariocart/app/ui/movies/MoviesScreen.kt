package com.mariocart.app.ui.movies

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentRow
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims

@Composable
fun MoviesScreen(
    onItemClick: (TmdbItem) -> Unit,
    viewModel: MoviesViewModel = viewModel()
) {
    val popular by viewModel.popular.collectAsState()
    val topRated by viewModel.topRated.collectAsState()
    val dims = responsiveDims()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = dims.topContentPadding)
    ) {
        item {
            Text(
                text = "Movies",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
        item {
            ContentRow(
                title = "Popular Movies", emoji = "\uD83C\uDFAC",
                items = popular, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMore() }
            )
        }
        item {
            ContentRow(
                title = "Top Rated", emoji = "\uD83C\uDFC6",
                items = topRated, onItemClick = onItemClick
            )
        }
    }
}
