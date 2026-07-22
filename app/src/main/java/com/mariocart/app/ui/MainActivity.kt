package com.mariocart.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.NetflixTheme
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.tv.SeasonEpisodePicker
import com.mariocart.app.ui.tv.TvScreen
import com.mariocart.app.ui.updates.UpdatesScreen
import com.mariocart.app.ui.util.isTvDevice
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

    // The app now uses a single transparent overlay top bar on BOTH phone
    // and TV (the old TV side rail was removed). There is no side rail to
    // pop open, so pressing D-pad Left on the home screen ONLY navigates
    // the horizontal content rows — exactly what you want when scrolling
    // back through movies you passed. The hero banner is fully visible
    // because nothing overlays its left edge.

    LaunchedEffect(Unit) {
        com.mariocart.app.ui.AutoUpdater.checkAndPrompt(context)
    }

    // ── One-time "Buy Me a Coffee" prompt (phone only) ────────────────── //
    // Shows a small support dialog the first time the app is opened on a
    // phone/tablet. Never shown on Android TV (lean-back UI, no good place
    // for a tap-through CTA). A SharedPreferences flag ("bmc_prompt_seen")
    // guarantees it only ever appears once per install; the persistent link
    // in Updates remains for anyone who dismisses it.
    val bmcPrefs = remember {
        context.getSharedPreferences("support_prefs", android.content.Context.MODE_PRIVATE)
    }
    // isTvDevice() is @Composable, so it must be called in the composable body
    // (not inside a remember {} lambda, where composable calls are forbidden).
    val isTv = isTvDevice()
    var showBmcDialog by remember {
        mutableStateOf(
            !isTv && !bmcPrefs.getBoolean("bmc_prompt_seen", false)
        )
    }
    val onBmcDismiss: () -> Unit = {
        bmcPrefs.edit().putBoolean("bmc_prompt_seen", true).apply()
        showBmcDialog = false
    }
    val onBmcOpen: () -> Unit = {
        bmcPrefs.edit().putBoolean("bmc_prompt_seen", true).apply()
        showBmcDialog = false
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/Bobyyy555"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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

    /**
     * Launches the player at a saved resume position — used by the Continue
     * Watching row. For TV shows the season/episode are looked up from the
     * stored WatchProgress so the user lands on the exact episode they were
     * watching, at the exact position. For movies it's a straight resume.
     */
    fun launchResume(item: TmdbItem, positionMs: Long) {
        if (item.isMovie) {
            val intent = PlayerActivity.newIntent(
                context = context,
                tmdbId = item.id,
                contentType = "movie",
                title = item.displayTitle,
                year = item.year,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                resumePositionMs = positionMs
            )
            context.startActivity(intent)
        } else {
            // TV: look up the stored season/episode so we resume the right
            // episode (the Continue Watching card may represent S2 E5, etc.).
            val wp = com.mariocart.app.data.repository.WatchProgressStore
                .get("tv_${item.id}")
            val season = wp?.season ?: 1
            val episode = wp?.episode ?: 1
            val intent = PlayerActivity.newIntent(
                context = context,
                tmdbId = item.id,
                contentType = "tv",
                season = season,
                episode = episode,
                title = item.displayTitle,
                year = item.year,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                resumePositionMs = positionMs
            )
            context.startActivity(intent)
        }
    }

    // ── Item-open router ───────────────────────────────────────────────
    // Movies → Netflix-style DetailScreen (Play button launches the player).
    // TV shows → Netflix-style SeasonEpisodePicker (detail hero + episodes).
    // This is a stable, remembered lambda so the screen tree below it does
    // NOT recompose every time AppRoot re-renders (scroll perf).
    val onItemClick: (TmdbItem) -> Unit = remember {
        { item ->
            showSearch = false
            searchGenre = null
            if (item.isMovie) selectedMovie = item
            else selectedTv = item
        }
    }

    // ── Continue Watching resume handler ─────────────────────────── //
    // Launches the player directly at the saved position (bypassing the
    // detail screen) so a Continue Watching card resumes in one click.
    val onResume: (TmdbItem, Long) -> Unit = remember {
        { item, positionMs -> launchResume(item, positionMs) }
    }

    // Stable search-with-genre callback (passed into the always-visible main
    // screen tree — kept stable for the same scroll-perf reason).
    val onSearchWithGenre: (String) -> Unit = remember {
        { genreId ->
            searchGenre = genreId
            showSearch = true
        }
    }

    // ── Back-button hierarchy ──────────────────────────────────────────────
    // On a TV remote the Back button should NEVER instantly quit the app.
    // It peels off overlays one layer at a time: detail → search → base.
    // Only at the base screen do we let the system handle back (exit).
    BackHandler(enabled = selectedTv != null) { selectedTv = null }
    BackHandler(enabled = selectedMovie != null) { selectedMovie = null }
    BackHandler(enabled = showSearch) {
        showSearch = false
        searchGenre = null
    }

    // ── One-time Buy Me a Coffee dialog (phone, first launch only) ────── //
    if (showBmcDialog) {
        BuyMeCoffeeDialog(onOpen = onBmcOpen, onDismiss = onBmcDismiss)
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

    // ── Unified layout: a single transparent overlay top bar for BOTH phone
    //    and TV. The old TV side rail was removed so that pressing D-pad Left
    //    on the home screen ONLY navigates the horizontal content rows
    //    (scrolling back through movies) — there is no sidebar to pop open and
    //    cover the hero banner. The top bar overlays the hero on Home with a
    //    transparent gradient (so the full hero stays visible) and floats
    //    above the solid content on other tabs (whose first row is pushed down
    //    by topContentPadding to clear it).
    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        NetflixScreenSwitch(
            currentTab = currentTab,
            onItemClick = onItemClick,
            onSearchWithGenre = onSearchWithGenre,
            onResume = onResume
        )
        NetflixTopBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            onSearchClick = { showSearch = true },
            isTv = dims.isTv
        )
    }
}

/** Crossfade between the main screens (Netflix-style soft transitions). */
@Composable
private fun NetflixScreenSwitch(
    currentTab: Tab,
    onItemClick: (TmdbItem) -> Unit,
    onSearchWithGenre: (String) -> Unit,
    onResume: (TmdbItem, Long) -> Unit
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
                onSearchWithGenre = onSearchWithGenre,
                onResume = onResume
            )
            Tab.Movies -> MoviesScreen(onItemClick = onItemClick)
            Tab.TV -> TvScreen(onItemClick = onItemClick)
            Tab.Browse -> BrowseScreen(onItemClick = onItemClick)
            Tab.Updates -> UpdatesScreen()
        }
    }
}

// ──────────────────────────────────────────────────────────────────── //
// ──────────────────────────────────────────────────────────────────────────── //
//  Unified top navigation bar (phone AND TV)                                //
//  Transparent over the hero, with a top→bottom black gradient so the        //
//  white text always reads. Tabs are D-pad focusable with the red            //
//  underline that Netflix uses for the active section. On TV the focused     //
//  tab gets a red focus ring + larger text/icons so it reads from the        //
//  couch. It never steals initial focus — each screen grabs focus on its     //
//  hero Play button / first card via rememberInitialFocusRequester, so the   //
//  user starts on content. The top bar is reached by pressing D-pad Up       //
//  from the topmost content row.                                            //
// ──────────────────────────────────────────────────────────────────────────── //
@Composable
private fun NetflixTopBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    onSearchClick: () -> Unit,
    isTv: Boolean = false
) {
    Surface(color = Color.Transparent, shadowElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = if (isTv) 0.9f else 0.85f),
                            Color.Black.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(
                    horizontal = if (isTv) 32.dp else 16.dp,
                    vertical = if (isTv) 14.dp else 10.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Inline nav tabs — Netflix shows these in the top bar.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Tab.entries.forEach { tab ->
                        TopNavTab(
                            tab = tab,
                            isSelected = tab == currentTab,
                            onClick = { onTabSelected(tab) },
                            isTv = isTv
                        )
                    }
                }
                IconButton(
                    onClick = onSearchClick,
                    modifier = if (isTv) Modifier.size(44.dp) else Modifier
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = if (isTv) Modifier.size(28.dp) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun TopNavTab(
    tab: Tab,
    isSelected: Boolean,
    onClick: () -> Unit,
    isTv: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val color = if (isSelected) Color.White else TextMuted

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            // On TV show a red focus ring so the user can tell which tab
            // is focused while navigating the top bar with the D-pad.
            .then(
                if (isTv && isFocused) {
                    Modifier.border(2.dp, Red, RoundedCornerShape(6.dp))
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (isTv) 6.dp else 0.dp,
                vertical = if (isTv) 8.dp else 6.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = tab.label,
            color = if (isFocused && !isSelected) TextPrimary else color,
            fontSize = if (isTv) 18.sp else 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        // Red underline under the active tab (Netflix active-section marker).
        Box(
            modifier = Modifier
                .width(if (isSelected) (if (isTv) 32.dp else 24.dp) else 0.dp)
                .height(3.dp)
                .background(Red, RoundedCornerShape(2.dp))
        )
    }
}

// ─────────────────────────────────────────────────────────────────────── //
//  Buy Me a Coffee one-time prompt dialog (phone only)                   //
//  Shown once on first launch; a "support_prefs" SharedPreferences flag  //
//  ("bmc_prompt_seen") prevents it from reappearing. Dismiss records the //
//  flag too, so it never nags again — the persistent link in Updates is  //
//  the always-available fallback.                                        //
// ─────────────────────────────────────────────────────────────────────── //
@Composable
private fun BuyMeCoffeeDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bg3,
        titleContentColor = TextPrimary,
        textContentColor = TextMuted,
        icon = {
            Icon(
                imageVector = Icons.Filled.LocalCafe,
                contentDescription = null,
                tint = Color(0xFFFFDD00), // Buy Me a Coffee amber
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(
                text = "Enjoying the app?",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "If you'd like to support its development, " +
                    "you can buy me a coffee. No pressure at all — " +
                    "and you won't see this again.",
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFDD00)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalCafe,
                    contentDescription = null,
                    tint = Color(0xFF1A1A1A),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Buy me a coffee",
                    color = Color(0xFF1A1A1A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Maybe later", color = TextMuted, fontSize = 14.sp)
            }
        }
    )
}
