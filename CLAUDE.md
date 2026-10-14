# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AviFone is an Android app with a bottom navigation bar of three destinations:

- **Listen** plays the phone microphone live into headphones with minimal latency and jitter. It has a volume slider and an optional zero-latency ambient-sound reducer.
- **Transcribe** (Conversation) transcribes the microphone **on the device** (sherpa-onnx), shows up to 4 speakers in different colors, and keeps a summary of the last 15 minutes. The speech model is chosen in Settings: Moonshine tiny (English, bundled) or one downloaded on first use: **Nemotron 3.5 streaming 0.6B (the default; 40 languages incl. Hindi)**, Whisper small or Whisper large-v3-turbo. The summary card has a **Local AI / Cloud AI** switch: Local uses Gemini Nano (ML Kit) where the phone has it, otherwise a Qwen3 0.6B model that the app downloads once and runs with LiteRT-LM; Cloud sends transcript text to Gemini after Google sign-in. Audio never leaves the phone in either mode.

- **Settings** holds the Google account (sign-out), the speech model (pick, download, delete) and language (English / Hindi / detect), and the Local AI model file (delete or download).

Both features keep running with the screen off. The audio path is native C++ (Oboe); the UI, session logic and Gemini calls are Kotlin/Compose.

Branches: `main` carries the current app (3.0: on-device transcription, Local AI / Cloud AI summaries). The superseded design where Gemini transcribed audio in the cloud is commit `957c419` (the former `AI-Addition` branch, since deleted). `Local-S2T` is the development branch; `main` is fast-forwarded to it at each release.

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
- **`WerTest`** (`androidTest`) measures word error rate on `androidTest/assets/wer/manifest.tsv` (file, start, end, text) for the built-in model and every speech model present in `files/models/speech/` on the device (download them in Settings first; the leave-installed flag keeps them). The committed manifest is the synthetic recording; `tools/make_svarah_subset.py` adds Indian-English clips from Svarah (gated on Hugging Face, CC-BY-4.0). Baseline on the synthetic set (emulator, 2026-10-13): Moonshine tiny ~15 %, Nemotron 3.5 ~7 %.
- **Desktop harness** `tools/eval_asr.py` runs the same VAD → recognizer pipeline on the laptop (`.venv` via `python3 -m virtualenv .venv && .venv/bin/pip install sherpa-onnx jiwer soundfile numpy`; models staged under `.gradle/speech-models/desktop/<id>/`, see `tools/README.md`). Use it to compare models or tune VAD/pre-roll before touching the app.
- **Native DSP** has no automated tests. It was verified by compiling `AmbientReducer.cpp` on the host with a throwaway `main`:
`g++ -O2 -std=c++17 -Iapp/src/main/cpp test.cpp app/src/main/cpp/AmbientReducer.cpp`

Toolchain pins live in `gradle/libs.versions.toml` and `app/build.gradle.kts`: AGP 9.4 (built-in Kotlin, so there is no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8, NDK 30.0.16248370, CMake 4.1.2 and Oboe 1.11 via prefab (requires `-DANDROID_STL=c++_shared`). `compileSdk` is 37 because the current AndroidX libraries refuse 36. `targetSdk` is 36.

On-device speech models: the `fetchSpeechModels` task in `app/build.gradle.kts` downloads the files pinned in `speechPins` from the k2-fsa GitHub releases and verifies their SHA-256.
- **Files:** the sherpa-onnx 1.13.8 AAR with ONNX Runtime statically linked, Moonshine tiny English v2 quantized, Silero VAD, and NeMo SpeakerNet.
- **Where they go:** the download cache is `.gradle/speech-models/` (survives `clean`). Models land in a **generated** assets folder under `build/` (wired in through `androidComponents ... addGeneratedSourceDirectory`), and the AAR becomes a `files(...)` dependency on the task output. Nothing large is in git, but the first build needs internet.
- **Size:** release builds are arm64-only (~107 MB APK); debug builds add x86_64 for the emulator. Models are `noCompress` so they load straight from the APK.
- **Changing a model:** update the URL and its hash together.

Local AI summary: `com.google.ai.edge.litertlm:litertlm-android` (ships arm64-v8a and x86_64 natives, so the emulator works on the CPU backend) and `com.google.mlkit:genai-prompt`. The model file is **not** in the APK: `transcribe/llm/ModelCatalog.kt` pins the Hugging Face URL, size and SHA-256 (from the resolve URL's `x-linked-size` / `x-linked-etag` headers) and `LocalModelManager` downloads it into `files/models/llm/` at runtime. Only ungated repositories work (Gemma 3 needs a licence click and a token).

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
  - `pump()` has two halves: a reader on `Dispatchers.Default` drains `AudioEngine.readCapture` every 200 ms, resamples to 16 kHz and queues chunks in an unbounded `Channel`; a consumer on `speechThread` feeds `LocalTranscriber`. The reader never waits on a model, so a slow decode can't overflow the 10 s native FIFO. `Stats.captureDropped` is still checked each tick and shown as "Some audio was dropped".
  - All native speech work runs on **one dedicated thread** (`speechThread`), because the sherpa-onnx objects aren't thread-safe.
  - The `LocalTranscriber` is created once and reused across sessions. Its sample counter keeps running, so each session anchors wall-clock time to `startSession()`'s value.
  - Model choice: `speech_model` and `speech_language` prefs; `effectiveModel()` is the chosen model if installed, else built-in Moonshine (the status row says so). `ensureRecognizer()` swaps the recognizer on the speech thread between utterances, also mid-session, when the choice, language or install state changes; a load failure falls back to Moonshine with a message.
  - On Stop, `pump()`'s `finally` flushes the utterance in progress, then a final summary runs.
  - It watches `ListenController.state` so that the notification's Stop also ends transcription.
- **`transcribe/local/LocalTranscriber.kt`**: Silero VAD (threshold 0.4, 0.5 s pause, 0.15 s min speech, 8 s soft cap) cuts utterances. Each utterance goes through the current `TextRecognizer` for text and NeMo SpeakerNet for a voice embedding, and `SpeakerClusterer` assigns a label.
  - **8 s cap and hard split.** sherpa-onnx 1.13.8 decodes Moonshine v2 segments over ~9.2 s to **empty text** and reports success (k2-fsa/sherpa-onnx#3471; fixed on master by #3976, in no release yet). The VAD cap is only a soft target, so `recognizeText()` also splits anything over the recognizer's `maxSegmentSeconds` into equal pieces. Drop the Moonshine limit when the AAR gets the fix; the cap also bounds latency.
  - A 12 s history ring supplies `preRollSeconds` of real audio before the VAD's cut (never reaching into the previous utterance). Whisper gains from 0.5 s; Moonshine does worse with it and Nemotron gets nothing beyond its silent lead-in, so it is per recognizer (measured with `tools/eval_asr.py`).
  - `flush()` pads the partial window and calls the library's `vad.flush()`. The VAD is never `reset()`: that restarts its sample counter, which timestamps anchor to.
  - In Python, Moonshine v2 is `from_moonshine_v2(encoder, decoder, tokens)`, and `vad.front` must be copied before `pop()`, or it segfaults.
- **`transcribe/local/TextRecognizer.kt`**: one interface (`transcribe(samples)`, `preRollSeconds`, `maxSegmentSeconds`) with `MoonshineRecognizer` (assets), `WhisperRecognizer` (files, `OfflineWhisperModelConfig`, language "" = detect) and `NemotronRecognizer` (`OnlineRecognizer` used one utterance at a time: 1 s of silent lead-in, the segment, 0.8 s tail, `setOption("language", …)`; endpointing off). Recognizers are created with `assetManager = null` for file paths. Live partial words are a possible follow-up.
- **`transcribe/speech/SpeechModelCatalog.kt`** pins each downloadable model (Hugging Face files with size + SHA-256 from `x-linked-size`/`x-linked-etag`, or a GitHub `tar.bz2` with its hash and the members to extract). **`SpeechModelManager`** downloads into `files/models/speech/<id>/` with `ResumableDownloader`, verifies, unpacks archives with commons-compress, and exposes `states`. Nemotron 3.5 exists only as a GitHub tarball; the Whisper repos serve single files.
- **`transcribe/local/SpeakerClusterer.kt`**: online cosine clustering against running-mean voice models, with at most 4 speakers labelled `S1`–`S4`.
  - The 0.55 threshold comes from a laptop evaluation: same-speaker similarity was ≥ 0.81 and different-speaker ≤ 0.35.
  - Utterances under 1 s are labelled but don't update a voice model.
  - Merged labels are never reused. `SpeakerRegistry` holds display names and merge aliases; a merge also folds the clusterer's voice models together.
- **Summary back ends** all implement `Summarizer` and share `SummaryPrompt` (instructions, plus a 6000-char transcript budget for the small models) and `SummaryJson` (lenient parser: fences, `<think>` tags, key aliases, bullet-line fallback). `TranscriptionController.summarizeIfChanged()` picks the back end from `summaryMode` (SharedPreferences `summary_mode`; default Cloud when Firebase is configured, else Local).
  - **`transcribe/llm/LocalModelManager`** probes ML Kit once (`checkStatus()`; any exception or `UNAVAILABLE`, as on the emulator, means LiteRT-LM), downloads the model with `ResumableDownloader` (plain `HttpURLConnection`, `Range` resume into a `.part`, size and SHA-256 check), and owns the LiteRT-LM engine on its own `llmThread`, separate from `speechThread` so transcription never stalls during a summary. The engine is kept across sessions and closed when the user switches to Cloud or deletes the model.
  - **`LiteRtLmSummarizer`** uses CPU on x86_64 and tries GPU then CPU on arm64; one `Conversation` per summary, thinking disabled. **`NanoSummarizer`** uses the Prompt API with the same prompt.
  - The download runs in the controller's scope with no extra foreground service: it survives rotation, and if the process dies the part file resumes on the next tap.
- **`GeminiSummarizer`** is the only cloud call. It sends transcript text to Firebase AI Logic (`gemini-3.5-flash`, JSON schema, low thinking), and only when the user is signed in. In Kotlin, the SDK builders are `text()`/`inlineData()` and `Schema.string()`/`Schema.double()`; their JVM names (`addText`, `str`, `numDouble`) don't compile from Kotlin. Firebase AI Logic requires App Check: `AviFoneApp` installs the debug provider, and each install's token must be registered in the console.
- **`MainActivity`** and **`ui/`**: `AppScaffold` is a Material 3 `Scaffold` with a bottom `NavigationBar` for Listen, Transcribe and Settings; a badge dot marks a feature that is running while another destination is shown. `SettingsScreen` holds Google sign-out and the Local AI model (delete, or download) so the summary card only carries the Local/Cloud choice, sign-in and download progress. The activity being stopped or destroyed does **not** stop either feature.

Couplings to keep in sync:

- **JNI names encode the package.** `Java_com_example_avifone_AudioEngine_*` in `jni_bridge.cpp` must match the package and object name of `AudioEngine.kt`. A package rename must update both, plus `System.loadLibrary("avifone")` and the CMake target name.
- **The stats array order** in `nativeGetStats` (`jni_bridge.cpp`) must match `AudioEngine.Stats.fromArray`.
- **`LiveEngine` never overrides `start()`/`stop()`.** `FullDuplexStream` already declares virtual `start()`/`stop()` with different return types, so the engine's own entry point is `setModes()`.
- **The audio callback runs on a real-time thread.** Parameters cross threads only through `std::atomic`s written by the UI thread. Never lock, allocate or log inside `onBothStreamsReady`. `mLock` guards only start, stop and stats.
- **Mic foreground services can only start while the app is visible.** That's why Start lives in the UI and the notification only offers Stop. Restarts on route changes work in the background only because the service is already foreground.

## Gotchas

- Keep the launcher icon in `res/mipmap-anydpi-v26/`. Lint calls the `-v26` suffix redundant, but moving it to `mipmap-anydpi` makes AAPT fail with "resource mipmap/ic_launcher not found".
- `sdkmanager` is now a wrapper around Google's "Android CLI". It **exits 0 when a package name doesn't exist**, so verify installs on disk. Platform 37 is named `platforms;android-37.0`, not `android-37`.
- The transcript is memory-only and pruned to 15 minutes, and audio never leaves the device. Persisted: the consent flag, `summary_mode`, `speech_model` and `speech_language` in SharedPreferences, the Local AI model under `files/models/llm/`, and speech models under `files/models/speech/<id>/`. Keep it that way: the cloud summary is scoped to personal, non-sensitive use on Gemini's free tier.
- The emulator AVD needs 4 GB or more of RAM for the Local AI model, and the first local summary there takes a minute or two on the x86_64 CPU. Whisper large-v3-turbo (1 GB int8) needs a 6 GB AVD. Nemotron 3.5 ran on the 2 GB AVD at RTF ~0.6 (x86_64); Whisper was not tested on the device for 3.1.
- Downloading in the emulator works; expect the 475 MB Nemotron archive to take a minute or two to unpack.
- The emulator's host-audio input proved unreliable on this machine: speech arrived ~30 dB quieter and missing most of its bass, or as pure digital silence. Test speech recognition with `LocalTranscriberTest` or a real phone, not the emulator mic.
- The emulator has no headphone device, so Start stays disabled there. Testing on the emulator means temporarily making `ListenController.findHeadphones()` return a fake `Headphones(...)` instead of `null`, building, and then restoring the file. Never commit that override.
- The emulator mic is silent unless it is launched with `-allow-host-audio`. With that flag it uses the host's default mic and its output goes to the host speakers, which howls. For audible demos, point it at a PulseAudio null-sink monitor playing a test file (`PULSE_SOURCE=<sink>.monitor`). Shut the emulator down before unloading the sink, or it falls back to the real mic.
- Emulator latency (~145 ms, 960-frame bursts, no MMAP) says nothing about real devices.
- Bluetooth output is allowed but adds 100–300 ms; the UI warns about it.

## Releases

`dist/` is git-ignored and holds local APKs and demo media. Release notes list the measured WER from `WerTest` / `tools/eval_asr.py` for the shipped models. Releases are GitHub Releases on `prasadvenky/AviFone` made with `gh` (installed in `~/.local/bin`). The APK attached to `v1.0` is debug-key signed, and a proper release keystore has not been set up yet. Commits are authored as `prasadvenky <venkateshprasad13@gmail.com>` (repo-local git config).
