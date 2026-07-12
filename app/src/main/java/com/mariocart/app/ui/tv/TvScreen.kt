package com.mariocart.app.ui.tv

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TvScreen() {
    // Your TV-specific UI (Leanback or Compose TV)
    Surface(modifier = Modifier.fillMaxSize()) {
        Text("TV Mode - Coming Soon", modifier = Modifier.padding(16.dp))
    }
}
