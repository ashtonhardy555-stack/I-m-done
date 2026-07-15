package com.mariocart.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.model.TvSeason
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary

/**
 * SeasonEpisodePicker
 *
 * Shown when the user taps a TV show. Fetches the show's seasons from TMDB
 * (ContentRepository.getTvDetails) and lets the user choose a season and an
 * episode before launching the player — mirroring the LookMovie Kodi addon's
 * "ListSerial → splitToSeasons → ListEpisodes" flow in the app UI.
 *
 * [onPlay] is invoked with the chosen season + episode number.
 */
@Composable
fun SeasonEpisodePicker(
    item: TmdbItem,
    onPlay: (season: Int, episode: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: SeasonEpisodeViewModel = viewModel()
) {
    val seasons by viewModel.seasons.collectAsState()
    val selectedSeason by viewModel.selectedSeason.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Load season data once for this show.
    LaunchedEffect(item.id) { viewModel.load(item.id) }

    val currentSeason: TvSeason? = seasons.firstOrNull { it.seasonNumber == selectedSeason }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // ── Top bar ────────────────────────────────────────────────────── //
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayTitle,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (item.year.isNotBlank()) {
                    Text(item.year, color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        if (isLoading) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Red) }
            return@Column
        }

        if (seasons.isEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("No season info available.", color = TextMuted) }
            return@Column
        }

        // -- Season selector (horizontally scrollable chips -- ALL seasons) -- //
        // Previously this rendered only the first 8 seasons in a fixed Row
        // and a second Row for the rest, with NO scrolling, so long-running
        // shows (20+ seasons) could not be scrolled through. Now every
        // season lives in a single LazyRow that scrolls horizontally, so the
        // user can reach any season no matter how many there are.
        Text(
            "Season",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(
                items = seasons,
                key = { season -> season.seasonNumber }
            ) { season ->
                SeasonChip(
                    label = season.name ?: "Season ${season.seasonNumber}",
                    isSelected = season.seasonNumber == selectedSeason,
                    onClick = { viewModel.selectSeason(season.seasonNumber) }
                )
            }
        }

        // ── Episode list ───────────────────────────────────────────────── //
        Text(
            "Episodes",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )

        val episodeCount = currentSeason?.episodeCount ?: 0
        if (episodeCount == 0) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("No episodes found for this season.", color = TextMuted) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(episodeCount) { epIndex ->
                    val ep = epIndex + 1
                    EpisodeRow(
                        episodeNumber = ep,
                        title = "Episode $ep",
                        onClick = { onPlay(selectedSeason, ep) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (isSelected) Color.White else Color(0xFFE5E5E5),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) Red else Bg3)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun EpisodeRow(episodeNumber: Int, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Bg3)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Episode number badge
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Red),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$episodeNumber",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = TextMuted
        )
    }
}
