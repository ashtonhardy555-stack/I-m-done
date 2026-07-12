package com.mariocart.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.browse.BrowseScreen
import com.mariocart.app.ui.home.HomeScreen
import com.mariocart.app.ui.movies.MoviesScreen
import com.mariocart.app.ui.player.PlayerActivity
import com.mariocart.app.ui.search.SearchScreen
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg2
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.MarioCartTheme
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.tv.SeasonEpisodePicker
import com.mariocart.app.ui.tv.TvScreen
import com.mariocart.app.ui.updates.UpdatesScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Movies("Movies", Icons.Default.Movie),
    TV("TV", Icons.Default.Tv),
    Browse("Browse", Icons.Default.GridView),
    Updates("Updates", Icons.Default.Upgrade)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MarioCartTheme {
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
    var currentTab by remember { mutableStateOf(Tab.Home) }
    var showSearch by remember { mutableStateOf(false) }
    var selectedTv by remember { mutableStateOf<TmdbItem?>(null) }
    var searchGenre by remember { mutableStateOf<String?>(null) }


    // Check for an update on launch (silent unless one is available)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.mariocart.app.ui.AutoUpdater.checkAndPrompt(context)
    }
    // ── Navigation helpers ──────────────────────────────────────────── //
    fun launchMovie(item: TmdbItem) {
        val intent = PlayerActivity.newIntent(
            context = context,
            tmdbId = item.id,
            contentType = "movie",
            title = item.displayTitle,
            year = item.year
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
            year = item.year
        )
        context.startActivity(intent)
    }

    val onItemClick: (TmdbItem) -> Unit = { item ->
        if (item.isMovie) {
            launchMovie(item)
        } else {
            // TV show → open the season/episode picker first.
            selectedTv = item
        }
    }

    // ── Search overlay takes priority ───────────────────────────────── //
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

    // ── Season / episode picker overlay for TV shows ────────────────── //
    selectedTv?.let { tv ->
        SeasonEpisodePicker(
            item = tv,
            onPlay = { season, episode -> launchTv(tv, season, episode) },
            onBack = { selectedTv = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                currentTab = currentTab,
                onSearchClick = { showSearch = true }
            )
        },
        bottomBar = {
            BottomNav(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        },
        containerColor = Bg
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentTab) {
                Tab.Home -> HomeScreen(
                    onItemClick = onItemClick,
                    onSearchWithGenre = { genreId ->
                        searchGenre = genreId
                        showSearch = true
                    }
                )
                Tab.Movies -> MoviesScreen(onItemClick = onItemClick)
                Tab.TV -> TvScreen(onItemClick = onItemClick)
                Tab.Browse -> BrowseScreen(onItemClick = onItemClick)
                Tab.Updates -> UpdatesScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────── //
//  Top app bar with the app title + search icon                            //
// ─────────────────────────────────────────────────────────────────────── //
@Composable
private fun TopAppBar(currentTab: Tab, onSearchClick: () -> Unit) {
    Surface(color = Bg2, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "I'm Done",
                color = Red,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = TextPrimary
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────── //
//  Bottom navigation bar                                                   //
// ─────────────────────────────────────────────────────────────────────── //
@Composable
private fun BottomNav(currentTab: Tab, onTabSelected: (Tab) -> Unit) {
    Surface(color = Bg2, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Tab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    isSelected = tab == currentTab,
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) Red else TextMuted
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(tab.label, color = color, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}
