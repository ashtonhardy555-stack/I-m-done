package com.mariocart.app.ui.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
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
    val canLoadMorePopular by viewModel.canLoadMorePopular.collectAsState()
    val canLoadMoreTopRated by viewModel.canLoadMoreTopRated.collectAsState()
    val dims = responsiveDims()

    // On a no-pointer TV box, land D-pad focus on the first card of the first
    // row so the user has a known starting point when they open TV Shows.
    val firstCardFocusRequester = rememberInitialFocusRequester()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // focusGroup(): the row cards are the only focusables here.
            // Without it, D-pad Up from the top row can move focus ABOVE the
            // LazyColumn into empty space — nothing is focused, Enter does
            // nothing, and the user is stranded on a no-pointer remote.
            // focusGroup() makes the focusable children a single focus unit so
            // Up clamps on the first row and Down clamps on the last.
            .focusGroup()
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
                canLoadMore = canLoadMorePopular,
                firstCardFocusRequester = firstCardFocusRequester
            )
        }
        item {
            ContentRow(
                title = "Top Rated Shows", emoji = "\uD83C\uDFC6",
                items = topRated, onItemClick = onItemClick,
                onLoadMore = { viewModel.loadMoreTopRated() },
                canLoadMore = canLoadMoreTopRated
            )
        }
    }
}
