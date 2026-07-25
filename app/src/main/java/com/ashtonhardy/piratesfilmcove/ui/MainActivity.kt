package com.ashtonhardy.piratesfilmcove.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.ui.browse.BrowseScreen
import com.ashtonhardy.piratesfilmcove.ui.detail.DetailScreen
import com.ashtonhardy.piratesfilmcove.ui.home.HomeScreen
import com.ashtonhardy.piratesfilmcove.ui.movies.MoviesScreen
import com.ashtonhardy.piratesfilmcove.ui.player.PlayerActivity
import com.ashtonhardy.piratesfilmcove.ui.search.SearchScreen
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg2
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.NetflixTheme
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.tv.SeasonEpisodePicker
import com.ashtonhardy.piratesfilmcove.ui.tv.TvScreen
import com.ashtonhardy.piratesfilmcove.ui.updates.UpdatesScreen
import com.ashtonhardy.piratesfilmcove.ui.util.LocalSideRailXReporter
import com.ashtonhardy.piratesfilmcove.ui.util.isTvDevice
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims

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

        com.ashtonhardy.piratesfilmcove.ui.browse.AppContextHolder.context = applicationContext

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

    // TV-only: the side rail is HIDDEN by default and only slides in when the
    // user presses Left while already on the first card of a row. Selecting a
    // nav item slides it back away so it never covers content while browsing.
    var sideNavVisible by remember { mutableStateOf(false) }
    val sideNavFocusRequester = remember { FocusRequester() }
    // True only during the one-time startup reveal of the rail. While true the
    // rail auto-retracts after a few seconds (if the user hasn't picked an
    // item or pressed Right to dismiss it) so it never lingers over content.
    var sideNavAutoShown by remember { mutableStateOf(false) }

    // The on-screen X (px) of the currently-focused card / hero button. Each
    // focused card reports its window-X here via LocalSideRailXReporter (see
    // ContentCard / HeroButton). Kept for reference but the top bar is now
    // opened by a UP press at the very top of the screen (see focusedCardY).
    var focusedCardX by remember { mutableStateOf(0f) }

    // The on-screen Y (px) of the currently-focused card / hero button. Each
    // focused card reports its window-Y here via LocalSideRailXReporter (see
    // ContentCard / HeroButton). The Up key handler below uses this to decide
    // whether an Up press is "at the very top of the screen" (open the top
    // bar) or "mid-screen" (let the default row-to-row up move happen). 0f
    // means "no card has reported yet" — treated as the top edge so the very
    // first Up press (e.g. on the hero) still opens the top bar as expected.
    var focusedCardY by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        com.ashtonhardy.piratesfilmcove.ui.AutoUpdater.checkAndPrompt(context)
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
            val wp = com.ashtonhardy.piratesfilmcove.data.repository.WatchProgressStore
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
    // TV: Back also dismisses the side rail (if visible) before anything else
    // peels off, so a TV remote user is never stuck with the rail on screen.
    BackHandler(enabled = sideNavVisible) {
        sideNavVisible = false
        sideNavAutoShown = false
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

    // ── Layout: TV uses a side navigation rail; phones use the Netflix top bar ──
    if (dims.isTv) {
        // The side rail is hidden by default. It slides in (overlapping the
        // left edge of the content) only when the user presses Left while on
        // the first card of a row — i.e. "at the beginning of a line". It
        // slides back out as soon as a nav item is chosen, so it never gets
        // in the way while scrolling through movies/shows.
        //
        // ONE-TIME STARTUP REVEAL: the first time the TV layout appears we
        // slide the rail in and land focus on it so the user immediately
        // discovers that a side menu exists (otherwise a no-pointer TV remote
        // user could browse for a long time without ever realising it's
        // there). After a few seconds — if they haven't picked an item or
        // pressed Right to dismiss — it auto-retracts so it doesn't linger
        // over the content. They can always bring it back with a Left press.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(400)
            sideNavVisible = true
            sideNavAutoShown = true
        }
        // Auto-retract the startup-revealed rail after a grace period. This
        // only fires for the auto-shown reveal (sideNavAutoShown == true);
        // a user-invoked Left-press reveal (sideNavAutoShown == false) stays
        // until they explicitly dismiss it.
        LaunchedEffect(sideNavVisible, sideNavAutoShown) {
            if (sideNavVisible && sideNavAutoShown) {
                kotlinx.coroutines.delay(4500)
                sideNavVisible = false
                sideNavAutoShown = false
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Bg)) {
            // When the side rail becomes visible, land D-pad focus on it so
            // the user can immediately navigate the nav items.
            LaunchedEffect(sideNavVisible) {
                if (sideNavVisible) {
                    kotlinx.coroutines.delay(120)
                    runCatching { sideNavFocusRequester.requestFocus() }
                }
            }

            // Content fills the full width; the top bar overlays it when
            // visible (it slides down from the very top of the screen).
            //
            // D-PAD UP / TOP BAR — "only open at the very top of the screen":
            // The top bar must open when the user presses Up ONLY if focus is
            // already at the very TOP of the screen — i.e. on the topmost row
            // (the first content row) or on the hero banner (which sits at the
            // top). An Up press mid-screen (a lower row) must instead move
            // focus to the row above (the Compose focus system's default
            // directional move), NOT open the top bar. A Left press must
            // NEVER open the top bar — it always performs the default
            // card-to-card move so the user can navigate left/right freely.
            //
            // How it works: every focused card / hero button reports its
            // on-screen Y (px) to `focusedCardY` via LocalSideRailXReporter
            // (see ContentCard / HeroButton). The outer onKeyEvent below
            // checks that Y against a top-edge threshold:
            //   • focusedCardY <= threshold  → at the top of the screen → open
            //     the top bar AND return true (consume the event so the focus
            //     system does NOT also perform a directional move).
            //   • focusedCardY >  threshold  → mid-screen (a lower row) → return
            //     false so the event is NOT consumed and the focus system
            //     performs the default row-to-row Up move.
            //
            // The topmost content row sits at y ≈ dims.rowPadding (the hero
            // banner / first LazyRow's top). Any second-or-lower row sits far
            // below the threshold (rowPadding + a full hero/row height). So
            // the threshold cleanly separates "at the very top" from
            // "mid-screen" — uniformly, with no per-row "isFirstRow" plumbing.
            //
            // The threshold is a small tolerance (16dp). The hero banner
            // reports Y = 0 (it is the topmost focusable element), so it is
            // always within the threshold. The first content row reports its
            // real Y (well below the hero, so > 16dp) and does NOT trigger.
            // This makes the top bar open ONLY when the user is on the very
            // top banner (the hero) and presses Up — never from a content row.
            val topThresholdPx = with(LocalDensity.current) {
                (16.dp).toPx()
            }
            CompositionLocalProvider(
                LocalSideRailXReporter provides { x, y ->
                    focusedCardX = x
                    focusedCardY = y
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onKeyEvent { event ->
                            // Only react to Up KeyUp when the top bar is
                            // hidden AND the focused card is at the very top
                            // of the screen. A manual reveal is NOT
                            // auto-shown, so it stays until dismissed. A Left
                            // press is NEVER intercepted here — it always
                            // falls through to the focus system so the user
                            // can navigate left/right freely at any row.
                            if (!sideNavVisible &&
                                event.type == KeyEventType.KeyUp &&
                                event.key == Key.DirectionUp &&
                                focusedCardY <= topThresholdPx
                            ) {
                                sideNavAutoShown = false
                                sideNavVisible = true
                                true
                            } else {
                                // Returning false lets the event continue. For
                                // a mid-screen Up this means the focus system
                                // performs the default row-to-row move; for a
                                // Left this means the default card-to-card move;
                                // for other keys it means the default action runs.
                                false
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup()
                    ) {
                        NetflixScreenSwitch(
                            currentTab = currentTab,
                            onItemClick = onItemClick,
                            onSearchWithGenre = onSearchWithGenre,
                            onResume = onResume
                        )
                    }
                }
            }

            // Sliding top bar — overlays the content's top edge, slides
            // down from the very top of the screen (an overlay on top of
            // the content, not a rail on the side).
            AnimatedVisibility(
                visible = sideNavVisible,
                enter = slideInVertically(animationSpec = tween(220)) { fullHeight -> -fullHeight },
                exit = slideOutVertically(animationSpec = tween(220)) { fullHeight -> -fullHeight },
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                TvSideNav(
                    currentTab = currentTab,
                    onTabSelected = {
                        currentTab = it
                        sideNavVisible = false
                        sideNavAutoShown = false
                    },
                    onSearchClick = {
                        showSearch = true
                        sideNavVisible = false
                        sideNavAutoShown = false
                    },
                    onDismiss = {
                        sideNavVisible = false
                        sideNavAutoShown = false
                    },
                    // Show the "press Left anytime" hint only during the
                    // startup auto-reveal, so first-time users learn the
                    // gesture but returning users aren't nagged.
                    showHint = sideNavAutoShown,
                    focusRequester = sideNavFocusRequester
                )
            }
        }
    } else {
        // Phone: transparent Netflix top bar that fades to solid on tab change.
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
//  TV top navigation bar (Android TV)                                 //
//  Horizontal icon+label bar that slides down from the very top.    //
// ──────────────────────────────────────────────────────────────────── //
@Composable
private fun TvSideNav(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    onSearchClick: () -> Unit,
    onDismiss: () -> Unit = {},
    showHint: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    Surface(
        color = Bg2,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            // Pressing Down while focused inside the top bar slides it
            // back up (D-pad dismiss without having to pick an item).
            // Back is handled by the BackHandler in AppRoot.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionDown) {
                    onDismiss()
                    true
                } else false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // One-time discoverability hint: a small chip at the start of
            // the top bar that tells first-time users they can reopen this
            // menu with an Up press at the very top. Only shown during the
            // startup auto-reveal; returning users never see it.
            if (showHint) {
                SideNavHintChip()
            }
            // The first item (Search) receives the shared focus requester so
            // the rail grabs D-pad focus as soon as it slides in.
            TvNavItem(
                icon = Icons.Default.Search,
                label = "Search",
                isSelected = false,
                onClick = onSearchClick,
                focusRequester = focusRequester
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

/**
 * Small "press Up at the top" hint shown at the start of the top bar during
 * the one-time startup reveal. Non-focusable (it's purely informational) so it
 * never steals D-pad focus from the first real nav item next to it.
 */
@Composable
private fun SideNavHintChip() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Red.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "↑ Menu",
            color = Red,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "press Up at the very top to reopen",
            color = TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun TvNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val color = if (isSelected) Red else TextPrimary
    val highlight = isFocused || isSelected

    Column(
        modifier = Modifier
            .width(100.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
