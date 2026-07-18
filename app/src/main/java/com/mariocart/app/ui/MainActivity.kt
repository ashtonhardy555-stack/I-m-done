package com.mariocart.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.browse.BrowseScreen
import com.mariocart.app.ui.detail.DetailScreen
import com.mariocart.app.ui.home.HomeScreen
import com.mariocart.app.ui.movies.MoviesScreen
import com.mariocart.app.ui.player.PlayerActivity
import com.mariocart.app.ui.search.SearchScreen
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg2
import com.mariocart.app.ui.theme.NetflixTheme
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.tv.SeasonEpisodePicker
import com.mariocart.app.ui.tv.TvScreen
import com.mariocart.app.ui.updates.UpdatesScreen
import com.mariocart.app.ui.util.responsiveDims

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Movies("Movies", Icons.Default.Movie),
    TV("TV Shows", Icons.Default.Tv),
    Browse("Browse", Icons.Default.GridView),
    Updates("Updates", Icons.Default.Upgrade)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        com.mariocart.app.ui.browse.AppContextHolder.context = applicationContext

        setContent {
            NetflixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dims = responsiveDims()
    var currentTab by remember { mutableStateOf(Tab.Home) }
    var showSearch by remember { mutableStateOf(false) }
    var selectedMovie by remember { mutableStateOf<TmdbItem?>(null) }
    var selectedTv by remember { mutableStateOf<TmdbItem?>(null) }
    var searchGenre by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        com.mariocart.app.ui.AutoUpdater.checkAndPrompt(context)
    }

    fun launchMovie(item: TmdbItem) {
        val intent = PlayerActivity.newIntent(
            context = context,
            tmdbId = item.id,
            contentType = "movie",
            title = item.displayTitle,
            year = item.year,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl
        )
        context.startActivity(intent)
    }

    fun launchTv(item: TmdbItem, season: Int, episode: Int) {
        val intent = PlayerActivity.newIntent(
            context = context,
            tmdbId = item.id,
            contentType = "tv",
            season = season,
            episode = episode,
            title = item.displayTitle,
            year = item.year,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl
        )
        context.startActivity(intent)
    }

    // ── Item-open router ───────────────────────────────────────────────
    // Movies → Netflix-style DetailScreen (Play button launches the player).
    // TV shows → Netflix-style SeasonEpisodePicker (detail hero + episodes).
    // This is a stable, remembered lambda so the screen tree below it does
    // NOT recompose every time AppRoot re-renders (scroll perf).
    val onItemClick: (TmdbItem) -> Unit = remember {
        { item ->
            if (item.isMovie) selectedMovie = item
            else selectedTv = item
        }
    }

    // Stable search-with-genre callback (passed into the always-visible main
    // screen tree — kept stable for the same scroll-perf reason).
    val onSearchWithGenre: (String) -> Unit = remember {
        { genreId ->
            searchGenre = genreId
            showSearch = true
        }
    }

    // ── Search overlay ──
    if (showSearch) {
        SearchScreen(
            onItemClick = onItemClick,
            onClose = {
                showSearch = false
                searchGenre = null
            },
            initialGenre = searchGenre
        )
        return
    }

    // ── Movie detail overlay (Netflix-style DetailScreen) ──
    selectedMovie?.let { movie ->
        DetailScreen(
            item = movie,
            onPlayMovie = { launchMovie(it) },
            onBack = { selectedMovie = null },
            onItemOpen = onItemClick
        )
        return
    }

    // ── Season / episode picker overlay (TV detail hero + episodes) ──
    selectedTv?.let { tv ->
        SeasonEpisodePicker(
            item = tv,
            onPlay = { season, episode -> launchTv(tv, season, episode) },
            onBack = { selectedTv = null },
            onItemOpen = onItemClick
        )
        return
    }

    // ── Layout: TV uses a side navigation rail; phones use the Netflix top bar ──
    if (dims.isTv) {
        Row(modifier = Modifier.fillMaxSize().background(Bg)) {
            TvSideNav(
                currentTab = currentTab,
                onTabSelected = { currentTab = it },
                onSearchClick = { showSearch = true },
                dims = dims
            )
            Box(modifier = Modifier.weight(1f)) {
                NetflixScreenSwitch(
                    currentTab = currentTab,
                    onItemClick = onItemClick,
                    onSearchWithGenre = onSearchWithGenre
                )
            }
        }
    } else {
        // Phone: transparent Netflix top bar that fades to solid on tab change.
        Box(modifier = Modifier.fillMaxSize().background(Bg)) {
            NetflixScreenSwitch(
                currentTab = currentTab,
                onItemClick = onItemClick,
                onSearchWithGenre = onSearchWithGenre
            )
            NetflixTopBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it },
                onSearchClick = { showSearch = true }
            )
        }
    }
}

/** Crossfade between the main screens (Netflix-style soft transitions). */
@Composable
private fun NetflixScreenSwitch(
    currentTab: Tab,
    onItemClick: (TmdbItem) -> Unit,
    onSearchWithGenre: (String) -> Unit
) {
    AnimatedContent(
        targetState = currentTab,
        transitionSpec = {
            fadeIn(tween(280)) togetherWith fadeOut(tween(180))
        },
        label = "screenSwitch"
    ) { tab ->
        when (tab) {
            Tab.Home -> HomeScreen(
                onItemClick = onItemClick,
                onSearchWithGenre = onSearchWithGenre
            )
            Tab.Movies -> MoviesScreen(onItemClick = onItemClick)
            Tab.TV -> TvScreen(onItemClick = onItemClick)
            Tab.Browse -> BrowseScreen(onItemClick = onItemClick)
            Tab.Updates -> UpdatesScreen()
        }
    }
}

// ──────────────────────────────────────────────────────────────────── //
//  Netflix top navigation bar (phone)                                 //
//  Transparent over the hero, with a top→bottom black gradient so the //
//  white text always reads. Tabs are D-pad focusable with the red     //
//  underline that Netflix uses for the active section.                //
// ──────────────────────────────────────────────────────────────────── //
@Composable
private fun NetflixTopBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    onSearchClick: () -> Unit
) {
    Surface(color = Color.Transparent, shadowElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Netflix-style wordmark (red).
                Text(
                    text = "NETFLIX",
                    color = Red,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.width(24.dp))
                // Inline nav tabs — Netflix shows these in the top bar.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Tab.entries.forEach { tab ->
                        TopNavTab(
                            tab = tab,
                            isSelected = tab == currentTab,
                            onClick = { onTabSelected(tab) }
                        )
                    }
                }
                IconButton(onClick = onSearchClick) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun TopNavTab(tab: Tab, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val color = if (isSelected) Color.White else TextMuted

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = tab.label,
            color = if (isFocused && !isSelected) TextPrimary else color,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        // Red underline under the active tab (Netflix active-section marker).
        Box(
            modifier = Modifier
                .width(if (isSelected) 24.dp else 0.dp)
                .height(3.dp)
                .background(Red, RoundedCornerShape(2.dp))
        )
    }
}

// ──────────────────────────────────────────────────────────────────── //
//  TV side navigation rail (Android TV)                               //
//  Vertical icon+label rail with the red focus highlight.             //
// ──────────────────────────────────────────────────────────────────── //
@Composable
private fun TvSideNav(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    onSearchClick: () -> Unit,
    dims: com.mariocart.app.ui.util.ResponsiveDims
) {
    Surface(
        color = Bg2,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxHeight()
            .width(120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "NETFLIX",
                color = Red,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            TvNavItem(
                icon = Icons.Default.Search,
                label = "Search",
                isSelected = false,
                onClick = onSearchClick
            )
            Tab.entries.forEach { tab ->
                TvNavItem(
                    icon = tab.icon,
                    label = tab.label,
                    isSelected = tab == currentTab,
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun TvNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val color = if (isSelected) Red else TextPrimary
    val highlight = isFocused || isSelected

    Column(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlight) Red.copy(alpha = 0.15f) else Color.Transparent)
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, Red, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = color,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
