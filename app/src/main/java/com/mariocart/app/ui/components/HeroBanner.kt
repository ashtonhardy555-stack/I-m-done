package com.mariocart.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Gold
import com.mariocart.app.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
fun HeroBanner(
    items: List<TmdbItem>,
    onPlayClick: (TmdbItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    val currentItem = items[currentIndex]

    LaunchedEffect(items.size) {
        while (true) {
            delay(8000)
            currentIndex = (currentIndex + 1) % items.size
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
    ) {
        // Background image
        AsyncImage(
            model = currentItem.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Bg),
                        startY = 200f
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 32.dp, end = 100.dp)
        ) {
            Text(
                text = currentItem.displayTitle,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 32.sp,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentItem.ratingText.isNotEmpty()) {
                    Text(
                        text = "\u2B50 ${currentItem.ratingText}",
                        color = Gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(text = currentItem.year, color = TextMuted, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!currentItem.overview.isNullOrBlank()) {
                Text(
                    text = currentItem.overview,
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPlayClick(currentItem) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("\u25B6  Play", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onPlayClick(currentItem) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6D6D6E).copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("\u2139  More Info", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
