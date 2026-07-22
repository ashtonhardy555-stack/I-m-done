package com.ashtonhardy.piratesfilmcove.ui.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.model.TvEpisode
import com.ashtonhardy.piratesfilmcove.data.model.TvSeason
import com.ashtonhardy.piratesfilmcove.ui.components.ContentCard
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.PureBlack
import com.ashtonhardy.piratesfilmcove.ui.theme.PlayWhite
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester

/**
 * SeasonEpisodePicker — Netflix-style TV show detail + season/episode browser.
 *
 * Layout (faithful to Netflix's show detail page):
 *  • Full-bleed backdrop hero at the top with the show title, meta row
 *    (rating • year • seasons • runtime), overview, and a "▶ Play" button
 *    that launches the first episode of the selected season.
 *  • Horizontal season selector (chips).
 *  • Vertical top-down episode list starting at Episode 1. Each episode row:
 *      – the episode NUMBER beside the thumbnail (large, on the left)
 *      – the episode THUMBNAIL (still) as the clickable play button
 *      – the episode TITLE + meta (runtime / air date) beside the thumbnail
 *      – the episode DESCRIPTION below the thumbnail
 *  • "More Like This" row at the bottom.
 *
 * [onPlay] is invoked with the chosen season + episode number, launching the
 * existing PlayerActivity — no streaming logic is touched.
 */
@Composable
fun SeasonEpisodePicker(
    item: TmdbItem,
    onPlay: (season: Int, episode: Int) -> Unit,
    onBack: () -> Unit,
    onItemOpen: (TmdbItem) -> Unit = {},
    viewModel: SeasonEpisodeViewModel = viewModel()
) {
    val dims = responsiveDims()
    val seasons by viewModel.seasons.collectAsStateWithLifecycle()
    val selectedSeason by viewModel.selectedSeason.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val showDetail by viewModel.showDetail.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val isLoadingEpisodes by viewModel.isLoadingEpisodes.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()

    LaunchedEffect(item.id) { viewModel.load(item.id) }

    // On a no-pointer TV box, land D-pad focus on the Play button when the
    // show detail opens so the user always knows where they are.
    val playFocusRequester = rememberInitialFocusRequester()

    // Resolve best-available detail fields (fall back to the list item).
    val backdropUrl = showDetail?.backdropUrl ?: item.backdropUrl
    val overview = showDetail?.overview ?: item.overview ?: ""
    val ratingText = showDetail?.ratingText ?: item.ratingText
    val year = showDetail?.year ?: item.year
    val runtimeText = showDetail?.runtimeText ?: ""
    val seasonCount = showDetail?.numberOfSeasons
        ?: seasons.size.let { if (it > 0) it else null }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            // focusGroup(): clamps D-pad focus inside the show detail / episode
            // picker so Up from the Play button / episode list can't escape
            // into empty space (nothing focused, user stranded on a no-pointer
            // remote).
            .focusGroup(),
        contentPadding = PaddingValues(bottom = 64.dp)
    ) {
        // ── Backdrop hero with show detail ──────────────────────────────
        item(key = "hero") {
            ShowDetailHero(
                backdropUrl = backdropUrl,
                title = item.displayTitle,
                ratingText = ratingText,
                year = year,
                runtimeText = runtimeText,
                seasonCount = seasonCount,
                overview = overview,
                onBack = onBack,
                onPlay = {
                    val firstEp = episodes.firstOrNull()?.episodeNumber ?: 1
                    onPlay(selectedSeason, firstEp)
                },
                dims = dims,
                playFocusRequester = playFocusRequester
            )
        }

        // ── Loading state ───────────────────────────────────────────────
        if (isLoading && seasons.isEmpty()) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = Red) }
            }
        }

        // ── Season selector ─────────────────────────────────────────────
        if (seasons.isNotEmpty()) {
            item(key = "seasons_label") {
                Text(
                    text = "Episodes",
                    color = TextPrimary,
                    fontSize = if (dims.isTv) 24.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        start = dims.rowPadding,
                        top = 16.dp,
                        bottom = 8.dp
                    )
                )
            }

            item(key = "season_chips") {
                SeasonSelectorRow(
                    seasons = seasons,
                    selectedSeason = selectedSeason,
                    onSelect = { seasonNum ->
                        viewModel.selectSeason(item.id, seasonNum)
                    },
                    dims = dims
                )
            }
        }

        // ── Episode list (top-down, numbered, thumbnails + descriptions) ─
        item(key = "episode_list") {
            EpisodeListSection(
                episodes = episodes,
                isLoading = isLoadingEpisodes,
                onPlay = { ep -> onPlay(selectedSeason, ep.episodeNumber) },
                dims = dims
            )
        }

        // ── More Like This ──────────────────────────────────────────────
        if (similar.isNotEmpty()) {
            item(key = "similar_label") {
                Text(
                    text = "More Like This",
                    color = TextPrimary,
                    fontSize = if (dims.isTv) 24.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        start = dims.rowPadding,
                        top = 20.dp,
                        bottom = 8.dp
                    )
                )
            }

            item(key = "similar_row") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = dims.rowPadding),
                    horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(
                        items = similar,
                        key = { "${it.id}_${it.contentType}" }
                    ) { similarItem ->
                        ContentCard(
                            item = similarItem,
                            onClick = { onItemOpen(similarItem) },
                            dims = dims
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Backdrop hero with show detail + Play button                                //
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShowDetailHero(
    backdropUrl: String?,
    title: String,
    ratingText: String,
    year: String,
    runtimeText: String,
    seasonCount: Int?,
    overview: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims,
    playFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val backdropRequest = remember(backdropUrl) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(300)
            .build()
    }

    Box(
        modifier = Modifier.fillMaxWidth().height(dims.heroHeight)
    ) {
        AsyncImage(
            model = backdropRequest,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(PureBlack)
        )

        // Left-to-right dark gradient
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    colors = listOf(
                        PureBlack.copy(alpha = 0.85f),
                        PureBlack.copy(alpha = 0.4f),
                        PureBlack.copy(alpha = 0.1f)
                    )
                )
            )
        )

        // Bottom-to-black gradient (blends into the episode list)
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        PureBlack.copy(alpha = 0.5f),
                        Bg
                    )
                )
            )
        )

        // Back button
        val backSrc = remember { MutableInteractionSource() }
        val backFocused by backSrc.collectIsFocusedAsState()
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .then(
                    if (backFocused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                    else Modifier
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = PlayWhite,
                modifier = Modifier.size(28.dp)
            )
        }

        // Title + meta + Play + overview
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = dims.rowPadding,
                    end = dims.rowPadding,
                    bottom = if (dims.isTv) 36.dp else 24.dp
                )
        ) {
            Text(
                text = title,
                color = PlayWhite,
                fontSize = if (dims.isTv) 42.sp else 30.sp,
                fontWeight = FontWeight.Black,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))

            val metaParts = buildList {
                if (ratingText.isNotEmpty()) add("⭐ $ratingText")
                if (year.isNotBlank()) add(year)
                if (seasonCount != null && seasonCount > 0) add("$seasonCount Season${if (seasonCount > 1) "s" else ""}")
                if (runtimeText.isNotEmpty()) add(runtimeText)
                add("Series")
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString("  •  "),
                    color = TextMuted,
                    fontSize = if (dims.isTv) 16.sp else 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
            }

            // Play button (white, Netflix-style)
            PlayButton(onClick = onPlay, dims = dims, focusRequester = playFocusRequester)

            if (overview.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = overview,
                    color = TextPrimary,
                    fontSize = if (dims.isTv) 16.sp else 14.sp,
                    maxLines = if (dims.isTv) 5 else 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PlayButton(
    onClick: () -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(180),
        label = "playBtnScale"
    )

    Row(
        modifier = Modifier
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .background(PlayWhite)
            .then(
                if (isFocused) {
                    Modifier.border(3.dp, Red, RoundedCornerShape(6.dp))
                } else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (dims.isTv) 36.dp else 28.dp,
                vertical = if (dims.isTv) 14.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = PureBlack,
            modifier = Modifier.size(if (dims.isTv) 28.dp else 22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Play",
            color = PureBlack,
            fontSize = if (dims.isTv) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Season selector (horizontal chips)                                          //
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun SeasonSelectorRow(
    seasons: List<TvSeason>,
    selectedSeason: Int,
    onSelect: (Int) -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = dims.rowPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = seasons,
            key = { it.seasonNumber }
        ) { season ->
            SeasonChip(
                label = season.name ?: "Season ${season.seasonNumber}",
                isSelected = season.seasonNumber == selectedSeason,
                onClick = { onSelect(season.seasonNumber) },
                dims = dims
            )
        }
    }
}

@Composable
private fun SeasonChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Red else Bg3)
            .then(
                if (isFocused && !isSelected) {
                    Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                } else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = if (dims.isTv) 20.dp else 16.dp, vertical = if (dims.isTv) 10.dp else 8.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) PlayWhite else TextPrimary,
            fontSize = if (dims.isTv) 16.sp else 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
//  Episode list — top-down, numbered, each episode is a thumbnail button with  //
//  the episode number BESIDE it and the description BELOW it.                  //
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun EpisodeListSection(
    episodes: List<TvEpisode>,
    isLoading: Boolean,
    onPlay: (TvEpisode) -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
) {
    if (isLoading && episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 24.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = Red) }
        return
    }

    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No episodes found for this season.", color = TextMuted, fontSize = 15.sp)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = dims.rowPadding)
    ) {
        episodes.forEach { episode ->
            EpisodeRow(
                episode = episode,
                onPlay = { onPlay(episode) },
                dims = dims
            )
            Spacer(Modifier.height(if (dims.isTv) 16.dp else 12.dp))
        }
    }
}

/**
 * A single episode row — Netflix-style:
 *
 *  ┌──────────┬─────────────────────────┐
 *  │          │  Episode title • meta   │
 *  │  1       │  [thumbnail button]     │   ← number BESIDE thumbnail
 *  │          │                         │
 *  ├──────────┴─────────────────────────┤
 *  │  Episode description (overview)    │   ← description BELOW
 *  └────────────────────────────────────┘
 *
 * The number is a large bold numeral on the left. The thumbnail (still) is
 * the clickable button. The episode title + meta sit beside the thumbnail.
 * The episode description sits below the whole row.
 */
@Composable
private fun EpisodeRow(
    episode: TvEpisode,
    onPlay: () -> Unit,
    dims: com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1.0f,
        animationSpec = tween(160),
        label = "epScale"
    )

    val stillRequest = remember(episode.stillUrl) {
        ImageRequest.Builder(context)
            .data(episode.stillUrl)
            .crossfade(200)
            .build()
    }

    val thumbWidth = if (dims.isTv) 240.dp else 160.dp
    val thumbHeight = thumbWidth * 9f / 16f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(Bg3.copy(alpha = 0.5f))
            .padding(if (dims.isTv) 12.dp else 10.dp)
    ) {
        // ── Top row: number + thumbnail + title/meta ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Episode NUMBER beside the thumbnail (large, bold)
            Text(
                text = "${episode.episodeNumber}",
                color = TextMuted,
                fontSize = if (dims.isTv) 40.sp else 30.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.width(if (dims.isTv) 56.dp else 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.width(if (dims.isTv) 12.dp else 8.dp))

            // Thumbnail as the clickable play button
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PureBlack)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onPlay
                    )
            ) {
                AsyncImage(
                    model = stillRequest,
                    contentDescription = episode.name ?: "Episode ${episode.episodeNumber}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Bottom gradient for the play icon legibility
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                PureBlack.copy(alpha = 0.6f)
                            )
                        )
                    )
                )

                // Play icon overlay (always visible, brighter on focus)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(50))
                        .background(PureBlack.copy(if (isFocused) 0.5f else 0.35f))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play episode ${episode.episodeNumber}",
                        tint = PlayWhite,
                        modifier = Modifier.size(if (dims.isTv) 26.dp else 20.dp)
                    )
                }
            }

            Spacer(Modifier.width(if (dims.isTv) 16.dp else 12.dp))

            // Title + meta beside the thumbnail
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name ?: "Episode ${episode.episodeNumber}",
                    color = TextPrimary,
                    fontSize = if (dims.isTv) 18.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val metaParts = buildList {
                    if (episode.runtimeText.isNotEmpty()) add(episode.runtimeText)
                    if (!episode.airDate.isNullOrBlank()) add(episode.airDate)
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        color = TextMuted,
                        fontSize = if (dims.isTv) 14.sp else 12.sp
                    )
                }
            }
        }

        // ── Episode description BELOW ──
        if (!episode.overview.isNullOrBlank()) {
            Spacer(Modifier.height(if (dims.isTv) 10.dp else 8.dp))
            Text(
                text = episode.overview,
                color = TextMuted,
                fontSize = if (dims.isTv) 14.sp else 13.sp,
                maxLines = if (dims.isTv) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (dims.isTv) 68.dp else 48.dp)
            )
        }
    }
}
