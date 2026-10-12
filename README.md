# AviFone

An Android app that plays the phone's microphone live into your headphones with very low
delay and minimal jitter. It has a volume slider and an optional ambient-sound reducer, and
keeps working with the screen off.

## Features

- **Low-latency, low-jitter pass-through.** The audio path is native C++ using
  [Oboe](https://github.com/google/oboe): a full-duplex stream in low-latency, exclusive mode
  (the fast MMAP path where the phone supports it). The microphone is read inside the output
  callback, so capture and playback share one clock with no extra buffer between them. The
  output buffer starts as small as possible and grows only if an underrun actually happens.
  The mic uses Android's `VoicePerformance` input mode, which is meant for live monitoring.
- **Volume slider.** Mute, then −40 dB to +12 dB. Changes are smoothed so they never click,
  and a soft limiter keeps loud sounds from reaching full scale.
- **Reduce ambient sound.** A high-pass filter removes rumble (traffic, HVAC, wind), then an
  adaptive expander tracks the background-noise level and lowers sound near it, with
  adjustable strength. It processes one sample at a time with no look-ahead, so it **adds no
  delay**. It reduces steady background noise in the gaps between foreground sounds; it can't
  remove noise that overlaps speech in time and pitch.
- **Works with the screen off.** A microphone foreground service shows a "Listening"
  notification with a Stop button.
- **Feedback protection.** Start only works when headphones are connected, and listening
  stops automatically if they're unplugged, so the mic never plays through the loudspeaker.
- **Live stats.** Latency, sample rate, buffer size, whether MMAP is in use, and glitch counts.

Wired or USB-C headphones give the lowest latency (typically about 10–30 ms). Bluetooth adds
100–300 ms that no app can remove.

## Requirements

- Android 8.0 (API 26) or newer, arm64 or x86_64
- Headphones

## Building

To set up the Android SDK, NDK and Android Studio on Linux without root:

```bash
./setup-android-dev.sh
```

Then build and install:

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug             # install on a connected phone
```

Toolchain: AGP 9.4, Gradle 9.8, Kotlin 2.4, Jetpack Compose, NDK 30, CMake 4.1, Oboe 1.11,
compileSdk 37.

The release build is currently signed with the local debug key so it can be side-loaded.
Configure your own release keystore before publishing.

## Project layout

| Path | Purpose |
|---|---|
| `app/src/main/cpp/LiveEngine.*` | Oboe full-duplex engine: stream setup, audio callback, volume, limiter, stats |
| `app/src/main/cpp/AmbientReducer.*` | Zero-latency high-pass filter and adaptive noise expander |
| `app/src/main/cpp/jni_bridge.cpp` | JNI functions called from Kotlin |
| `app/src/main/java/.../AudioEngine.kt` | Kotlin wrapper around the native engine |
| `app/src/main/java/.../ListenController.kt` | App-wide session state: headphone detection, route changes, settings |
| `app/src/main/java/.../ListenService.kt` | Microphone foreground service, wake lock and notification |
| `app/src/main/java/.../ui/` | Compose UI |
| `setup-android-dev.sh` | Installs the Android development environment |
