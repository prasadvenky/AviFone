package com.example.avifone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

data class Headphones(val name: String, val isBluetooth: Boolean)

data class MonitorUiState(
    val running: Boolean = false,
    val headphones: Headphones? = null,
    /** Slider position 0..1. */
    val volume: Float = 0.5f,
    val noiseReduction: Boolean = false,
    val noiseReductionStrength: Float = 0.5f,
    val stats: AudioEngine.Stats? = null,
    val message: String? = null,
)

/**
 * App-wide owner of the listening session. It outlives the activity so audio keeps
 * flowing with the screen off; [ListenService] keeps the process in the foreground
 * while [MonitorUiState.running] is true.
 */
class ListenController private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MonitorUiState(headphones = findHeadphones()))
    val state: StateFlow<MonitorUiState> = _state
    private var pollJob: Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onRouteChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onRouteChanged()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        pushVolume()
        pushNoiseReduction()
    }

    /** Must be called while the app is visible: Android only lets a microphone
     *  foreground service start from the foreground. */
    fun start() {
        val headphones = findHeadphones()
        if (headphones == null) {
            _state.update { it.copy(headphones = null, message = NO_HEADPHONES) }
            return
        }
        val result = AudioEngine.start()
        if (result != 0) {
            _state.update { it.copy(running = false, message = "Could not open audio (error $result)") }
            return
        }
        _state.update { it.copy(running = true, headphones = headphones, message = null) }
        ListenService.start(context, headphones.name)
        startPolling()
    }

    fun stop(message: String? = null) {
        pollJob?.cancel()
        pollJob = null
        AudioEngine.stop()
        ListenService.stop(context)
        _state.update { it.copy(running = false, stats = null, message = message) }
    }

    fun setVolume(position: Float) {
        _state.update { it.copy(volume = position) }
        pushVolume()
    }

    fun setNoiseReduction(enabled: Boolean) {
        _state.update { it.copy(noiseReduction = enabled) }
        pushNoiseReduction()
    }

    fun setNoiseReductionStrength(strength: Float) {
        _state.update { it.copy(noiseReductionStrength = strength) }
        pushNoiseReduction()
    }

    fun onPermissionDenied() {
        _state.update { it.copy(message = "Microphone permission is required") }
    }

    private fun pushVolume() = AudioEngine.setVolume(sliderToGain(_state.value.volume))

    private fun pushNoiseReduction() = _state.value.let {
        AudioEngine.setNoiseReduction(it.noiseReduction, it.noiseReductionStrength)
    }

    private fun onRouteChanged(streamLost: Boolean = false) {
        val headphones = findHeadphones()
        val previous = _state.value
        _state.update { it.copy(headphones = headphones) }
        if (!previous.running) return
        if (headphones == null) {
            // Never let the mic feed the loudspeaker: that causes a feedback howl.
            stop(NO_HEADPHONES)
        } else if (streamLost || headphones != previous.headphones) {
            // New output device: reopen so the streams pick up its native config.
            // The service is already in the foreground, so this works with the screen off.
            AudioEngine.stop()
            start()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (AudioEngine.consumeDisconnected()) {
                    onRouteChanged(streamLost = true)
                    if (!_state.value.running) return@launch
                }
                _state.update { it.copy(stats = AudioEngine.stats()) }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun findHeadphones(): Headphones? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = outputs.firstOrNull { it.type in WIRED_TYPES }
            ?: outputs.firstOrNull { it.type in bluetoothTypes() }
            ?: return null
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headphones"
        return Headphones(name, isBluetooth = device.type in bluetoothTypes())
    }

    companion object {
        private const val STATS_INTERVAL_MS = 250L
        private const val NO_HEADPHONES = "Connect headphones to start"

        // Holds only the application context, so this can't leak an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ListenController? = null

        fun get(context: Context): ListenController = instance ?: synchronized(this) {
            instance ?: ListenController(context.applicationContext).also { instance = it }
        }

        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        private fun bluetoothTypes(): Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        /** Slider 0 = mute; otherwise -40 dB .. +12 dB on a perceptual (dB) scale. */
        fun sliderToGain(position: Float): Float =
            if (position <= 0f) 0f else 10f.pow(sliderToDb(position) / 20f)

        fun sliderToDb(position: Float): Float = -40f + 52f * position
    }
}
