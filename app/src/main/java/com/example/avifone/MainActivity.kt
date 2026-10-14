package com.example.avifone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.avifone.transcribe.TranscriptionController
import com.example.avifone.ui.AppScaffold
import com.example.avifone.ui.AviFoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Listening and transcription are owned by app-wide controllers + ListenService,
        // so they keep running when this activity stops or is destroyed (screen off, Home).
        val listen = ListenController.get(this)
        val conversation = TranscriptionController.get(this)
        setContent {
            AviFoneTheme {
                AppScaffold(listen, conversation)
            }
        }
    }
}
