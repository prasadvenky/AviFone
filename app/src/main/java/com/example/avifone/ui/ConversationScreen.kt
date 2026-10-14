package com.example.avifone.ui

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.transcribe.ConversationUiState
import com.example.avifone.transcribe.LocalEngine
import com.example.avifone.transcribe.LocalModelState
import com.example.avifone.transcribe.SpeakerInfo
import com.example.avifone.transcribe.SummaryMode
import com.example.avifone.transcribe.TranscriptLine
import com.example.avifone.transcribe.TranscriptionController
import java.text.DateFormat
import java.util.Date
import kotlin.math.log10

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
        // Transcription is fully on-device and needs no account; only the summary does.
        item { TranscribeButton(state, onToggle) }
        item { StatusRow(state) }
        state.message?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        item {
            SummaryCard(
                state = state,
                onSignIn = { activity?.let(controller::signIn) },
                onMode = controller::setSummaryMode,
                onDownload = controller::downloadLocalModel,
                onCancelDownload = controller::cancelDownload,
            )
        }
        if (state.speakers.isNotEmpty()) {
            item { SpeakerLegend(state.speakers.values.distinctBy { it.label }) { editing = it } }
        }
        item { TranscriptHeader(hasLines = state.lines.isNotEmpty(), onClear = controller::clearTranscript) }
        if (state.lines.isEmpty()) {
            item {
                Text(
                    "Each line appears about a second after someone finishes speaking. Speech is " +
                        "transcribed on this phone, and up to 4 speakers get their own color.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.lines, key = { it.id }) { line ->
            state.speakers[line.speaker]?.let { speaker ->
                TranscriptRow(line, speaker) { info -> editing = info }
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
    when {
        state.loadingModels -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Loading speech models…", style = MaterialTheme.typography.bodySmall)
        }
        state.transcribing -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (state.speechActive) "● Listening · someone is speaking…" else "● Listening",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            state.activeSpeechModel?.let { active ->
                Text(
                    if (active != state.speechModel) {
                        "${active.displayName} · download ${state.speechModel.displayName} in Settings"
                    } else {
                        active.displayName
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MicLevelMeter(state.micLevel)
            if (state.audioDropped) {
                Text(
                    "Some audio was dropped: this phone couldn't keep up with the speech model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.micSilent) {
                Text(
                    "No sound is reaching the microphone." + if (isEmulator()) {
                        " This emulator was started without audio; launch it with -allow-host-audio."
                    } else {
                        " Check that another app isn't using it and that it isn't muted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Peak input level on a -60 dBFS..0 dBFS scale, so a live microphone visibly moves. */
@Composable
private fun MicLevelMeter(peak: Float) {
    val db = if (peak > 0f) 20 * log10(peak) else -100f
    val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
    }
}

private fun isEmulator(): Boolean =
    Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.startsWith("generic")

@Composable
private fun SummaryCard(
    state: ConversationUiState,
    onSignIn: () -> Unit,
    onMode: (SummaryMode) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val summary = state.summary
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Text("Last 15 minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.summarizing) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                summary?.let {
                    Text(
                        "  updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.updatedAtMs))}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(if (expanded) "  ▲" else "  ▼", style = MaterialTheme.typography.labelMedium)
            }
            if (!expanded) return@Column

            SummaryModeSelector(state.summaryMode, onMode)
            val usable = when (state.summaryMode) {
                SummaryMode.CLOUD -> {
                    CloudSection(state, onSignIn)
                    state.firebaseConfigured && state.user != null
                }
                SummaryMode.LOCAL -> {
                    LocalSection(state, onDownload, onCancelDownload)
                    state.localModel == LocalModelState.Ready
                }
            }
            if (!usable) return@Column

            if (summary == null) {
                Text(
                    "A summary of the conversation appears here about a minute after transcription " +
                        "starts." + if (state.summaryMode == SummaryMode.LOCAL) {
                            " The first one takes longer while the model loads."
                        } else {
                            ""
                        },
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
            state.summaryLabel?.let {
                Text(
                    "Written by $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryModeSelector(mode: SummaryMode, onMode: (SummaryMode) -> Unit) {
    val modes = listOf(SummaryMode.LOCAL, SummaryMode.CLOUD)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(if (m == SummaryMode.LOCAL) "Local AI" else "Cloud AI")
            }
        }
    }
}

/** Gemini through Firebase: needs the project config and a signed-in Google account. */
@Composable
private fun CloudSection(state: ConversationUiState, onSignIn: () -> Unit) {
    if (!state.firebaseConfigured) {
        Text(
            "The cloud summary needs Firebase: add app/google-services.json and rebuild (see " +
                "README). Transcription and Local AI work without it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val user = state.user
    if (user == null) {
        Text(
            "Sign in with Google to get the summary from Gemini. Only the transcript text is " +
                "sent; audio stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onSignIn, enabled = !state.signingIn, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.signingIn) "Signing in…" else "Sign in with Google")
        }
        return
    }
    Text(
        "Signed in as ${user.email ?: user.name ?: "Google user"} · manage in Settings",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A model on this phone: Gemini Nano where Android provides it, else a one-time download. */
@Composable
private fun LocalSection(
    state: ConversationUiState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val nano = state.localEngine == LocalEngine.NANO
    val name = state.localModelName
    when (val model = state.localModel) {
        LocalModelState.Probing -> ProgressLine("Checking this device…")
        is LocalModelState.NotDownloaded -> {
            if (nano) {
                Text(
                    "This phone has Gemini Nano. Android downloads it once; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Get Gemini Nano") }
            } else {
                val total = model.totalBytes?.let(::mb) ?: "a one-time"
                Text(
                    "Summaries are written on this phone by $name ($total download, once). Keep " +
                        "AviFone open while it downloads; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (model.resumeBytes > 0 && model.totalBytes != null) {
                            "Resume (${mb(model.resumeBytes)} of ${mb(model.totalBytes)})"
                        } else {
                            "Download $total"
                        }
                    )
                }
            }
        }
        is LocalModelState.Downloading -> {
            val total = model.totalBytes
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (model.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total != null) "${mb(model.bytes)} / ${mb(total)}" else "${mb(model.bytes)} so far",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelDownload) { Text("Cancel") }
            }
        }
        LocalModelState.Verifying -> ProgressLine("Verifying the download…")
        LocalModelState.Installing -> ProgressLine("Unpacking…")  // archives: speech models only
        is LocalModelState.Failed -> {
            Text("Download failed: ${model.reason}", color = MaterialTheme.colorScheme.error)
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        }
        LocalModelState.Ready -> Text(
            if (nano) "Using Gemini Nano on this device" else "$name · on this device · manage in Settings",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProgressLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

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
private fun TranscriptRow(line: TranscriptLine, speaker: SpeakerInfo, onEdit: (SpeakerInfo) -> Unit) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(line.startMs))
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
                "Speech is transcribed on this phone; audio never leaves it. The summary of the " +
                    "last 15 minutes is written either on this phone (Local AI; a small model is " +
                    "downloaded once) or, if you choose Cloud AI and sign in, by sending the " +
                    "transcript text to Google Gemini.\n\n" +
                    "Make sure everyone being recorded agrees, and don't use the summary for " +
                    "confidential, customer or patient conversations. The transcript is kept only " +
                    "in memory, for the last 15 minutes."
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
