package com.mariocart.app.ui.tv

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
import com.mariocart.app.ui.util.rememberInitialFocusRequester

@Composable
fun TvScreen(
    onItemClick: (TmdbItem) -> Unit,
    viewModel: TvViewModel = viewModel()
) {
    val popular by viewModel.popular.collectAsState()
    val topRated by viewModel.topRated.collectAsState()
    val dims = responsiveDims()

    // On a no-pointer TV box, land D-pad focus on the first card of the first
    // row so the user has a known starting point when they open TV Shows.
    val firstCardFocusRequester = rememberInitialFocusRequester()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = dims.topContentPadding)
    ) {
        item {
            Text(
                text = "TV Shows",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
        }
        item {
            ContentRow(
                title = "Popular Shows", emoji = "\uD83D\uDCFA",
                items = popular, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMore() },
                firstCardFocusRequester = firstCardFocusRequester
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
