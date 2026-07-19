package com.mariocart.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentCard
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.util.responsiveDims
import com.mariocart.app.ui.util.rememberInitialFocusRequester

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
    val dims = responsiveDims()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // On a no-pointer TV box, land D-pad focus in the search field when the
    // screen opens so the user can start typing right away.
    val searchFieldFocusRequester = rememberInitialFocusRequester()
    // Focus target for the first result card — used after the user presses
    // Done/Enter on the keypad so they can immediately D-pad to a movie.
    val firstResultFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialGenre) {
        if (initialGenre != null) {
            viewModel.setInitialGenre(initialGenre)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                // Pressing Enter / Done on the remote keypad hides it so the
                // user can D-pad through the results grid and pick a movie.
                keyboard?.hide()
                focusManager.clearFocus()
                // If there are already results on screen, jump focus to the
                // first one so the user isn't left stranded with nothing
                // focused on a no-pointer TV box.
                if (results.isNotEmpty()) {
                    runCatching { firstResultFocusRequester.requestFocus() }
                }
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFieldFocusRequester)
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
                        focusRequester = if (item === results.first()) firstResultFocusRequester else null
                    )
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
