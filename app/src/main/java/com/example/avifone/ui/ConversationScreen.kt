package com.example.avifone.ui

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.transcribe.ConversationSummary
import com.example.avifone.transcribe.ConversationUiState
import com.example.avifone.transcribe.SpeakerInfo
import com.example.avifone.transcribe.TranscriptLine
import com.example.avifone.transcribe.TranscriptionController
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil

@Composable
fun ConversationScreen(controller: TranscriptionController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var askConsent by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SpeakerInfo?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted[Manifest.permission.RECORD_AUDIO] == true) controller.start() }

    val startWithPermissions = {
        if (hasMicrophone(context) && hasNotifications(context)) controller.start()
        else permissionLauncher.launch(requiredPermissions())
    }
    val onToggle = {
        when {
            state.transcribing -> controller.stop()
            !state.consentGiven -> askConsent = true
            else -> startWithPermissions()
        }
    }

    val listState = rememberLazyListState()
    // Follow new lines as they arrive.
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !state.firebaseConfigured -> item { NotConfiguredCard() }
            state.user == null -> item {
                SignInCard(state.signingIn) { activity?.let(controller::signIn) }
            }
            else -> {
                item { AccountRow(state, controller::signOut) }
                item { TranscribeButton(state, onToggle) }
                item { StatusRow(state) }
            }
        }
        state.message?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (state.user != null) {
            item { SummaryCard(state.summary, state.summarizing) }
            if (state.speakers.isNotEmpty()) {
                item { SpeakerLegend(state.speakers.values.distinctBy { it.label }) { editing = it } }
            }
            item { TranscriptHeader(hasLines = state.lines.isNotEmpty(), onClear = controller::clearTranscript) }
            if (state.lines.isEmpty()) {
                item {
                    Text(
                        "The transcript appears here about ${TranscriptionController.CHUNK_SECONDS} seconds " +
                            "after people start talking. Up to 4 speakers get their own color.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.lines, key = { it.id }) { line ->
                TranscriptRow(line, state.speakers[line.speaker]) { info -> editing = info }
            }
        }
    }

    if (askConsent) {
        ConsentDialog(
            onAgree = {
                askConsent = false
                controller.giveConsent()
                startWithPermissions()
            },
            onDismiss = { askConsent = false },
        )
    }
    editing?.let { speaker ->
        SpeakerDialog(
            speaker = speaker,
            others = state.speakers.values.distinctBy { it.label }.filter { it.label != speaker.label },
            onRename = { controller.renameSpeaker(speaker.label, it); editing = null },
            onMerge = { into -> controller.mergeSpeakers(speaker.label, into.label); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun NotConfiguredCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Firebase isn't set up yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Conversation transcription signs in with Google and uses Gemini through Firebase. " +
                    "Add app/google-services.json from your Firebase project and rebuild (see README).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SignInCard(signingIn: Boolean, onSignIn: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transcribe conversations", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sign in with Google to transcribe what's said around you with Gemini, see who said " +
                    "what, and get a running summary of the last 15 minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSignIn, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) {
                Text(if (signingIn) "Signing in…" else "Sign in with Google")
            }
        }
    }
}

@Composable
private fun AccountRow(state: ConversationUiState, onSignOut: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Signed in as ${state.user?.email ?: state.user?.name ?: "Google user"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun TranscribeButton(state: ConversationUiState, onToggle: () -> Unit) {
    Button(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = if (state.transcribing) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            if (state.transcribing) "Stop transcribing" else "Start transcribing",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StatusRow(state: ConversationUiState) {
    if (!state.transcribing && state.pendingChunks == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.transcribing) {
            val secondsLeft = ceil((1f - state.chunkProgress) * TranscriptionController.CHUNK_SECONDS).toInt()
            Text(
                "● Recording · next update in ${secondsLeft}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            LinearProgressIndicator(progress = { state.chunkProgress }, modifier = Modifier.fillMaxWidth())
        }
        if (state.pendingChunks > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Transcribing with Gemini…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: ConversationSummary?, summarizing: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Text("Last 15 minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (summarizing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                summary?.let {
                    Text(
                        "  updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.updatedAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(if (expanded) "  ▲" else "  ▼", style = MaterialTheme.typography.labelMedium)
            }
            if (!expanded) return@Column
            if (summary == null) {
                Text(
                    "A summary of the conversation appears here about a minute after transcription starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (summary.topic.isNotBlank()) {
                Text(summary.topic, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            summary.points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            if (summary.actionItems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Action items & questions", style = MaterialTheme.typography.labelLarge)
                summary.actionItems.forEach { Text("☐ $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerLegend(speakers: List<SpeakerInfo>, onEdit: (SpeakerInfo) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        speakers.forEach { speaker ->
            AssistChip(
                onClick = { onEdit(speaker) },
                label = { Text(speaker.name) },
                leadingIcon = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(speaker.colorIndex)))
                },
            )
        }
    }
}

@Composable
private fun TranscriptHeader(hasLines: Boolean, onClear: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text("Transcript", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (hasLines) TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun TranscriptRow(line: TranscriptLine, speaker: SpeakerInfo?, onEdit: (SpeakerInfo) -> Unit) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(line.startMs))
    if (line.isGap || speaker == null) {
        Text(
            "$time · couldn't transcribe this part",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val color = speakerColor(speaker.colorIndex)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    speaker.name,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onEdit(speaker) },
                )
                Text(
                    "  $time",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you start") },
        text = {
            Text(
                "Microphone audio is sent to Google Gemini to be transcribed and summarized. " +
                    "Make sure everyone being recorded agrees, and don't use it for confidential, " +
                    "customer or patient conversations.\n\n" +
                    "The transcript stays on this phone, only in memory, and only for the last 15 minutes."
            )
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("I understand") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SpeakerDialog(
    speaker: SpeakerInfo,
    others: List<SpeakerInfo>,
    onRename: (String) -> Unit,
    onMerge: (SpeakerInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(speaker.label) { mutableStateOf(speaker.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speaker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (others.isNotEmpty()) {
                    Text("Same person as…", style = MaterialTheme.typography.labelLarge)
                    others.forEach { other ->
                        OutlinedButton(onClick = { onMerge(other) }, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(speakerColor(other.colorIndex)))
                            Text("  ${other.name}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Okabe-Ito colorblind-safe hues, adjusted for contrast in light and dark themes. */
@Composable
private fun speakerColor(index: Int): Color {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DARK_SPEAKER_COLORS else LIGHT_SPEAKER_COLORS
    return palette[index.mod(palette.size)]
}

private val LIGHT_SPEAKER_COLORS = listOf(
    Color(0xFF0072B2), Color(0xFFB35F00), Color(0xFF007A5A), Color(0xFFB0558F),
)
private val DARK_SPEAKER_COLORS = listOf(
    Color(0xFF56B4E9), Color(0xFFE69F00), Color(0xFF2EC4A0), Color(0xFFE38FC6),
)
