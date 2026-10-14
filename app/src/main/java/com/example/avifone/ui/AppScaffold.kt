package com.example.avifone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.ListenController
import com.example.avifone.R
import com.example.avifone.transcribe.TranscriptionController

private enum class Destination(val label: String) {
    LISTEN("Listen"),
    CONVERSATION("Transcribe"),
    SETTINGS("Settings"),
}

/** Material 3 bottom navigation bar; the selected destination fills the rest of the screen. */
@Composable
fun AppScaffold(listen: ListenController, conversation: TranscriptionController) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val listening by listen.state.collectAsStateWithLifecycle()
    val transcribing by conversation.state.collectAsStateWithLifecycle()
    val destination = Destination.entries[selected]

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                BarItem(
                    destination = Destination.LISTEN,
                    selected = destination == Destination.LISTEN,
                    icon = painterResource(R.drawable.ic_headset),
                    // A dot marks a feature that is running while you're elsewhere.
                    active = listening.running,
                    onClick = { selected = Destination.LISTEN.ordinal },
                )
                BarItem(
                    destination = Destination.CONVERSATION,
                    selected = destination == Destination.CONVERSATION,
                    icon = painterResource(R.drawable.ic_forum),
                    active = transcribing.transcribing,
                    onClick = { selected = Destination.CONVERSATION.ordinal },
                )
                BarItem(
                    destination = Destination.SETTINGS,
                    selected = destination == Destination.SETTINGS,
                    icon = rememberVectorPainter(Icons.Filled.Settings),
                    active = false,
                    onClick = { selected = Destination.SETTINGS.ordinal },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Text(
                destination.label,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            )
            when (destination) {
                Destination.LISTEN -> MonitorScreen(listen)
                Destination.CONVERSATION -> ConversationScreen(conversation)
                Destination.SETTINGS -> SettingsScreen(conversation)
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(
    destination: Destination,
    selected: Boolean,
    icon: Painter,
    active: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            BadgedBox(badge = { if (active) Badge() }) {
                Icon(icon, contentDescription = null)
            }
        },
        label = { Text(destination.label) },
    )
}
