#!/usr/bin/env python3
"""Measure the app's speech-to-text pipeline on a labelled set, on the laptop.

Replicates LocalTranscriber: Silero VAD with the app's settings cuts utterances, each is
decoded by the chosen model (split into <= 8 s pieces like the app), and the texts are joined.
Reports word error rate (WER), substitutions / deletions / insertions, the number of segments
of a second or more that decoded to nothing, and the real-time factor.

Manifest formats:
  * Svarah / NeMo JSON lines: {"audio_filepath": ..., "duration": ..., "text": ...}
  * TSV: file<TAB>text, or file<TAB>start_s<TAB>end_s<TAB>text (a slice of a longer file;
    '#' lines are comments). Paths are relative to --audio-dir (default: the manifest's folder).

Model directories mirror the phone's files/models/speech/<id>/ layout (see tools/README.md).
"""
import argparse
import json
import os
import re
import sys
import time

import jiwer
import numpy as np
import sherpa_onnx
import soundfile as sf

SAMPLE_RATE = 16_000
WINDOW = 512
MAX_SEGMENT_SECONDS = 8.0       # LocalTranscriber.MAX_SEGMENT_SECONDS
NEMOTRON_TAIL_SECONDS = 0.8     # NemotronRecognizer.TAIL_SECONDS
NEMOTRON_LEAD_SECONDS = 1.0     # NemotronRecognizer.LEAD_SECONDS
# TextRecognizer.preRollSeconds / maxSegmentSeconds per model
PRE_ROLL = {"moonshine-tiny": 0.0, "nemotron-3.5-560ms": 0.0, "whisper-small": 0.5, "whisper-turbo": 0.5}
MAX_SEGMENT = {"moonshine-tiny": 8.0, "nemotron-3.5-560ms": 60.0, "whisper-small": 28.0, "whisper-turbo": 28.0}

MODELS = ["moonshine-tiny", "nemotron-3.5-560ms", "whisper-small", "whisper-turbo"]


def make_vad(model_dir):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = os.path.join(model_dir, "silero_vad.onnx")
    cfg.silero_vad.threshold = 0.4
    cfg.silero_vad.min_silence_duration = 0.5
    cfg.silero_vad.min_speech_duration = 0.15
    cfg.silero_vad.max_speech_duration = MAX_SEGMENT_SECONDS
    cfg.silero_vad.window_size = WINDOW
    cfg.sample_rate = SAMPLE_RATE
    cfg.num_threads = 1
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=120)


class Recognizer:
    """One utterance in, text out, matching the app's TextRecognizer classes."""

    def __init__(self, model, model_dir, language, threads, nemotron_tail=NEMOTRON_TAIL_SECONDS,
                 nemotron_lead=NEMOTRON_LEAD_SECONDS):
        d = os.path.join(model_dir, model)
        self.model = model
        self.language = language
        self.online = None
        if model == "moonshine-tiny":
            self.rec = sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
                encoder=os.path.join(d, "encoder_model.ort"),
                decoder=os.path.join(d, "decoder_model_merged.ort"),
                tokens=os.path.join(d, "tokens.txt"),
                num_threads=threads,
            )
        elif model.startswith("whisper"):
            self.rec = sherpa_onnx.OfflineRecognizer.from_whisper(
                encoder=os.path.join(d, "encoder.int8.onnx"),
                decoder=os.path.join(d, "decoder.int8.onnx"),
                tokens=os.path.join(d, "tokens.txt"),
                language="" if language == "auto" else language,
                task="transcribe",
                num_threads=threads,
            )
        elif model.startswith("nemotron"):
            self.online = sherpa_onnx.OnlineRecognizer.from_transducer(
                tokens=os.path.join(d, "tokens.txt"),
                encoder=os.path.join(d, "encoder.int8.onnx"),
                decoder=os.path.join(d, "decoder.int8.onnx"),
                joiner=os.path.join(d, "joiner.int8.onnx"),
                num_threads=threads,
                enable_endpoint_detection=False,
            )
            self.tail = np.zeros(int(nemotron_tail * SAMPLE_RATE), dtype=np.float32)
            self.lead = np.zeros(int(nemotron_lead * SAMPLE_RATE), dtype=np.float32)
        else:
            sys.exit(f"unknown model {model}")

    def transcribe(self, samples):
        """Text for a segment of any length (over-long ones are split like the app does)."""
        max_samples = int(MAX_SEGMENT[self.model] * SAMPLE_RATE)
        pieces = (len(samples) + max_samples - 1) // max_samples
        if pieces <= 1:
            return self._once(samples)
        size = (len(samples) + pieces - 1) // pieces
        return " ".join(self._once(samples[i * size:(i + 1) * size]) for i in range(pieces)).strip()

    def _once(self, samples):
        if self.online is not None:
            s = self.online.create_stream()
            s.set_option("language", self.language)
            if len(self.lead):
                s.accept_waveform(SAMPLE_RATE, self.lead)
            s.accept_waveform(SAMPLE_RATE, samples)
            s.accept_waveform(SAMPLE_RATE, self.tail)
            s.input_finished()
            while self.online.is_ready(s):
                self.online.decode_stream(s)
            r = self.online.get_result(s)
            return (r if isinstance(r, str) else r.text).strip()
        s = self.rec.create_stream()
        s.accept_waveform(SAMPLE_RATE, samples)
        self.rec.decode_stream(s)
        return s.result.text.strip()


def read_manifest(path):
    base = os.path.dirname(os.path.abspath(path))
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            if line.lstrip().startswith("{"):
                j = json.loads(line)
                items.append((j["audio_filepath"], None, None, j["text"]))
            else:
                parts = line.split("\t")
                if len(parts) == 2:
                    items.append((parts[0], None, None, parts[1]))
                elif len(parts) >= 4:
                    start = float(parts[1]) if parts[1] else None
                    end = float(parts[2]) if parts[2] else None
                    items.append((parts[0], start, end, "\t".join(parts[3:])))
                else:
                    sys.exit(f"bad manifest line: {line!r}")
    return base, items


def load_audio(path, start, end):
    audio, rate = sf.read(path, dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if rate != SAMPLE_RATE:  # plain linear resampling is enough for an evaluation
        n = int(round(len(audio) * SAMPLE_RATE / rate))
        audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
    if start is not None or end is not None:
        a = int((start or 0) * SAMPLE_RATE)
        b = int(end * SAMPLE_RATE) if end is not None else len(audio)
        audio = audio[a:b]
    return audio


def run_clip(vad, rec, audio, pre_roll=0.0):
    """Feeds one clip through VAD + recognizer; returns (text, empty_long_segments)."""
    vad.reset()
    texts, empty = [], 0
    # A second of silence first, like the pause before someone speaks in the live app: the VAD
    # then places the segment start a little before the speech, as it does on the phone.
    audio = np.concatenate([np.zeros(SAMPLE_RATE, dtype=np.float32), audio])
    # A second of silence first, like the pause before someone speaks in the live app.
    audio = np.concatenate([np.zeros(SAMPLE_RATE, dtype=np.float32), audio])
    pad = (-len(audio)) % WINDOW
    if pad:
        audio = np.concatenate([audio, np.zeros(pad, dtype=np.float32)])
    pre = int(pre_roll * SAMPLE_RATE)

    def drain():
        nonlocal empty
        while not vad.empty():
            seg = vad.front
            samples = np.array(seg.samples, dtype=np.float32)  # copy before pop()
            start = seg.start
            vad.pop()
            if pre:
                samples = np.concatenate([audio[max(0, start - pre):start], samples])
            text = rec.transcribe(samples)
            if not re.search(r"[0-9A-Za-zऀ-ॿ]", text):
                if len(samples) >= SAMPLE_RATE:
                    empty += 1
                continue
            texts.append(text)

    for i in range(0, len(audio), WINDOW):
        vad.accept_waveform(audio[i:i + WINDOW])
        drain()
    vad.flush()
    drain()
    return " ".join(texts), empty


NORMALIZE = jiwer.Compose([
    jiwer.ToLowerCase(),
    jiwer.RemovePunctuation(),
    jiwer.RemoveMultipleSpaces(),
    jiwer.Strip(),
    jiwer.ReduceToListOfListOfWords(),
])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, choices=MODELS)
    ap.add_argument("--model-dir", default=os.path.join(os.path.dirname(__file__), "..", ".gradle", "speech-models", "desktop"))
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--audio-dir", help="base for relative audio paths (default: the manifest's folder)")
    ap.add_argument("--language", default="en", help="en, hi, ... or auto (ignored by moonshine-tiny)")
    ap.add_argument("--limit", type=int, help="only the first N clips")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--no-vad", action="store_true", help="decode each clip whole (still split at 8 s)")
    ap.add_argument("--pre-roll", type=float,
                    help="seconds of real audio before the VAD's cut to prepend (default: the app's value per model)")
    ap.add_argument("--nemotron-tail", type=float, default=NEMOTRON_TAIL_SECONDS,
                    help="seconds of silence appended so the streaming model emits its last words")
    ap.add_argument("--nemotron-lead", type=float, default=NEMOTRON_LEAD_SECONDS,
                    help="seconds of silence prepended so the first word isn't lost to warm-up")
    ap.add_argument("-v", "--verbose", action="store_true", help="print reference and hypothesis per clip")
    args = ap.parse_args()

    base, items = read_manifest(args.manifest)
    if args.audio_dir:
        base = args.audio_dir
    if args.limit:
        items = items[:args.limit]

    vad = make_vad(args.model_dir)
    rec = Recognizer(args.model, args.model_dir, args.language, args.threads, args.nemotron_tail, args.nemotron_lead)

    refs, hyps = [], []
    seconds = 0.0
    empty_total = 0
    started = time.time()
    for n, (path, start, end, text) in enumerate(items, 1):
        full = path if os.path.isabs(path) else os.path.join(base, path)
        audio = load_audio(full, start, end)
        seconds += len(audio) / SAMPLE_RATE
        if args.no_vad:
            hyp = rec.transcribe(audio)
            if not hyp.strip() and len(audio) >= SAMPLE_RATE:
                empty_total += 1
        else:
            pre = PRE_ROLL[args.model] if args.pre_roll is None else args.pre_roll
            hyp, empty = run_clip(vad, rec, audio, pre)
            empty_total += empty
        refs.append(text)
        hyps.append(hyp)
        if args.verbose:
            print(f"[{n}] REF: {text}\n    HYP: {hyp}")
        elif n % 25 == 0:
            print(f"  {n}/{len(items)} clips…", file=sys.stderr)
    elapsed = time.time() - started

    out = jiwer.process_words(refs, hyps, reference_transform=NORMALIZE, hypothesis_transform=NORMALIZE)
    print(f"model={args.model} language={args.language} clips={len(items)} audio={seconds:.0f}s "
          f"elapsed={elapsed:.0f}s rtf={elapsed / max(seconds, 1e-9):.3f}")
    print(f"WER {100 * out.wer:.2f}%  (sub {out.substitutions}, del {out.deletions}, ins {out.insertions}, "
          f"hits {out.hits})  empty segments >= 1 s: {empty_total}")


if __name__ == "__main__":
    main()
