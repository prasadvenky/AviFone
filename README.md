# AviFone

An Android app that plays the phone's microphone live into your headphones with very low
delay and minimal jitter. It has a volume slider and an optional ambient-sound reducer, and
keeps working with the screen off. A second tab transcribes the conversation around you on
the phone itself, shows who said what in a different color for each of up to 4 speakers, and
(with Google sign-in) keeps a Gemini summary of the last 15 minutes.

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

### Conversation tab

- Tap **Start transcribing**. Speech is turned into text **on the phone** with small open
  models, so it works offline and audio never leaves the device. It works with or without
  headphone listening; when both are on, they share the same microphone stream.
- A line appears about a second after each person finishes speaking.
- **Up to 4 speakers**, each with their own color, told apart on the phone by their voice.
  Tap a speaker to rename them, or to merge them into another speaker if one person was
  split in two.
- **"Last 15 minutes" summary**: sign in with Google, and the transcript *text* is sent to
  Gemini to write the topic, key points and action items, refreshed every minute. Sign-in
  is needed only for the summary.
- The transcript is kept only in memory, and only for 15 minutes. Transcription keeps going
  with the screen off.
- Before the first start, a notice asks you to make sure everyone being recorded agrees.

| On-device model | Size | License |
|---|---|---|
| [Moonshine](https://github.com/moonshine-ai/moonshine) tiny English (v2, quantized): speech to text | ~44 MB | MIT |
| [Silero VAD](https://github.com/snakers4/silero-vad): detects when someone is talking | 0.6 MB | MIT |
| NVIDIA NeMo SpeakerNet: voice fingerprints for telling speakers apart | 23 MB | Apache-2.0 (NeMo) |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 runtime (with ONNX Runtime) | ~24 MB native | Apache-2.0 |

The models are downloaded and checksum-verified at build time and bundled into the APK, so
the release APK is about 107 MB. English only.

Wired or USB-C headphones give the lowest latency (typically about 10–30 ms). Bluetooth adds
100–300 ms that no app can remove.

## Requirements

- Android 8.0 (API 26) or newer, arm64 or x86_64
- Headphones, for the Listen tab
- A Google account and internet, only for the Conversation tab's AI summary

## Building

To set up the Android SDK, NDK and Android Studio on Linux without root:

```bash
./setup-android-dev.sh
```

Then build and install:

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug             # install on a connected phone
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew connectedDebugAndroidTest  # on-device speech test (emulator or phone)
```

Toolchain: AGP 9.4, Gradle 9.8, Kotlin 2.4, Jetpack Compose, NDK 30, CMake 4.1, Oboe 1.11,
Firebase BoM 35, compileSdk 37.

### Firebase setup (needed for the Conversation tab)

Transcription works without Firebase; only the AI summary needs it. The first build also
needs internet, to download the speech models. To enable the summary:

1. In the [Firebase console](https://console.firebase.google.com), create a project and add
   an Android app with package name `com.example.avifone`.
2. Add the SHA-1 fingerprint of the key that signs your builds:
   `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`
3. **Authentication → Sign-in method**: enable **Google**.
4. **AI Logic**: enable it with the **Gemini Developer API**.
5. Download `google-services.json` into `app/`, then rebuild. The file is git-ignored.

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
| `app/src/main/java/.../transcribe/local/` | On-device speech-to-text and speaker clustering (sherpa-onnx) |
| `app/src/main/java/.../transcribe/` | Conversation session, transcript, speaker names, Gemini summary, sign-in |
| `app/src/main/java/.../ui/` | Compose UI (Listen and Conversation tabs) |
| `setup-android-dev.sh` | Installs the Android development environment |
