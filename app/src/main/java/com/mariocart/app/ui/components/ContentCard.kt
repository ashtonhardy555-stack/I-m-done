package com.mariocart.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Gold
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted

@Composable
fun ContentCard(
    item: TmdbItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Bg3)
            .clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(140.dp)
                    .height(210.dp)
                    .background(Bg3)
            )

            // TV badge
            if (!item.isMovie) {
                Text(
                    text = "TV",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Red, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Rating badge
            if (item.ratingText.isNotEmpty()) {
                Text(
                    text = "\u2B50 ${item.ratingText}",
                    color = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.displayTitle,
                color = Color(0xFFDDDDDD),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.year,
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
