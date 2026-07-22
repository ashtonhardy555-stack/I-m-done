package com.mariocart.app.ui

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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

    // TV-only: the side rail is HIDDEN by default and only slides in when the
    // user presses Left while already on the first card of a row. Selecting a
    // nav item slides it back away so it never covers content while browsing.
    var sideNavVisible by remember { mutableStateOf(false) }
    val sideNavFocusRequester = remember { FocusRequester() }
    // True only during the one-time startup reveal of the rail. While true the
    // rail auto-retracts after a few seconds (if the user hasn't picked an
    // item or pressed Right to dismiss it) so it never lingers over content.
    var sideNavAutoShown by remember { mutableStateOf(false) }

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

            // Content fills the full width; the rail overlays it when visible.
            //
            // D-PAD LEFT / SIDEBAR FIX:
            // The onKeyEvent that opens the side rail is on the OUTER Box,
            // and the focusGroup() is on the INNER Box. This ordering is
            // critical: the inner focusGroup() gets FIRST crack at every
            // key event. When the user presses Left and there IS a card to
            // the left of the current focus, the focusGroup consumes the event
            // (moves focus left) and it never reaches the outer onKeyEvent.
            // Only when the focus is already at the FAR-LEFT edge of a row
            // (nothing further left to move to) does the focusGroup let the
            // event bubble up to the outer onKeyEvent, which then reveals the
            // side rail. This is the correct "only open the sidebar at the
            // beginning of a line" behaviour.
            //
            // The previous bug was that onKeyEvent and focusGroup() were on
            // the SAME Box, so onKeyEvent intercepted every Left press BEFORE
            // the focusGroup could consume it for card-to-card navigation —
            // making the sidebar open on every single Left press.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        // Reveal the side rail when the user presses Left and
                        // it is currently hidden — i.e. "at the beginning of a
                        // line". This only fires when the inner focusGroup()
                        // did NOT consume the Left event (focus was already at
                        // the far-left edge). A manual reveal is NOT
                        // auto-shown, so it stays until dismissed.
                        if (!sideNavVisible &&
                            event.type == KeyEventType.KeyUp &&
                            event.key == Key.DirectionLeft
                        ) {
                            sideNavAutoShown = false
                            sideNavVisible = true
                            true
                        } else {
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

            // Sliding side rail — overlays the content's left edge.
            AnimatedVisibility(
                visible = sideNavVisible,
                enter = slideInHorizontally(animationSpec = tween(220)) { fullWidth -> -fullWidth },
                exit = slideOutHorizontally(animationSpec = tween(220)) { fullWidth -> -fullWidth },
                modifier = Modifier.align(Alignment.CenterStart)
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
//  TV side navigation rail (Android TV)                               //
//  Vertical icon+label rail with the red focus highlight.             //
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
            .fillMaxHeight()
            .width(120.dp)
            // Pressing Right while focused inside the rail slides it back
            // away (D-pad dismiss without having to pick an item). Back is
            // handled by the BackHandler in AppRoot.
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionRight) {
                    onDismiss()
                    true
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // One-time discoverability hint: a small chip at the top of the
            // rail that tells first-time users they can reopen this menu with
            // a Left press at any time. Only shown during the startup
            // auto-reveal; returning users never see it.
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
 * Small "press ← anytime" hint shown at the top of the side rail during the
 * one-time startup reveal. Non-focusable (it's purely informational) so it
 * never steals D-pad focus from the first real nav item below it.
 */
@Composable
private fun SideNavHintChip() {
    Column(
        modifier = Modifier
            .width(100.dp)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Red.copy(alpha = 0.18f))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "◀ Menu",
            color = Red,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "press Left\nto reopen",
            color = TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 11.sp
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
