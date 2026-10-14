package com.example.avifone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.ListenController
import com.example.avifone.transcribe.TranscriptionController

@Composable
fun AppScaffold(listen: ListenController, conversation: TranscriptionController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listening by listen.state.collectAsStateWithLifecycle()
    val transcribing by conversation.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Text(
                "AviFone",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                // A dot marks a tab whose feature is running while you're on the other one.
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(if (listening.running) "Listen ●" else "Listen") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (transcribing.transcribing) "Conversation ●" else "Conversation") },
                )
            }
            when (tab) {
                0 -> MonitorScreen(listen)
                else -> ConversationScreen(conversation)
            }
        }
    }
}
