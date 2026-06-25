package com.mariocart.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.ui.browse.BrowseScreen
import com.mariocart.app.ui.home.HomeScreen
import com.mariocart.app.ui.movies.MoviesScreen
import com.mariocart.app.ui.player.PlayerActivity
import com.mariocart.app.ui.search.SearchScreen
import com.mariocart.app.ui.theme.Bg
import com.mariocart.app.ui.theme.MarioCartTheme
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.tv.TvScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            ServerManager.initialize(this@MainActivity)
        }

        setContent {
            MarioCartTheme {
                MainApp(
                    onPlayContent = { item ->
    startActivity(
        PlayerActivity.newIntent(
            context = this@MainActivity,
            tmdbId = item.id,
            contentType = item.contentType,
            title = item.displayTitle
                                     )
                                  )
                               }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun MainApp(onPlayContent: (TmdbItem) -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showSearch by remember { mutableStateOf(false) }

    val tabs = listOf("Home", "Movies", "TV Shows", "Browse")

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopNavBar(
                    selectedTab = selectedTab,
                    tabs = tabs,
                    onTabSelected = { selectedTab = it },
                    onSearchClick = { showSearch = true }
                )
            },
            bottomBar = {
                BottomNav(
                    selectedTab = selectedTab,
                    tabs = tabs,
                    onTabSelected = { selectedTab = it }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> HomeScreen(onItemClick = onPlayContent)
                    1 -> MoviesScreen(onItemClick = onPlayContent)
                    2 -> TvScreen(onItemClick = onPlayContent)
                    3 -> BrowseScreen(onItemClick = onPlayContent)
                }
            }
        }

        AnimatedVisibility(
            visible = showSearch,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SearchScreen(
                onItemClick = onPlayContent,
                onClose = { showSearch = false }
            )
        }
    }
}

@Composable
fun TopNavBar(
    selectedTab: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
    onSearchClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.98f), Color.Black.copy(alpha = 0.85f))
                )
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\uD83C\uDF44",
            fontSize = 18.sp,
            modifier = Modifier.padding(end = 4.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        tabs.forEachIndexed { index, label ->
            val isSelected = selectedTab == index
            Text(
                text = label,
                color = if (isSelected) Color.White else TextMuted,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Red.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextPrimary)
        }
    }
}

@Composable
fun BottomNav(
    selectedTab: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = Bg,
        contentColor = TextPrimary,
        modifier = Modifier.height(64.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                icon = {},
                label = {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = Color.White,
                    unselectedTextColor = TextMuted,
                    indicatorColor = Red.copy(alpha = 0.2f)
                )
            )
        }
    }
}
