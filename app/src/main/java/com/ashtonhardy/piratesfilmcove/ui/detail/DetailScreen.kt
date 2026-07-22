package com.ashtonhardy.piratesfilmcove.ui.detail

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
import com.ashtonhardy.piratesfilmcove.ui.components.ContentCard
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg
import com.ashtonhardy.piratesfilmcove.ui.theme.PureBlack
import com.ashtonhardy.piratesfilmcove.ui.theme.PlayWhite
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.ResponsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester

/**
 * Netflix-style detail screen for **movies**.
 *
 * Layout (faithful to Netflix's title detail page):
 *  • Full-bleed backdrop at the top with a left-to-right + bottom-to-black
 *    gradient so the overlaid text stays legible.
 *  • Title, meta row (⭐ rating • year • runtime • "Movie"), overview, and a
 *    white "▶ Play" button that launches [onPlayMovie] → PlayerActivity.
 *  • "More Like This" horizontal row of [ContentCard]s at the bottom.
 *
 * TV shows have their own detail screen built into
 * [com.ashtonhardy.piratesfilmcove.ui.tv.SeasonEpisodePicker] (the detail hero sits at the
 * top of the season/episode browser).
 *
 * No streaming logic is touched here — [onPlayMovie] launches the existing
 * PlayerActivity.
 */
@Composable
fun DetailScreen(
    item: TmdbItem,
    onPlayMovie: (TmdbItem) -> Unit,
    onBack: () -> Unit,
    onItemOpen: (TmdbItem) -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val dims = responsiveDims()
    val movieDetail by viewModel.movieDetail.collectAsStateWithLifecycle()
    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(item.id) { viewModel.load(item) }

    // On a no-pointer TV box, land D-pad focus on the Play button when the
    // movie detail opens so the user always knows where they are.
    val playFocusRequester = rememberInitialFocusRequester()

    // Resolve the best available backdrop + overview + meta while the full
    // detail loads (fall back to the lightweight list item).
    val backdropUrl = movieDetail?.backdropUrl ?: item.backdropUrl
    val overview = movieDetail?.overview ?: item.overview ?: ""
    val ratingText = movieDetail?.ratingText ?: item.ratingText
    val runtimeText = movieDetail?.runtimeText ?: ""
    val year = movieDetail?.year ?: item.year

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // focusGroup(): clamps D-pad focus inside the detail screen so
                // Up from the Play button / similar items can't escape into
                // empty space (nothing focused, user stranded on a no-pointer
                // remote).
                .focusGroup(),
            contentPadding = PaddingValues(bottom = 64.dp)
        ) {
            // ── Backdrop hero ────────────────────────────────────────────
            item(key = "hero") {
                DetailBackdrop(
                    backdropUrl = backdropUrl,
                    title = item.displayTitle,
                    ratingText = ratingText,
                    year = year,
                    runtimeText = runtimeText,
                    overview = overview,
                    onBack = onBack,
                    onPlay = { onPlayMovie(item) },
                    dims = dims,
                    playFocusRequester = playFocusRequester
                )
            }

            // ── More Like This ───────────────────────────────────────────
            if (similar.isNotEmpty()) {
                item(key = "similar_label") {
                    Text(
                        text = "More Like This",
                        color = TextPrimary,
                        fontSize = if (dims.isTv) 24.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            start = dims.rowPadding,
                            top = 24.dp,
                            bottom = 8.dp
                        )
                    )
                }

                item(key = "similar_row") {
                    SimilarRow(
                        items = similar,
                        onItemClick = onItemOpen,
                        dims = dims
                    )
                }
            }

            // ── Loading state for the initial detail fetch ───────────────
            if (isLoading && overview.isBlank()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Red)
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  Backdrop hero with overlaid title / meta / overview / Play button          //
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailBackdrop(
    backdropUrl: String?,
    title: String,
    ratingText: String,
    year: String,
    runtimeText: String,
    overview: String,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    dims: ResponsiveDims,
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
        modifier = Modifier
            .fillMaxWidth()
            .height(dims.heroHeight)
    ) {
        // Backdrop image
        AsyncImage(
            model = backdropRequest,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(PureBlack)
        )

        // Left-to-right dark gradient (Netflix keeps text on the left legible)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            PureBlack.copy(alpha = 0.85f),
                            PureBlack.copy(alpha = 0.4f),
                            PureBlack.copy(alpha = 0.1f)
                        )
                    )
                )
        )

        // Bottom-to-black gradient (blends the hero into the rows below)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
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

        // Back button (top-left)
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

        // Title + meta + overview + Play (bottom-left, overlaid on backdrop)
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

            // Meta row: ⭐ rating • year • runtime • Movie
            val metaParts = buildList {
                if (ratingText.isNotEmpty()) add("⭐ $ratingText")
                if (year.isNotBlank()) add(year)
                if (runtimeText.isNotEmpty()) add(runtimeText)
                add("Movie")
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

            // Play button (Netflix white ▶ Play)
            PlayButton(
                text = "Play",
                onClick = onPlay,
                dims = dims,
                focusRequester = playFocusRequester
            )

            // Overview (Netflix shows the synopsis below the buttons)
            if (overview.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = overview,
                    color = TextPrimary,
                    fontSize = if (dims.isTv) 16.sp else 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = if (dims.isTv) 5 else 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Netflix's signature white "▶ Play" button with D-pad focus support.
 */
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit,
    dims: ResponsiveDims,
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
            text = text,
            color = PureBlack,
            fontSize = if (dims.isTv) 20.sp else 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
//  "More Like This" row                                                        //
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SimilarRow(
    items: List<TmdbItem>,
    onItemClick: (TmdbItem) -> Unit,
    dims: ResponsiveDims
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = dims.rowPadding),
        horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = items,
            key = { "${it.id}_${it.contentType}" }
        ) { similarItem ->
            ContentCard(
                item = similarItem,
                onClick = { onItemClick(similarItem) },
                dims = dims
            )
        }
    }
}
