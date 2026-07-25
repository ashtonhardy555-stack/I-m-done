package com.ashtonhardy.piratesfilmcove.ui.search

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.ui.components.ContentCard
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg
import com.ashtonhardy.piratesfilmcove.ui.theme.Bg3
import com.ashtonhardy.piratesfilmcove.ui.theme.Red
import com.ashtonhardy.piratesfilmcove.ui.theme.TextMuted
import com.ashtonhardy.piratesfilmcove.ui.theme.TextPrimary
import com.ashtonhardy.piratesfilmcove.ui.util.responsiveDims
import com.ashtonhardy.piratesfilmcove.ui.util.rememberInitialFocusRequester

/**
 * Genre chips shown in the side-to-side bar at the top of Search. Mirrors the
 * Home screen's "Browse by Category" chips so the user can switch genres
 * without leaving search. The empty id is "Trending" (no genre filter).
 */
private data class SearchGenreChip(val emoji: String, val label: String, val id: String)

private val SEARCH_GENRE_CHIPS = listOf(
    SearchGenreChip("\uD83D\uDD25", "Trending", ""),
    SearchGenreChip("\uD83C\uDFAC", "Action", "28"),
    SearchGenreChip("\uD83D\uDE02", "Comedy", "35"),
    SearchGenreChip("\uD83D\uDC7B", "Horror", "27"),
    SearchGenreChip("\uD83D\uDE80", "Sci-Fi", "878"),
    SearchGenreChip("\uD83C\uDFAD", "Drama", "18"),
    SearchGenreChip("\uD83D\uDD2A", "Thriller", "53"),
    SearchGenreChip("\uD83C\uDF00", "Animation", "16"),
    SearchGenreChip("\uD83D\uDC95", "Romance", "10749"),
    SearchGenreChip("\uD83D\uDD75", "Crime", "80"),
    SearchGenreChip("\uD83C\uDF0D", "Adventure", "12"),
    SearchGenreChip("\uD83D\uDCFA", "TV Action", "10759"),
    SearchGenreChip("\uD83D\uDCD6", "Documentary", "99"),
)

@Composable
fun SearchScreen(
    onItemClick: (TmdbItem) -> Unit,
    onClose: () -> Unit,
    initialGenre: String? = null,
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val canLoadMore by viewModel.canLoadMore.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val activeGenreId by viewModel.activeGenreId.collectAsState()
    val dims = responsiveDims()
    val keyboard = LocalSoftwareKeyboardController.current

    // On a no-pointer TV box, land D-pad focus in the search field when the
    // screen opens so the user can start typing right away.
    val searchFieldFocusRequester = rememberInitialFocusRequester()
    // Focus target for the first result card — used after the user presses
    // Done/Enter on the keypad so they can immediately D-pad to a movie.
    val firstResultFocusRequester = remember { FocusRequester() }
    // Flipped true when the user "commits" the search (presses Enter / Done /
    // D-pad-center). While true, a LaunchedEffect below watches for results
    // and moves focus to the first one — this handles the debounce timing so
    // the user is never left stranded with nothing focused if they press Enter
    // before the 700ms debounce has populated the grid.
    var searchCommitted by remember { mutableStateOf(false) }

    // Whether the user is in free-text-search mode (a non-blank committed
    // query) vs. genre-browse mode. The side-to-side genre bar is dimmed and
    // disabled during an active text search because the chips don't apply.
    val inTextSearch = query.isNotBlank()

    // The human-readable label for what the user is currently browsing.
    val categoryLabel = remember(query, activeGenreId) {
        when {
            query.isNotBlank() -> "Search results for \"$query\""
            activeGenreId != null -> {
                val chip = SEARCH_GENRE_CHIPS.find { it.id == activeGenreId }
                if (chip != null) chip.label else "Browse"
            }
            else -> "Search"
        }
    }

    // When the user commits the search, land focus on the first result card
    // the moment results are available (they may already be on screen from the
    // auto-search-while-typing, or they may arrive a beat later after the
    // debounce). This guarantees Enter always closes the keypad AND gives the
    // user a focused card to D-pad through.
    LaunchedEffect(searchCommitted, results) {
        if (searchCommitted && results.isNotEmpty()) {
            searchCommitted = false
            kotlinx.coroutines.delay(60)
            runCatching { firstResultFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(initialGenre) {
        if (initialGenre != null) {
            viewModel.setInitialGenre(initialGenre)
        }
    }

    // A single LazyVerticalGrid hosts the search field, the side-to-side genre
    // bar, the category label, the loading state, and the content cards.
    // Headers / footers span the full grid width via GridItemSpan(maxLineSpan)
    // so they read as normal rows, while the cards flow into dims.gridColumns
    // columns (3 on phone, 5 on TV). This is the same "side-to-side bar" layout
    // the Browse screen uses, so search results line up just like everything
    // else in the app.
    //
    // focusGroup(): clamps D-pad focus inside the search screen so Up from the
    // search field / first result can't escape into empty space (nothing
    // focused, user stranded on a no-pointer remote).
    LazyVerticalGrid(
        columns = GridCells.Fixed(dims.gridColumns),
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .focusGroup()
            .padding(top = dims.safeAreaTop),
        contentPadding = PaddingValues(
            start = dims.rowPadding,
            end = dims.rowPadding,
            bottom = 24.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(dims.cardSpacing)
    ) {
        // ── Search bar row (full width) ────────────────────────────────────
        item(span = { GridItemSpan(dims.gridColumns) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
            ) {
                Text(
                    "Search",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.weight(1f))
                val closeSrc = remember { MutableInteractionSource() }
                val closeFocused by closeSrc.collectIsFocusedAsState()
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.then(
                        if (closeFocused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }
        }

        // ── Search field (full width) ────────────────────────────────────
        item(span = { GridItemSpan(dims.gridColumns) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                placeholder = { Text("Search movies or TV shows...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // Pressing the search action key on a soft keyboard hides it
                    // so the user can D-pad through the results grid. The actual
                    // focus hand-off to the first result is handled by the
                    // searchCommitted flag + LaunchedEffect above (robust against
                    // the debounce timing).
                    keyboard?.hide()
                    searchCommitted = true
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFieldFocusRequester)
                    // onKeyEvent catches Enter / D-pad-center / NumPadEnter even
                    // when the TV Leanback IME doesn't fire the IME Done action
                    // (a common failure on Android TV boxes). KeyboardActions
                    // alone is unreliable here, so this is the guaranteed path:
                    // it hides the on-screen keypad and hands focus to the
                    // results grid.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            (event.key == Key.Enter ||
                                event.key == Key.NumPadEnter ||
                                event.key == Key.DirectionCenter)) {
                            keyboard?.hide()
                            // NOTE: we intentionally do NOT call focusManager.
                            // clearFocus() here — doing so can cause the TV
                            // Leanback IME to fire a final onValueChange with
                            // an empty string, which would clear the search
                            // results right after the user committed the
                            // search. Hiding the keyboard is sufficient to
                            // close the on-screen keypad and let the user
                            // D-pad through the results grid.
                            searchCommitted = true
                            true
                        } else false
                    }
                    .padding(vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Red,
                    unfocusedBorderColor = TextMuted,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        // ── Side-to-side genre bar (full width) ──────────────────────────
        // A horizontal LazyRow of genre pills the user can D-pad / tap through
        // to switch categories side-to-side, just like the Browse screen.
        // During an active free-text search the pills are dimmed (the genre
        // filter doesn't apply to a text query) but still tappable to jump
        // back into a genre browse.
        item(span = { GridItemSpan(dims.gridColumns) }) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                items(SEARCH_GENRE_CHIPS) { chip ->
                    SearchGenrePill(
                        chip = chip,
                        isSelected = !inTextSearch && activeGenreId == chip.id,
                        isDimmed = inTextSearch,
                        onClick = { viewModel.selectGenre(chip.id) }
                    )
                }
            }
        }

        // ── Active category / genre label (full width) ───────────────────
        // Shows the user what they're currently browsing: the genre name when
        // in genre-browse mode, or "Search results for '<query>'" when doing
        // a free-text search. This is the "categories and genre should
        // properly show me the category or genre" fix.
        item(span = { GridItemSpan(dims.gridColumns) }) {
            Text(
                text = categoryLabel,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 8.dp)
            )
        }

        // ── Loading state (full width) ──────────────────────────────────
        // Initial load only: full-width spinner when there are no items yet.
        // Once items are on screen we keep them visible and reflect an
        // in-flight loadMore() on the "Load More" button, so pressing it
        // never wipes the grid.
        if (isLoading && results.isEmpty()) {
            item(span = { GridItemSpan(dims.gridColumns) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Red, modifier = Modifier.size(36.dp))
                }
            }
        }

        // ── Result cards ────────────────────────────────────────────────
        // Always rendered when there are results, regardless of isLoading,
        // so a loadMore() in progress doesn't blank out what's shown.
        if (results.isNotEmpty()) {
            items(
                items = results,
                key = { item -> "${item.id}_${item.contentType}" }
            ) { item ->
                ContentCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    dims = dims,
                    fillMaxWidth = true,
                    focusRequester = if (item === results.first()) firstResultFocusRequester else null
                )
            }

        } else if (!isLoading && query.length >= 2) {
            // ── Empty state for a committed text search ─────────────────
            item(span = { GridItemSpan(dims.gridColumns) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found", color = TextMuted, fontSize = 16.sp)
                }
            }
        } else if (!isLoading && activeGenreId != null) {
            // ── Empty state for a genre browse with no streamable titles ─
            item(span = { GridItemSpan(dims.gridColumns) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No titles available in this category", color = TextMuted, fontSize = 16.sp)
                }
            }
        } else if (!isLoading) {
            // ── Initial empty state ──────────────────────────────────────
            item(span = { GridItemSpan(dims.gridColumns) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Start typing to search or pick a category above", color = TextMuted, fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * A genre pill for the side-to-side bar. Mirrors the Browse screen's GenrePill
 * styling (red background when selected, rounded, focusable) but adds an emoji
 * prefix to match the Home "Browse by Category" chips. When [isDimmed] is true
 * (an active free-text search) the pill is greyed out but still tappable so
 * the user can jump back into a genre browse at any time.
 */
@Composable
private fun SearchGenrePill(
    chip: SearchGenreChip,
    isSelected: Boolean,
    isDimmed: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Text(
        text = "${chip.emoji} ${chip.label}",
        color = when {
            isSelected -> Color.White
            isDimmed -> TextMuted
            isFocused -> Color.White
            else -> Color(0xFFE5E5E5)
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .then(
                if (isFocused) Modifier.border(2.dp, Red, RoundedCornerShape(20.dp))
                else Modifier
            )
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) Red else Bg3)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Full-width "Load More" affordance for the search results grid. Mirrors the
 * styling of the Browse screen's ShowMoreButton (red focus border, centered
 * label + chevron) so the two screens feel consistent. While [isLoading] is
 * true (a loadMore() is in flight) it shows a spinner and won't fire another
 * request. Fully D-pad-focusable so it's reachable by scrolling the grid to
 * the bottom on an Android TV box.
 */
@Composable
private fun SearchLoadMoreButton(
    isLoading: Boolean,
    isTv: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Bg3)
                .then(
                    if (isFocused) {
                        Modifier.border(3.dp, Red, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    enabled = !isLoading,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(horizontal = 32.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Red,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Load More",
                        color = if (isFocused) Red else TextPrimary,
                        fontSize = if (isTv) 16.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Load More",
                        tint = if (isFocused) Red else TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
