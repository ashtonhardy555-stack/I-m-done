package com.mariocart.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.ui.components.ContentCard
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.Bg3
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onItemClick: (TmdbItem) -> Unit,
    onClose: () -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchType by viewModel.searchType.collectAsState()
    val yearFilter by viewModel.yearFilter.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg.copy(alpha = 0.97f))
            .padding(top = 48.dp, start = 16.dp, end = 16.dp)
    ) {
        // Close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        // Search input
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateQuery(it) },
            placeholder = { Text("Search movies, shows\u2026", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Red,
                focusedBorderColor = Red,
                unfocusedBorderColor = Color(0xFF333333),
                focusedContainerColor = Bg3,
                unfocusedContainerColor = Bg3,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        // Filters row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            // Type filter
            FilterChip(
                label = when (searchType) {
                    "movie" -> "Movies Only"
                    "tv" -> "TV Only"
                    else -> "All"
                },
                options = listOf("All" to "multi", "Movies Only" to "movie", "TV Only" to "tv"),
                onSelect = { viewModel.updateType(it) }
            )

            // Year filter
            FilterChip(
                label = when (yearFilter) {
                    "2024" -> "2024+"
                    "2023" -> "2023+"
                    "2020" -> "2020+"
                    else -> "Any Year"
                },
                options = listOf(
                    "Any Year" to "",
                    "2024+" to "2024",
                    "2023+" to "2023",
                    "2020+" to "2020"
                ),
                onSelect = { viewModel.updateYear(it.ifEmpty { null }) }
            )
        }

        // Results
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Red, modifier = Modifier.size(36.dp))
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("\uD83D\uDD0D", fontSize = 48.sp)
                    Text("No results found", color = TextMuted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chunked = results.chunked(3)
                items(chunked.size) { rowIndex ->
                    val rowItems = chunked[rowIndex]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { item ->
                            ContentCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChip(
    label: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF333333),
                unfocusedBorderColor = Color(0xFF333333),
                focusedContainerColor = Bg3,
                unfocusedContainerColor = Bg3,
            ),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .size(width = 130.dp, height = 48.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Bg3)
        ) {
            options.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name, color = Color.White, fontSize = 13.sp) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
