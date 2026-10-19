---
name: speech-eval
description: Measure speech-model word error rate (WerTest on device, tools/eval_asr.py and tools/live_tester.py on the laptop) and cut a GitHub release of AviFone with those numbers.
---

# Measuring speech models

- **`WerTest`** (`androidTest`) measures word error rate on `androidTest/assets/wer/manifest.tsv` (file, start, end, text) for the built-in model and every speech model present in `files/models/speech/` on the device (download them in Settings first; the leave-installed flag keeps them). The committed manifest is the synthetic recording; `tools/make_svarah_subset.py` adds Indian-English clips from Svarah (gated on Hugging Face, CC-BY-4.0). Baseline on the synthetic set (emulator, 2026-10-13): Moonshine tiny 16.4 %, Nemotron 3.5 6.9 % (RTF 0.5), Whisper small 9.5 % (RTF 0.5). Whisper large-v3-turbo is untested on a device.
- **Desktop harness** `tools/eval_asr.py` runs the same VAD → recognizer pipeline on the laptop (`.venv` via `python3 -m virtualenv .venv && .venv/bin/pip install sherpa-onnx jiwer soundfile numpy`; models staged under `.gradle/speech-models/desktop/<id>/`, see `tools/README.md`). Use it to compare models or tune VAD/pre-roll before touching the app. **`tools/live_tester.py`** is a Tkinter window on the same pipeline that captures what the computer plays (PulseAudio monitor via `parec`) or a microphone and shows each ticked model's transcript side by side with decode times; paste a reference text to get WER. `--headless` runs it without a window. Model workers are joined on exit: a process that quits mid-decode aborts inside ONNX Runtime.

# Releases

`dist/` is git-ignored and holds local APKs and demo media. Release notes list the measured WER from `WerTest` / `tools/eval_asr.py` for the shipped models. Releases are GitHub Releases on `prasadvenky/AviFone` made with `gh` (installed in `~/.local/bin`). The APK attached to `v1.0` is debug-key signed, and a proper release keystore has not been set up yet. Commits are authored as `prasadvenky <venkateshprasad13@gmail.com>` (repo-local git config).
