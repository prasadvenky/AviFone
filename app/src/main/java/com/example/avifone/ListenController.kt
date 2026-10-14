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
    /** Headphone pass-through is on. */
    val running: Boolean = false,
    /** Raw microphone capture for transcription is on. */
    val capturing: Boolean = false,
    val headphones: Headphones? = null,
    /** Slider position 0..1. */
    val volume: Float = 0.5f,
    val noiseReduction: Boolean = false,
    val noiseReductionStrength: Float = 0.5f,
    val stats: AudioEngine.Stats? = null,
    val message: String? = null,
)

/**
 * App-wide owner of the microphone session. It outlives the activity so audio keeps
 * flowing with the screen off; [ListenService] keeps the process in the foreground
 * while listening or capturing.
 *
 * Two independent features share the native engine's one microphone stream:
 * headphone pass-through ([MonitorUiState.running]) and raw-sample capture for
 * transcription ([MonitorUiState.capturing], driven by TranscriptionController).
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

    /** Starts headphone pass-through. Must be called while the app is visible: Android
     *  only lets a microphone foreground service start from the foreground. */
    fun start() {
        val headphones = findHeadphones()
        if (headphones == null) {
            _state.update { it.copy(headphones = null, message = NO_HEADPHONES) }
            return
        }
        val result = AudioEngine.setModes(listen = true, capture = _state.value.capturing)
        if (result != 0) {
            _state.update { it.copy(running = false, message = "Could not open audio (error $result)") }
            return
        }
        _state.update { it.copy(running = true, headphones = headphones, message = null) }
        onSessionChanged()
    }

    /** Stops headphone pass-through; capture (if on) keeps running. */
    fun stop(message: String? = null) {
        AudioEngine.setModes(listen = false, capture = _state.value.capturing)
        _state.update { it.copy(running = false, stats = null, message = message) }
        onSessionChanged()
    }

    /**
     * Turns raw microphone capture for transcription on or off. Works with or without
     * headphones. Returns false if the microphone couldn't be opened. Turning it on
     * must happen while the app is visible (see [start]).
     */
    fun setCaptureEnabled(enabled: Boolean): Boolean {
        val result = AudioEngine.setModes(listen = _state.value.running, capture = enabled)
        if (result != 0) {
            _state.update { it.copy(message = "Could not open the microphone (error $result)") }
            return false
        }
        _state.update { it.copy(capturing = enabled) }
        onSessionChanged()
        return true
    }

    /** Stops everything (the notification's Stop action). */
    fun stopAll() {
        AudioEngine.setModes(listen = false, capture = false)
        _state.update { it.copy(running = false, capturing = false, stats = null) }
        onSessionChanged()
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

    /** Keeps the foreground service and the stats/disconnect poller in step with the session. */
    private fun onSessionChanged() {
        val s = _state.value
        if (s.running || s.capturing) {
            ListenService.start(context, listening = s.running, transcribing = s.capturing,
                headphones = s.headphones?.name)
            if (pollJob == null) startPolling()
        } else {
            pollJob?.cancel()
            pollJob = null
            ListenService.stop(context)
        }
    }

    private fun onRouteChanged(streamLost: Boolean = false) {
        val headphones = findHeadphones()
        val previous = _state.value
        _state.update { it.copy(headphones = headphones) }
        when {
            previous.running && headphones == null ->
                // Never let the mic feed the loudspeaker: that causes a feedback howl.
                // Capture for transcription carries on without headphones.
                stop(NO_HEADPHONES)
            previous.running && (streamLost || headphones != previous.headphones) -> {
                // New output device: reopen so the streams pick up its native config.
                // The service is already in the foreground, so this works with the screen off.
                AudioEngine.setModes(listen = false, capture = previous.capturing)
                start()
            }
            previous.capturing && streamLost ->
                // The engine notices the stream died and reopens it.
                setCaptureEnabled(true)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (AudioEngine.consumeDisconnected()) onRouteChanged(streamLost = true)
                if (_state.value.running) _state.update { it.copy(stats = AudioEngine.stats()) }
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
