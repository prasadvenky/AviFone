package com.example.avifone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.avifone.AudioEngine
import com.example.avifone.ListenController
import com.example.avifone.MonitorUiState
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(controller: ListenController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The notification permission is optional: without it the service still runs,
    // the ongoing notification just isn't shown in the shade.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) controller.start()
        else controller.onPermissionDenied()
    }

    val onToggle = {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when {
            state.running -> controller.stop()
            hasMic && hasNotifications(context) -> controller.start()
            else -> permissionLauncher.launch(requiredPermissions())
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AviFone", style = MaterialTheme.typography.headlineMedium)
            HeadphoneCard(state)

            Button(
                onClick = onToggle,
                enabled = state.running || state.headphones != null,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    if (state.running) "Stop listening" else "Start listening",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.running) {
                Text(
                    "Keeps listening with the screen off. Stop it here or from the notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            VolumeCard(state, controller::setVolume)
            NoiseReductionCard(state, controller::setNoiseReduction, controller::setNoiseReductionStrength)
            if (state.running) state.stats?.let { StatsCard(it) }
        }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

/** If this was denied, the launcher still calls back with the mic result, so Start isn't blocked. */
private fun hasNotifications(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
private fun HeadphoneCard(state: MonitorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val hp = state.headphones
            Text(
                if (hp == null) "No headphones connected" else "Output: ${hp.name}",
                style = MaterialTheme.typography.titleMedium,
            )
            when {
                hp == null -> Text(
                    "Plug in wired or USB headphones. Playing the mic through the " +
                        "speaker would cause feedback, so it's disabled.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                hp.isBluetooth -> Text(
                    "Bluetooth adds 100–300 ms of delay that no app can remove. " +
                        "Use wired or USB-C headphones for the lowest latency.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun VolumeCard(state: MonitorUiState, onVolume: (Float) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.volume <= 0f) "Muted"
                    else "%+.0f dB".format(ListenController.sliderToDb(state.volume)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Slider(value = state.volume, onValueChange = onVolume)
            if (state.running) {
                val level = state.stats?.inputLevelDb ?: -100f
                Text("Mic level", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { ((level + 60f) / 60f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NoiseReductionCard(
    state: MonitorUiState,
    onEnabled: (Boolean) -> Unit,
    onStrength: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce ambient sound", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cuts rumble and quiets steady background noise. Adds no delay.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.noiseReduction, onCheckedChange = onEnabled)
            }
            if (state.noiseReduction) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Strength", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(state.noiseReductionStrength * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(value = state.noiseReductionStrength, onValueChange = onStrength)
                val reduction = state.stats?.reductionDb
                if (state.running && reduction != null) {
                    Text(
                        "Currently reducing by %.0f dB".format(-reduction),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: AudioEngine.Stats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Audio path", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Latency: " + (stats.latencyMs?.let { "%.1f ms".format(it) } ?: "not reported"))
            Text(
                "${stats.sampleRate} Hz · burst ${stats.framesPerBurst} · " +
                    "buffer ${stats.outputBufferFrames} frames"
            )
            Text(
                "Fast path (MMAP): in ${if (stats.mmapInput) "yes" else "no"}, " +
                    "out ${if (stats.mmapOutput) "yes" else "no"}"
            )
            Text("Glitches: ${stats.outputXRuns} output, ${stats.inputUnderflows} input")
        }
    }
}
