package com.freeflix.app.ui.search

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freeflix.app.data.model.TmdbItem
import com.freeflix.app.ui.components.ContentCard
import com.freeflix.app.ui.theme.Bg
import com.freeflix.app.ui.theme.Bg3
import com.freeflix.app.ui.theme.Red
import com.freeflix.app.ui.theme.TextMuted
import com.freeflix.app.ui.theme.TextPrimary
import com.freeflix.app.ui.util.responsiveDims
import com.freeflix.app.ui.util.rememberInitialFocusRequester

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
    val dims = responsiveDims()
    val focusManager = LocalFocusManager.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            // focusGroup(): clamps D-pad focus inside the search screen so Up
            // from the search field / first result can't escape into empty
            // space (nothing focused, user stranded on a no-pointer remote).
            .focusGroup()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            val closeSrc = remember { MutableInteractionSource() }
            val closeFocused by closeSrc.collectIsFocusedAsState()
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .then(
                        if (closeFocused) Modifier.border(2.dp, Red, RoundedCornerShape(8.dp))
                        else Modifier
                    )
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }

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
                .padding(vertical = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Red,
                unfocusedBorderColor = TextMuted,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        if (isLoading) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Red)
            }
        } else if (results.isNotEmpty()) {
            // Netflix-style grid of cards.
            LazyVerticalGrid(
                columns = GridCells.Fixed(dims.gridColumns),
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(dims.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(dims.cardSpacing),
                modifier = Modifier.weight(1f)
            ) {
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

                // "Load More" footer - a full-width, D-pad-focusable button
                // shown whenever there are more pages available. While a
                // loadMore() is in flight it shows a spinner and is disabled.
                // This is the "no load more button so I can see more of what
                // I searched or what genre I chose" fix for Search on TV.
                if (canLoadMore) {
                    item(span = { GridItemSpan(dims.gridColumns) }) {
                        SearchLoadMoreButton(
                            isLoading = loadingMore,
                            isTv = dims.isTv,
                            onClick = { viewModel.loadMore() }
                        )
                    }
                }
            }
        } else if (query.length >= 2) {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No results found", color = TextMuted, fontSize = 16.sp)
            }
        } else {
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Start typing to search", color = TextMuted, fontSize = 16.sp)
            }
        }
    }
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
