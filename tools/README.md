# Speech-to-text evaluation tools

Two scripts measure the app's transcription pipeline outside the phone, so models and VAD
settings can be compared in seconds rather than by listening.

## Setup (once)

```bash
python3 -m pip install --user virtualenv        # if `python3 -m venv` lacks ensurepip
python3 -m virtualenv .venv
.venv/bin/pip install sherpa-onnx jiwer soundfile numpy
```

Stage the models under `.gradle/speech-models/desktop/` in the same layout the phone uses
(`<model id>/` with the file names from `SpeechModelCatalog.kt`), plus `silero_vad.onnx`:

```bash
D=.gradle/speech-models; mkdir -p $D/desktop/{moonshine-tiny,nemotron-3.5-560ms,whisper-small,whisper-turbo}
cp $D/silero_vad.onnx $D/desktop/
tar xjf $D/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2 --strip-components=1 -C $D/desktop/moonshine-tiny
curl -L -o /tmp/nemotron.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2
tar xjf /tmp/nemotron.tar.bz2 --strip-components=1 -C $D/desktop/nemotron-3.5-560ms --wildcards '*.onnx' '*/tokens.txt'
W=https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main
curl -L -o $D/desktop/whisper-small/encoder.int8.onnx $W/small-encoder.int8.onnx
curl -L -o $D/desktop/whisper-small/decoder.int8.onnx $W/small-decoder.int8.onnx
curl -L -o $D/desktop/whisper-small/tokens.txt       $W/small-tokens.txt
```

Whisper turbo is the same with `turbo-` file names from `sherpa-onnx-whisper-turbo`.

## `eval_asr.py` — word error rate of the app's pipeline

```bash
.venv/bin/python tools/eval_asr.py --model nemotron-3.5-560ms \
    --manifest app/src/androidTest/assets/wer/manifest.tsv --audio-dir app/src/androidTest/assets -v
```

It replicates `LocalTranscriber`: a second of silence, then Silero VAD with the app's
settings cuts utterances; each goes to the model with the app's per-model pre-roll, lead-in
and hard segment limit; the texts are joined per clip and scored with jiwer (lower-cased, no
punctuation). Manifests are TSV (`file`, optional `start_s`, `end_s`, `text`) or Svarah /
NeMo JSON lines. `--pre-roll`, `--nemotron-lead`, `--nemotron-tail`, `--language auto|en|hi`
and `--no-vad` override the app's values for experiments.

Results on the committed synthetic set (16 turns, 87 s, flite voices; laptop, 8 threads):

| Model | WER | RTF |
|---|---|---|
| moonshine-tiny | 11.5 % | 0.02 |
| nemotron-3.5-560ms | 6.1 % | 0.28 |
| whisper-small | 6.9 % (0.5 s pre-roll) | 0.31 |

On the x86_64 emulator (`WerTest`): Moonshine 16.4 %, Nemotron 6.9 %, Whisper small 9.5 %, the
two large models at RTF ~0.5.

Synthetic voices are not Indian English; they only show the pipeline is sound. For the real
question, run the Svarah benchmark below.

## Svarah (Indian English)

[Svarah](https://github.com/AI4Bharat/Svarah) is 9.6 h of Indian-accented English from 117
speakers (CC-BY-4.0). The Hugging Face copy needs a login and a click to accept the terms:

```bash
.venv/bin/pip install "huggingface_hub[cli]"
.venv/bin/huggingface-cli login
.venv/bin/huggingface-cli download ai4bharat/Svarah --repo-type dataset --local-dir ~/datasets/Svarah
.venv/bin/python tools/eval_asr.py --model nemotron-3.5-560ms --manifest ~/datasets/Svarah/svarah_manifest.json --limit 200
```

The GitHub layout is `audio/*.wav` with `svarah_manifest.json` (one JSON object per line:
`audio_filepath`, `duration`, `text`). If the Hugging Face copy is parquet instead, export it
to that layout first.

## `make_svarah_subset.py` — Indian English on the device

```bash
.venv/bin/python tools/make_svarah_subset.py --svarah ~/datasets/Svarah --count 20
```

Copies 20 fixed clips (3–10 s) as 16 kHz WAV into `app/src/androidTest/assets/wer/svarah/`
and appends them to `wer/manifest.tsv`, so `WerTest` scores them on the emulator or phone for
every installed model (download the models in Settings first; the
`leaveApksInstalledAfterRun` flag keeps them between runs). Keep the attribution line in the
commit.
