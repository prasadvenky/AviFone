package com.example.avifone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.avifone.ui.AviFoneTheme
import com.example.avifone.ui.MonitorScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Listening is owned by ListenController + ListenService, so it keeps
        // running when this activity stops or is destroyed (screen off, Home).
        val controller = ListenController.get(this)
        setContent {
            AviFoneTheme {
                MonitorScreen(controller)
            }
        }
    }
}
