package com.example.avifone.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.R
import com.example.avifone.transcribe.ConversationUiState
import com.example.avifone.transcribe.LocalEngine
import com.example.avifone.transcribe.LocalModelState
import com.example.avifone.transcribe.TranscriptionController
import com.example.avifone.transcribe.llm.ModelCatalog
import com.example.avifone.transcribe.speech.SpeechLanguage
import com.example.avifone.transcribe.speech.SpeechModel
import com.example.avifone.transcribe.speech.SpeechModelCatalog

/** Account and storage settings: Google sign-out, the speech model, and the Local AI model file. */
@Composable
fun SettingsScreen(controller: TranscriptionController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteSpeech by remember { mutableStateOf<SpeechModel?>(null) }

    // Find out what Local AI would use here, even if the user hasn't chosen it yet.
    LaunchedEffect(Unit) { controller.checkLocalModel() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Google account") }
        item { AccountCard(state, onSignIn = { activity?.let(controller::signIn) }, onSignOut = controller::signOut) }

        item { SectionTitle("Speech model") }
        items(SpeechModelCatalog.ALL, key = { it.id }) { model ->
            SpeechModelCard(
                model = model,
                state = state,
                totalRamMb = controller.totalRamMb,
                onUse = { controller.setSpeechModel(model) },
                onDownload = { controller.downloadSpeechModel(model.id) },
                onCancel = { controller.cancelSpeechDownload(model.id) },
                onDelete = { confirmDeleteSpeech = model },
            )
        }
        item { LanguageCard(state.speechLanguage, onLanguage = controller::setSpeechLanguage) }

        item { SectionTitle("Local AI model") }
        item { ModelCard(state, onDownload = controller::downloadLocalModel, onDelete = { confirmDelete = true }) }

        item { SectionTitle("About") }
        item {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("AviFone ${version ?: ""}".trim()) },
                    supportingContent = {
                        Text(
                            "Speech is transcribed on this phone (Silero VAD, the speech model chosen " +
                                "above, NeMo SpeakerNet for speaker labels). Audio never leaves the device."
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }

    confirmDeleteSpeech?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSpeech = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete ${model.displayName}?") },
            text = {
                Text(
                    "About ${Math.round(model.installedBytes / 1_000_000.0)} MB will be freed. " +
                        if (state.speechModel == model) {
                            "Transcription switches to ${SpeechModelCatalog.MOONSHINE_TINY.displayName} until it is downloaded again."
                        } else {
                            "It can be downloaded again at any time."
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDeleteSpeech = null; controller.deleteSpeechModel(model.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteSpeech = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete the model?") },
            text = {
                Text(
                    "${state.localModelName} (${ModelCatalog.DEFAULT.sizeMb} MB) will be removed from this " +
                        "phone. Local AI summaries will need it downloaded again."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; controller.deleteLocalModel() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun AccountCard(state: ConversationUiState, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val user = state.user
    SettingsCard(
        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
        headline = when {
            !state.firebaseConfigured -> "Firebase isn't set up in this build"
            user == null -> "Not signed in"
            else -> user.email ?: user.name ?: "Google user"
        },
        supporting = if (state.firebaseConfigured) {
            "Used only for Cloud AI summaries. Transcription and Local AI need no account."
        } else {
            "Add app/google-services.json and rebuild to enable Cloud AI summaries."
        },
    ) {
        when {
            !state.firebaseConfigured -> Unit
            user == null -> TextButton(onClick = onSignIn, enabled = !state.signingIn) {
                Text(if (state.signingIn) "Signing in…" else "Sign in with Google")
            }
            else -> TextButton(onClick = onSignOut) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Text("  Sign out")
            }
        }
    }
}

@Composable
private fun ModelCard(state: ConversationUiState, onDownload: () -> Unit, onDelete: () -> Unit) {
    val nano = state.localEngine == LocalEngine.NANO
    val model = state.localModel
    val unknown = state.localEngine == LocalEngine.NONE || model == LocalModelState.Probing
    val sizeMb = ModelCatalog.DEFAULT.sizeMb
    SettingsCard(
        icon = { Icon(painterResource(R.drawable.ic_forum), contentDescription = null) },
        headline = state.localModelName.ifEmpty { "On-device model" },
        supporting = when {
            unknown -> "Checking this device…"
            nano -> "Gemini Nano, provided and stored by Android."
            model == LocalModelState.Ready -> "Downloaded · $sizeMb MB in app storage. Writes the Local AI summaries."
            model is LocalModelState.Downloading -> "Downloading…"
            model == LocalModelState.Verifying -> "Verifying the download…"
            model is LocalModelState.Failed -> "Download failed: ${model.reason}"
            else -> "Not downloaded ($sizeMb MB). Needed for Local AI summaries."
        },
    ) {
        when {
            nano || unknown -> Unit
            model == LocalModelState.Ready -> TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("  Delete model")
            }
            model is LocalModelState.NotDownloaded || model is LocalModelState.Failed ->
                TextButton(onClick = onDownload) { Text("Download $sizeMb MB") }
            else -> Unit
        }
    }
}

/** One speech model: pick it, download or delete it, see its state. */
@Composable
private fun SpeechModelCard(
    model: SpeechModel,
    state: ConversationUiState,
    totalRamMb: Int,
    onUse: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val modelState = state.speechModels[model.id] ?: LocalModelState.Probing
    val selected = state.speechModel == model
    val ready = model.builtIn || modelState == LocalModelState.Ready
    val active = state.activeSpeechModel == model
    val status = when {
        model.builtIn -> "Included in the app."
        modelState == LocalModelState.Ready -> "Downloaded · ${Math.round(model.installedBytes / 1_000_000.0)} MB in app storage."
        modelState is LocalModelState.Downloading -> {
            val total = modelState.totalBytes ?: model.downloadBytes
            "Downloading… ${(modelState.bytes * 100 / total.coerceAtLeast(1))} % of ${model.downloadMb} MB"
        }
        modelState == LocalModelState.Verifying -> "Verifying the download…"
        modelState == LocalModelState.Installing -> "Unpacking…"
        modelState is LocalModelState.Failed -> "Download failed: ${modelState.reason}"
        modelState is LocalModelState.NotDownloaded && modelState.resumeBytes > 0 ->
            "Partly downloaded (${Math.round(modelState.resumeBytes / 1_000_000.0)} of ${model.downloadMb} MB)."
        else -> "Not downloaded (${model.downloadMb} MB)."
    }
    val extra = when {
        selected && !ready -> " Selected; ${SpeechModelCatalog.MOONSHINE_TINY.displayName} is used until it is downloaded."
        active && state.transcribing -> " Transcribing now."
        else -> ""
    }
    // A model that needs most of the phone's RAM will be slow or fail to load.
    val tight = !model.builtIn && model.minRamMb > totalRamMb * 0.5
    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(model.displayName) },
                supportingContent = {
                    Column {
                        Text(model.note)
                        Text(status + extra, style = MaterialTheme.typography.bodySmall)
                        if (tight) {
                            Text(
                                "Needs about ${"%.1f".format(model.minRamMb / 1000.0)} GB of memory; this phone has " +
                                    "${"%.1f".format(totalRamMb / 1024.0)} GB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (modelState is LocalModelState.Downloading) {
                            val total = modelState.totalBytes ?: model.downloadBytes
                            LinearProgressIndicator(
                                progress = { (modelState.bytes.toDouble() / total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                },
                leadingContent = { RadioButton(selected = selected, onClick = onUse) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                when {
                    model.builtIn -> Unit
                    modelState == LocalModelState.Ready -> TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("  Delete")
                    }
                    modelState is LocalModelState.Downloading || modelState == LocalModelState.Verifying ||
                        modelState == LocalModelState.Installing -> TextButton(onClick = onCancel) { Text("Cancel") }
                    modelState is LocalModelState.NotDownloaded && modelState.resumeBytes > 0 ->
                        TextButton(onClick = onDownload) { Text("Resume download") }
                    else -> TextButton(onClick = onDownload) { Text("Download ${model.downloadMb} MB") }
                }
            }
        }
    }
}

/** The language the downloadable models are told to expect (the built-in model is English only). */
@Composable
private fun LanguageCard(language: SpeechLanguage, onLanguage: (SpeechLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsCard(
        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
        headline = "Language",
        supporting = "English suits Indian English. Detect lets Nemotron and Whisper decide per " +
            "utterance, which also handles Hindi mixed with English. Moonshine tiny is English only.",
    ) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(language.displayName)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SpeechLanguage.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = { open = false; onLanguage(option) },
                    )
                }
            }
        }
    }
}

/** A Material list item with its action on its own row, so long text isn't squeezed. */
@Composable
private fun SettingsCard(
    icon: @Composable () -> Unit,
    headline: String,
    supporting: String,
    action: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(headline) },
                supportingContent = { Text(supporting) },
                leadingContent = icon,
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
            Row(
                Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) { action() }
        }
    }
}
