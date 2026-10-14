package com.example.avifone.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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

/** Account and storage settings: Google sign-out and the Local AI model file. */
@Composable
fun SettingsScreen(controller: TranscriptionController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    // Find out what Local AI would use here, even if the user hasn't chosen it yet.
    LaunchedEffect(Unit) { controller.checkLocalModel() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Google account") }
        item { AccountCard(state, onSignIn = { activity?.let(controller::signIn) }, onSignOut = controller::signOut) }

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
                            "Speech is transcribed on this phone with Moonshine, Silero VAD and NeMo " +
                                "SpeakerNet. Audio never leaves the device."
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
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
