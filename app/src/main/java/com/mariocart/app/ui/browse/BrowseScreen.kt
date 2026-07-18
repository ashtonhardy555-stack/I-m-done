package com.mariocart.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mariocart.app.data.model.Genre
import com.mariocart.app.data.model.MOVIE_GENRES
import com.mariocart.app.data.model.TV_GENRES
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentCard
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.util.responsiveDims

@Composable
fun BrowseScreen(
    onItemClick: (TmdbItem) -> Unit,
    viewModel: BrowseViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val allGenres = MOVIE_GENRES + TV_GENRES
    val dims = responsiveDims()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = dims.topContentPadding)
    ) {
        item {
            Text(
                text = "\uD83D\uDDC2\uFE0F Browse by Genre",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
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
                        }
                    )
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(36.dp))
                }
            }
        } else {
            // Display items in a grid-like manner using rows
            val chunked = items.chunked(3)
            items(chunked.size) { rowIndex ->
                val rowItems = chunked[rowIndex]
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { item ->
                        ContentCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Fill remaining space if row isn't full
                    repeat(3 - rowItems.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (items.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { viewModel.loadMore() }) {
                            Text("Load More", color = Red)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenrePill(
    genre: Genre,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = genre.name,
        color = if (isSelected) Color.White else Color(0xFFE5E5E5),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) Red else Bg3)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
