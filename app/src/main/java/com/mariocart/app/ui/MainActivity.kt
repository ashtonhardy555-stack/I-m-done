package com.mariocart.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.ui.theme.MarioCartTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // Initialize server manager on app start
            ServerManager.initialize(this)
            Log.d("MainActivity", "✅ App initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Initialization error: ${e.message}", e)
        }
        
        setContent {
            MarioCartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(text = "Mario Cart - Streaming App")
                }
            }
        }
    }
}
