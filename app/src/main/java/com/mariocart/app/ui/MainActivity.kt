package com.mariocart.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mariocart.app.data.server.ServerManager
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.mariocart.app.ui.theme.MarioCartTheme
import com.mariocart.app.ui.theme.Red
import com.mariocart.app.ui.theme.TextMuted
import com.mariocart.app.ui.theme.TextPrimary
import com.mariocart.app.ui.tv.TvScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch server health check in background
        lifecycleScope.launch {
            ServerManager.initialize(this@MainActivity)
        }
        setContent {
            MarioCartTheme {
                MainApp(
                    onPlayContent = { item ->
                        startActivity(
                            PlayerActivity.newIntent(
                                context = this,
                                tmdbId = item.id,
                                type = item.contentType,
                                title = item.displayTitle
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

        // Search overlay
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
                    colors = listOf(Color.Black.copy(alpha = 0.95f), Color.Transparent)
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo
        Text(
            text = "\uD83C\uDF44 MARIO CART",
            color = Red,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Text(
                text = "HIT A PIPE AND CHILL",
                color = TextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Search button
        IconButton(onClick = onSearchClick) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = TextPrimary
            )
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
        containerColor = Bg2,
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
