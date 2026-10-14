# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AviFone is an Android app with two tabs:

- **Listen** plays the phone microphone live into headphones with minimal latency and jitter. It has a volume slider and an optional zero-latency ambient-sound reducer.
- **Conversation** transcribes the microphone **on the device** (sherpa-onnx), shows up to 4 speakers in different colors, and, if the user signs in with Google, keeps a Gemini summary of the last 15 minutes. Only transcript text goes to Gemini; audio never leaves the phone.

Both keep running with the screen off. The audio path is native C++ (Oboe); the UI, session logic and Gemini calls are Kotlin/Compose.

Branches: `main` has Listen only. `AI-Addition` has the Conversation tab with Gemini doing transcription and speaker separation from audio. `Local-S2T` (this design) does both on the device.

## Environment and commands

The SDK, NDK, CMake and Android Studio live in the user's home directory and are installed by `./setup-android-dev.sh`, which needs no root and is safe to re-run. Gradle must run on Android Studio's bundled JDK:

```bash
export JAVA_HOME=~/Android/android-studio/jbr ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug            # debug APK (native code unoptimized)
./gradlew assembleRelease          # optimized; signed with the local debug key
./gradlew lintDebug                # report: app/build/reports/lint-results-debug.txt
./gradlew installDebug             # install on a connected device
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew testDebugUnitTest --tests '*SpeakerClustererTest'  # a single test class
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

- **Unit tests** (`app/src/test`) cover the pure-Kotlin logic: resampler, speaker clusterer, speaker registry, and transcript pruning. `org.json` is a stub in local unit tests, so the real library is a `testImplementation`.
- **`LocalTranscriberTest`** (`app/src/androidTest`) runs the real bundled models on a synthetic 4-voice recording (`androidTest/assets/conversation4_16k.wav` plus `conversation4_truth.tsv`), feeding it straight into `LocalTranscriber` without the mic. It asserts 4 speakers, purity ≥ 0.9, and at least 14 of 16 turns recognized. On the emulator it takes ~10 s. The leave-installed flag stops the run from uninstalling the app and wiping its sign-in.
- **Native DSP** has no automated tests. It was verified by compiling `AmbientReducer.cpp` on the host with a throwaway `main`:
`g++ -O2 -std=c++17 -Iapp/src/main/cpp test.cpp app/src/main/cpp/AmbientReducer.cpp`

Toolchain pins live in `gradle/libs.versions.toml` and `app/build.gradle.kts`: AGP 9.4 (built-in Kotlin, so there is no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8, NDK 30.0.16248370, CMake 4.1.2 and Oboe 1.11 via prefab (requires `-DANDROID_STL=c++_shared`). `compileSdk` is 37 because the current AndroidX libraries refuse 36. `targetSdk` is 36.

On-device speech models: the `fetchSpeechModels` task in `app/build.gradle.kts` downloads the files pinned in `speechPins` from the k2-fsa GitHub releases and verifies their SHA-256.
- **Files:** the sherpa-onnx 1.13.8 AAR with ONNX Runtime statically linked, Moonshine tiny English v2 quantized, Silero VAD, and NeMo SpeakerNet.
- **Where they go:** the download cache is `.gradle/speech-models/` (survives `clean`). Models land in a **generated** assets folder under `build/` (wired in through `androidComponents ... addGeneratedSourceDirectory`), and the AAR becomes a `files(...)` dependency on the task output. Nothing large is in git, but the first build needs internet.
- **Size:** release builds are arm64-only (~107 MB APK); debug builds add x86_64 for the emulator. Models are `noCompress` so they load straight from the APK.
- **Changing a model:** update the URL and its hash together.

Firebase: `app/google-services.json` is per-developer and git-ignored. `app/build.gradle.kts` applies the google-services plugin **only if that file exists**, so the app always builds. Without it, `AuthRepository.isConfigured` is false; transcription still works and only the summary card says Firebase isn't set up. Because `R.string.default_web_client_id` exists only when the plugin runs, it is looked up by name at runtime.

## Architecture

The audio pipeline crosses three layers, and each layer has one owner:

- **`cpp/LiveEngine`** owns the microphone. `setModes(listen, capture)` derives the stream layout: **Duplex** whenever listen is on, **InputOnly** (shared, `VoiceRecognition` preset, its own `CaptureCallback`) for capture alone, otherwise **Off**. It reopens streams only when the layout changes or a stream died. Capture pushes **raw** mic samples, taken before noise reduction and volume, into a lock-free `oboe::FifoBuffer` that is allocated once and never freed, so the Kotlin reader needs no lock. In Duplex, the output stream's data callback drives everything and reads the input non-blocking inside it, so there is one clock and no extra FIFO between capture and playback. The output is opened first and the input is opened at the output's sample rate. Input presets fall back in order: `VoicePerformance`, then `Unprocessed`, then `VoiceRecognition`. A `LatencyTuner` grows the output buffer only when an underrun occurs. Per sample, `onBothStreamsReady` runs the reducer, crossfades dry and wet, applies smoothed gain, then applies the soft limiter.
- **`cpp/AmbientReducer`**: a high-pass biquad followed by an adaptive downward expander that tracks the noise floor. It is strictly sample-by-sample with no look-ahead, and the zero-latency guarantee depends on keeping it that way. Don't add FFT or block processing without accepting the added delay.
- **`cpp/jni_bridge.cpp`**: one global `LiveEngine` behind `extern "C"` JNI functions.
- **`AudioEngine.kt`**: the Kotlin mirror of the JNI functions.
- **`ListenController.kt`**: a process-wide singleton, not a ViewModel, and the **only** caller of `AudioEngine.setModes`. It tracks `running` (listening) and `capturing` separately. It also holds headphone detection through an `AudioDeviceCallback`, and a 250 ms poll loop for stats and `consumeDisconnected()`. Unplugging headphones stops listening only (feedback protection); capture continues. It starts the foreground service while either feature is on, and `stopAll()` is the notification's Stop.
- **`ListenService.kt`**: a `microphone`-type foreground service that does no audio work. It keeps the process foreground so Android doesn't silence the mic with the screen off, holds a partial wake lock, and shows a notification whose title reflects listening, transcribing or both.
- **`transcribe/TranscriptionController.kt`**: a singleton that turns capture on through `ListenController.setCaptureEnabled`. Transcription needs only consent and the mic permission, not sign-in.
  - `pump()` drains `AudioEngine.readCapture`, resamples to 16 kHz, and feeds `LocalTranscriber` continuously.
  - All native speech work runs on **one dedicated thread** (`speechThread`), because the sherpa-onnx objects aren't thread-safe.
  - The `LocalTranscriber` is created once (loading takes about 1 s) and reused across sessions. Its sample counter keeps running, so each session anchors wall-clock time to `samplesAccepted` at its start.
  - On Stop, `pump()`'s `finally` flushes the utterance in progress, then a final summary runs.
  - It watches `ListenController.state` so that the notification's Stop also ends transcription.
- **`transcribe/local/LocalTranscriber.kt`**: Silero VAD cuts utterances at pauses of 0.5 s or more. Each utterance goes through Moonshine for text and NeMo SpeakerNet for a voice embedding, and `SpeakerClusterer` assigns a label.
  - The Kotlin `Vad` has **no `flush()`**, so `flush()` feeds 0.8 s of silence instead.
  - Moonshine v2 uses `OfflineMoonshineModelConfig(encoder, mergedDecoder)`. In Python the equivalent is `from_moonshine_v2`, and the Python `vad.front` must be copied before `pop()`, or it segfaults.
- **`transcribe/local/SpeakerClusterer.kt`**: online cosine clustering against running-mean voice models, with at most 4 speakers labelled `S1`–`S4`.
  - The 0.55 threshold comes from a laptop evaluation: same-speaker similarity was ≥ 0.81 and different-speaker ≤ 0.35.
  - Utterances under 1 s are labelled but don't update a voice model.
  - Merged labels are never reused. `SpeakerRegistry` holds display names and merge aliases; a merge also folds the clusterer's voice models together.
- **`GeminiSummarizer`** is the only cloud call. It sends transcript text to Firebase AI Logic (`gemini-3.5-flash`, JSON schema, low thinking), and only when the user is signed in. In Kotlin, the SDK builders are `text()`/`inlineData()` and `Schema.string()`/`Schema.double()`; their JVM names (`addText`, `str`, `numDouble`) don't compile from Kotlin. Firebase AI Logic requires App Check: `AviFoneApp` installs the debug provider, and each install's token must be registered in the console.
- **`MainActivity`** and **`ui/`**: `AppScaffold` holds the Listen/Conversation tabs and renders both controllers' state. The activity being stopped or destroyed does **not** stop either feature.

Couplings to keep in sync:

- **JNI names encode the package.** `Java_com_example_avifone_AudioEngine_*` in `jni_bridge.cpp` must match the package and object name of `AudioEngine.kt`. A package rename must update both, plus `System.loadLibrary("avifone")` and the CMake target name.
- **The stats array order** in `nativeGetStats` (`jni_bridge.cpp`) must match `AudioEngine.Stats.fromArray`.
- **`LiveEngine` never overrides `start()`/`stop()`.** `FullDuplexStream` already declares virtual `start()`/`stop()` with different return types, so the engine's own entry point is `setModes()`.
- **The audio callback runs on a real-time thread.** Parameters cross threads only through `std::atomic`s written by the UI thread. Never lock, allocate or log inside `onBothStreamsReady`. `mLock` guards only start, stop and stats.
- **Mic foreground services can only start while the app is visible.** That's why Start lives in the UI and the notification only offers Stop. Restarts on route changes work in the background only because the service is already foreground.

## Gotchas

- Keep the launcher icon in `res/mipmap-anydpi-v26/`. Lint calls the `-v26` suffix redundant, but moving it to `mipmap-anydpi` makes AAPT fail with "resource mipmap/ic_launcher not found".
- `sdkmanager` is now a wrapper around Google's "Android CLI". It **exits 0 when a package name doesn't exist**, so verify installs on disk. Platform 37 is named `platforms;android-37.0`, not `android-37`.
- The transcript is memory-only and pruned to 15 minutes, and audio never leaves the device. The consent flag in SharedPreferences is the only thing persisted. Keep it that way: the summary is scoped to personal, non-sensitive use on Gemini's free tier.
- The emulator's host-audio input proved unreliable on this machine: speech arrived ~30 dB quieter and missing most of its bass, or as pure digital silence. Test speech recognition with `LocalTranscriberTest` or a real phone, not the emulator mic.
- The emulator has no headphone device, so Start stays disabled there. Testing on the emulator means temporarily making `ListenController.findHeadphones()` return a fake `Headphones(...)` instead of `null`, building, and then restoring the file. Never commit that override.
- The emulator mic is silent unless it is launched with `-allow-host-audio`. With that flag it uses the host's default mic and its output goes to the host speakers, which howls. For audible demos, point it at a PulseAudio null-sink monitor playing a test file (`PULSE_SOURCE=<sink>.monitor`). Shut the emulator down before unloading the sink, or it falls back to the real mic.
- Emulator latency (~145 ms, 960-frame bursts, no MMAP) says nothing about real devices.
- Bluetooth output is allowed but adds 100–300 ms; the UI warns about it.

## Releases

`dist/` is git-ignored and holds local APKs and demo media. Releases are GitHub Releases on `prasadvenky/AviFone` made with `gh` (installed in `~/.local/bin`). The APK attached to `v1.0` is debug-key signed, and a proper release keystore has not been set up yet. Commits are authored as `prasadvenky <venkateshprasad13@gmail.com>` (repo-local git config).
