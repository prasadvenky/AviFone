# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AviFone is an Android app that plays the phone microphone live into headphones with minimal latency and jitter. It has a volume slider and an optional zero-latency ambient-sound reducer, and keeps running with the screen off. The audio path is native C++ (Oboe); the UI and session logic are Kotlin/Compose.

## Environment and commands

The SDK, NDK, CMake and Android Studio live in the user's home directory and are installed by `./setup-android-dev.sh`, which needs no root and is safe to re-run. Gradle must run on Android Studio's bundled JDK:

```bash
export JAVA_HOME=~/Android/android-studio/jbr ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug            # debug APK (native code unoptimized)
./gradlew assembleRelease          # optimized; signed with the local debug key
./gradlew lintDebug                # report: app/build/reports/lint-results-debug.txt
./gradlew installDebug             # install on a connected device
```

There are no unit or instrumented tests. The DSP was verified by compiling `AmbientReducer.cpp` on the host with a throwaway `main` that feeds synthetic noise and tone bursts:
`g++ -O2 -std=c++17 -Iapp/src/main/cpp test.cpp app/src/main/cpp/AmbientReducer.cpp`

Toolchain pins live in `gradle/libs.versions.toml` and `app/build.gradle.kts`: AGP 9.4 (built-in Kotlin, so there is no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8, NDK 30.0.16248370, CMake 4.1.2 and Oboe 1.11 via prefab (requires `-DANDROID_STL=c++_shared`). `compileSdk` is 37 because the current AndroidX libraries refuse 36. `targetSdk` is 36.

## Architecture

The audio pipeline crosses three layers, and each layer has one owner:

- **`cpp/LiveEngine`**: an `oboe::FullDuplexStream`. The output stream's data callback drives everything and reads the input non-blocking inside it, so there is one clock and no extra FIFO between capture and playback. The output is opened first and the input is opened at the output's sample rate. Input presets fall back in order: `VoicePerformance`, then `Unprocessed`, then `VoiceRecognition`. A `LatencyTuner` grows the output buffer only when an underrun occurs. Per sample, `onBothStreamsReady` runs the reducer, crossfades dry and wet, applies smoothed gain, then applies the soft limiter.
- **`cpp/AmbientReducer`**: a high-pass biquad followed by an adaptive downward expander that tracks the noise floor. It is strictly sample-by-sample with no look-ahead, and the zero-latency guarantee depends on keeping it that way. Don't add FFT or block processing without accepting the added delay.
- **`cpp/jni_bridge.cpp`**: one global `LiveEngine` behind `extern "C"` JNI functions.
- **`AudioEngine.kt`**: the Kotlin mirror of the JNI functions.
- **`ListenController.kt`**: a process-wide singleton, not a ViewModel, that owns the session. It holds the UI `StateFlow`, headphone detection through an `AudioDeviceCallback`, and a 250 ms poll loop that reads stats and `consumeDisconnected()`. On a route change it stops when headphones disappear (feedback protection) and restarts when the device changes or the stream errors.
- **`ListenService.kt`**: a `microphone`-type foreground service that does no audio work. It keeps the process foreground so Android doesn't silence the mic with the screen off, holds a partial wake lock, and shows the notification with a Stop action. `ListenController.start()` starts it and `stop()` stops it.
- **`MainActivity`** and **`ui/`**: render `ListenController.state`. The activity being stopped or destroyed does **not** stop listening.

Couplings to keep in sync:

- **JNI names encode the package.** `Java_com_example_avifone_AudioEngine_*` in `jni_bridge.cpp` must match the package and object name of `AudioEngine.kt`. A package rename must update both, plus `System.loadLibrary("avifone")` and the CMake target name.
- **The stats array order** in `nativeGetStats` (`jni_bridge.cpp`) must match `AudioEngine.Stats.fromArray`.
- **`LiveEngine` uses `startEngine()`/`stopEngine()`**, not `start()`/`stop()`, because `FullDuplexStream` already declares virtual `start()`/`stop()` with different return types.
- **The audio callback runs on a real-time thread.** Parameters cross threads only through `std::atomic`s written by the UI thread. Never lock, allocate or log inside `onBothStreamsReady`. `mLock` guards only start, stop and stats.
- **Mic foreground services can only start while the app is visible.** That's why Start lives in the UI and the notification only offers Stop. Restarts on route changes work in the background only because the service is already foreground.

## Gotchas

- Keep the launcher icon in `res/mipmap-anydpi-v26/`. Lint calls the `-v26` suffix redundant, but moving it to `mipmap-anydpi` makes AAPT fail with "resource mipmap/ic_launcher not found".
- `sdkmanager` is now a wrapper around Google's "Android CLI". It **exits 0 when a package name doesn't exist**, so verify installs on disk. Platform 37 is named `platforms;android-37.0`, not `android-37`.
- The emulator has no headphone device, so Start stays disabled there. Testing on the emulator means temporarily making `ListenController.findHeadphones()` return a fake `Headphones(...)` instead of `null`, building, and then restoring the file. Never commit that override.
- The emulator mic is silent unless it is launched with `-allow-host-audio`. With that flag it uses the host's default mic and its output goes to the host speakers, which howls. For audible demos, point it at a PulseAudio null-sink monitor playing a test file (`PULSE_SOURCE=<sink>.monitor`). Shut the emulator down before unloading the sink, or it falls back to the real mic.
- Emulator latency (~145 ms, 960-frame bursts, no MMAP) says nothing about real devices.
- Bluetooth output is allowed but adds 100–300 ms; the UI warns about it.

## Releases

`dist/` is git-ignored and holds local APKs and demo media. Releases are GitHub Releases on `prasadvenky/AviFone` made with `gh` (installed in `~/.local/bin`). The APK attached to `v1.0` is debug-key signed, and a proper release keystore has not been set up yet. Commits are authored as `prasadvenky <venkateshprasad13@gmail.com>` (repo-local git config).
