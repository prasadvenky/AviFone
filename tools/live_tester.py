#!/usr/bin/env python3
"""Live speech-to-text tester for the laptop: play anything (a YouTube video, a call, a file)
and watch the app's speech models transcribe it side by side.

    .venv/bin/python tools/live_tester.py

It captures what the computer is playing (the PulseAudio monitor of the default output) or a
microphone, runs the app's pipeline (Silero VAD with LocalTranscriber's settings, then each
ticked model with its own pre-roll, lead-in and segment limit, see eval_asr.py), and shows one
column per model with a timestamp, the decode time and the real-time factor for every
utterance. Paste the video's own transcript into the reference box and press "Score WER" to get
a word error rate per model.

Headless, for scripts and smoke tests (prints what the GUI would show):

    .venv/bin/python tools/live_tester.py --headless 30 --models nemotron-3.5-560ms whisper-small

Models are the ones staged under .gradle/speech-models/desktop/ (tools/README.md).
"""
import argparse
import os
import queue
import re
import signal
import subprocess
import sys
import threading
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_asr  # noqa: E402  (same folder; shares the pipeline with the WER harness)
from eval_asr import MAX_SEGMENT, MODELS, PRE_ROLL, SAMPLE_RATE, WINDOW, Recognizer, make_vad  # noqa: E402

DEFAULT_MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".gradle", "speech-models", "desktop")
LANGUAGES = ["en", "hi", "auto"]
HISTORY_SECONDS = 16          # ring of recent audio that pre-roll is cut from
MIN_SEGMENT_SAMPLES = SAMPLE_RATE // 10   # LocalTranscriber drops shorter VAD segments (Whisper crashes)
MODEL_FILES = {
    "moonshine-tiny": ["encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"],
    "nemotron-3.5-560ms": ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"],
    "whisper-small": ["encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"],
    "whisper-turbo": ["encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt"],
}


# ----------------------------------------------------------------------------- audio sources

def pulse_sources():
    """[(name, label)] from PulseAudio, monitors (what's playing) first."""
    try:
        out = subprocess.run(["pactl", "list", "short", "sources"], capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        return []
    monitors, mics = [], []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        name = parts[1]
        if name.endswith(".monitor"):
            monitors.append((name, f"What the computer plays  ({name[:-8]})"))
        else:
            mics.append((name, f"Microphone  ({name})"))
    return monitors + mics


def default_source():
    try:
        sink = subprocess.run(["pactl", "get-default-sink"], capture_output=True, text=True, check=True).stdout.strip()
        if sink:
            return sink + ".monitor"
    except (OSError, subprocess.CalledProcessError):
        pass
    srcs = pulse_sources()
    return srcs[0][0] if srcs else None


class PulseCapture:
    """Reads a PulseAudio source with `parec` as 16 kHz mono float32, in WINDOW-sized chunks."""

    def __init__(self, source):
        self.proc = subprocess.Popen(
            ["parec", "-d", source, "--format=float32le", f"--rate={SAMPLE_RATE}", "--channels=1",
             "--latency-msec=50"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)

    def read(self):
        want = WINDOW * 4
        buf = b""
        while len(buf) < want:
            chunk = self.proc.stdout.read(want - len(buf))
            if not chunk:
                return None
            buf += chunk
        return np.frombuffer(buf, dtype=np.float32)

    def error(self):
        if self.proc.poll() is None:
            return None
        return (self.proc.stderr.read() or b"").decode(errors="replace").strip() or f"parec exited {self.proc.returncode}"

    def close(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(2)
            except subprocess.TimeoutExpired:
                self.proc.kill()


class FileCapture:
    """Feeds a sound file through the pipeline at real-time speed (so the timeline looks live)."""

    def __init__(self, path, realtime=True):
        import soundfile as sf
        audio, rate = sf.read(path, dtype="float32", always_2d=True)
        audio = audio.mean(axis=1)
        if rate != SAMPLE_RATE:
            n = int(round(len(audio) * SAMPLE_RATE / rate))
            audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
        pad = (-len(audio)) % WINDOW
        self.audio = np.concatenate([audio, np.zeros(pad, dtype=np.float32)])
        self.pos = 0
        self.realtime = realtime
        self.t0 = time.monotonic()

    def read(self):
        if self.pos >= len(self.audio):
            return None
        if self.realtime:
            due = self.t0 + self.pos / SAMPLE_RATE
            now = time.monotonic()
            if due > now:
                time.sleep(due - now)
        chunk = self.audio[self.pos:self.pos + WINDOW]
        self.pos += WINDOW
        return chunk

    def error(self):
        return None

    def close(self):
        pass


# ----------------------------------------------------------------------------- pipeline

class Segment:
    __slots__ = ("start", "samples", "history", "wall")

    def __init__(self, start, samples, history, wall):
        self.start = start              # sample index of the VAD cut (since session start)
        self.samples = samples          # the utterance itself
        self.history = history          # up to 1 s of audio just before `start` (for pre-roll)
        self.wall = wall                # time.monotonic() when the VAD emitted it


class ModelWorker(threading.Thread):
    """Loads one model on its own thread and decodes the segments queued for it."""

    def __init__(self, model, model_dir, language, threads, events):
        super().__init__(daemon=True, name=model)
        self.model = model
        self.model_dir = model_dir
        self.language = language
        self.threads = threads
        self.events = events            # queue of dicts for the UI
        self.jobs = queue.Queue()
        self.stop_flag = threading.Event()
        self.rec = None
        self.decoded_seconds = 0.0
        self.decode_time = 0.0
        self.count = 0
        self.busy = False

    def idle(self):
        return self.jobs.empty() and not self.busy

    def run(self):
        try:
            t = time.monotonic()
            self.rec = Recognizer(self.model, self.model_dir, self.language, self.threads)
            self.events.put({"kind": "loaded", "model": self.model, "seconds": time.monotonic() - t})
        except Exception as e:  # noqa: BLE001
            self.events.put({"kind": "error", "model": self.model, "message": f"{type(e).__name__}: {e}"})
            return
        pre = int(PRE_ROLL[self.model] * SAMPLE_RATE)
        while not self.stop_flag.is_set():
            try:
                seg = self.jobs.get(timeout=0.2)
            except queue.Empty:
                continue
            self.busy = True
            samples = seg.samples
            if pre and len(seg.history):
                samples = np.concatenate([seg.history[-pre:], samples])
            t = time.monotonic()
            try:
                text = self.rec.transcribe(samples)
            except Exception as e:  # noqa: BLE001
                self.events.put({"kind": "error", "model": self.model, "message": f"{type(e).__name__}: {e}"})
                self.busy = False
                continue
            took = time.monotonic() - t
            self.busy = False
            dur = len(seg.samples) / SAMPLE_RATE
            self.decoded_seconds += dur
            self.decode_time += took
            self.count += 1
            self.events.put({
                "kind": "text", "model": self.model, "start": seg.start / SAMPLE_RATE, "duration": dur,
                "text": text, "decode": took, "latency": time.monotonic() - seg.wall, "queued": self.jobs.qsize(),
                "avg_rtf": self.decode_time / max(self.decoded_seconds, 1e-9), "count": self.count,
            })

    def stop(self):
        self.stop_flag.set()


class Session:
    """Capture thread: reads audio, runs the VAD, fans segments out to the model workers."""

    def __init__(self, capture, model_dir, events):
        self.capture = capture
        self.events = events
        self.vad = make_vad(model_dir)
        self.workers = {}
        self.history = np.zeros(HISTORY_SECONDS * SAMPLE_RATE, dtype=np.float32)
        self.fed = 0
        self.stop_flag = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True, name="capture")
        self.segments = 0
        self.started = time.monotonic()

    def add_worker(self, worker):
        self.workers[worker.model] = worker

    def remove_worker(self, model):
        w = self.workers.pop(model, None)
        if w:
            w.stop()

    def start(self):
        self.thread.start()

    def wait_idle(self, timeout):
        """Blocks until every worker has decoded what was queued (or timeout seconds pass)."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and not all(w.idle() for w in self.workers.values()):
            time.sleep(0.1)

    def stop(self):
        """Stops capture, then the workers. Joins them: a process that exits while an ONNX session
        is mid-decode aborts with "terminate called without an active exception"."""
        self.stop_flag.set()
        self.thread.join(5)
        self.capture.close()
        for w in list(self.workers.values()):
            w.stop()
        for w in list(self.workers.values()):
            w.join(120)

    def _run(self):
        level_at = 0.0
        while not self.stop_flag.is_set():
            chunk = self.capture.read()
            if chunk is None:
                self.events.put({"kind": "ended", "message": self.capture.error()})
                break
            n = len(chunk)
            self.history = np.roll(self.history, -n)
            self.history[-n:] = chunk
            self.fed += n
            self.vad.accept_waveform(chunk)
            self._drain()
            now = time.monotonic()
            if now - level_at >= 0.1:
                level_at = now
                rms = float(np.sqrt(np.mean(chunk * chunk))) if n else 0.0
                self.events.put({"kind": "level", "db": 20 * np.log10(max(rms, 1e-6)),
                                 "seconds": self.fed / SAMPLE_RATE})
        try:
            self.vad.flush()
            self._drain()
        except Exception:  # noqa: BLE001
            pass

    def _drain(self):
        while not self.vad.empty():
            seg = self.vad.front
            samples = np.array(seg.samples, dtype=np.float32)   # copy before pop()
            start = seg.start
            self.vad.pop()
            if len(samples) < MIN_SEGMENT_SAMPLES:
                continue
            # the history ring ends at `self.fed`; pre-roll needs the audio just before `start`
            behind = self.fed - start
            before = self.history[:len(self.history) - behind] if behind < len(self.history) else self.history[:0]
            self.segments += 1
            segment = Segment(start, samples, before[-SAMPLE_RATE:].copy(), time.monotonic())
            for w in self.workers.values():
                w.jobs.put(segment)
            self.events.put({"kind": "segment", "start": start / SAMPLE_RATE, "duration": len(samples) / SAMPLE_RATE,
                             "count": self.segments})


def staged_models(model_dir):
    """Model id -> True if every file is present under model_dir/<id>/."""
    return {m: all(os.path.exists(os.path.join(model_dir, m, f)) for f in MODEL_FILES[m]) for m in MODELS}


def wer(reference, hypothesis):
    import jiwer
    if not reference.strip() or not hypothesis.strip():
        return None
    out = jiwer.process_words(reference, hypothesis, reference_transform=eval_asr.NORMALIZE,
                              hypothesis_transform=eval_asr.NORMALIZE)
    return out


# ----------------------------------------------------------------------------- GUI

def run_gui(args):
    import tkinter as tk
    from tkinter import filedialog, messagebox, scrolledtext, ttk

    available = staged_models(args.model_dir)
    sources = pulse_sources()
    events = queue.Queue()
    state = {"session": None, "transcripts": {m: [] for m in MODELS}, "loaded_language": {}}

    root = tk.Tk()
    root.title("AviFone speech model tester")
    root.geometry("1400x800")
    root.minsize(900, 500)

    # --- top bar
    top = ttk.Frame(root, padding=8)
    top.pack(fill="x")
    ttk.Label(top, text="Listen to:").pack(side="left")
    source_labels = [label for _, label in sources] or ["(no PulseAudio sources found)"]
    source_var = tk.StringVar()
    default = default_source()
    for name, label in sources:
        if name == (args.source or default):
            source_var.set(label)
    if not source_var.get():
        source_var.set(source_labels[0])
    source_box = ttk.Combobox(top, textvariable=source_var, values=source_labels, state="readonly", width=48)
    source_box.pack(side="left", padx=(4, 12))

    ttk.Label(top, text="Language:").pack(side="left")
    lang_var = tk.StringVar(value=args.language)
    ttk.Combobox(top, textvariable=lang_var, values=LANGUAGES, state="readonly", width=6).pack(side="left", padx=(4, 12))

    start_btn = ttk.Button(top, text="Start")
    start_btn.pack(side="left")
    file_btn = ttk.Button(top, text="Play file…")
    file_btn.pack(side="left", padx=(6, 12))

    ttk.Label(top, text="Level:").pack(side="left")
    level = ttk.Progressbar(top, length=160, maximum=60, value=0)
    level.pack(side="left", padx=(4, 12))
    status_var = tk.StringVar(value="Stopped. Tick the models to compare, play something, press Start.")
    ttk.Label(top, textvariable=status_var).pack(side="left")

    # --- model columns
    columns = ttk.Frame(root, padding=(8, 0, 8, 0))
    columns.pack(fill="both", expand=True)
    checks, frames, texts, heads, stats = {}, {}, {}, {}, {}

    def layout_columns():
        for m in MODELS:
            frames[m].grid_forget()
        shown = [m for m in MODELS if checks[m].get()]
        for col in range(len(MODELS)):
            columns.columnconfigure(col, weight=1 if col < len(shown) else 0, uniform="model")
        for col, m in enumerate(shown):
            frames[m].grid(row=0, column=col, sticky="nsew", padx=4)
        columns.rowconfigure(0, weight=1)

    def on_check(m):
        layout_columns()
        s = state["session"]
        if s is None:
            return
        if checks[m].get() and m not in s.workers:
            start_worker(s, m)
        elif not checks[m].get() and m in s.workers:
            s.remove_worker(m)
            heads[m].set(f"{m}  (stopped)")

    for m in MODELS:
        checks[m] = tk.BooleanVar(value=available[m] and m in args.models)
        f = ttk.Frame(columns)
        frames[m] = f
        heads[m] = tk.StringVar(value=m if available[m] else f"{m}  (not staged, see tools/README.md)")
        cb = ttk.Checkbutton(top, text=m, variable=checks[m], command=lambda m=m: on_check(m),
                             state="normal" if available[m] else "disabled")
        cb.pack(side="right", padx=4)
        ttk.Label(f, textvariable=heads[m], font=("TkDefaultFont", 10, "bold"), wraplength=320).pack(anchor="w")
        stats[m] = tk.StringVar(value="")
        ttk.Label(f, textvariable=stats[m], foreground="#555", wraplength=320).pack(anchor="w")
        t = scrolledtext.ScrolledText(f, wrap="word", width=20, height=20, font=("TkDefaultFont", 11))
        t.tag_configure("meta", foreground="#888", font=("TkDefaultFont", 9))
        t.tag_configure("warn", foreground="#b00")
        t.pack(fill="both", expand=True)
        texts[m] = t
    layout_columns()

    # --- reference / scoring
    bottom = ttk.Frame(root, padding=8)
    bottom.pack(fill="x")
    ttk.Label(bottom, text="Reference text (paste the video's transcript, then Score WER):").pack(anchor="w")
    ref = scrolledtext.ScrolledText(bottom, wrap="word", height=4, font=("TkDefaultFont", 10))
    ref.pack(fill="x")
    btns = ttk.Frame(bottom)
    btns.pack(fill="x", pady=(4, 0))

    def score():
        reference = ref.get("1.0", "end")
        for m in MODELS:
            hyp = " ".join(t for _, _, t, *_ in state["transcripts"][m])
            out = wer(reference, hyp)
            if out is None:
                continue
            heads[m].set(f"{m}   WER {100 * out.wer:.1f} %  (sub {out.substitutions}, del {out.deletions}, "
                         f"ins {out.insertions}, {len(out.references[0])} ref words)")

    def clear():
        for m in MODELS:
            texts[m].delete("1.0", "end")
            state["transcripts"][m].clear()
            heads[m].set(m if available[m] else heads[m].get())
            stats[m].set("")

    def save():
        path = filedialog.asksaveasfilename(defaultextension=".txt", filetypes=[("Text", "*.txt")],
                                            initialfile=time.strftime("transcripts-%Y%m%d-%H%M%S.txt"))
        if not path:
            return
        with open(path, "w", encoding="utf-8") as f:
            for m in MODELS:
                if not state["transcripts"][m]:
                    continue
                f.write(f"== {m}\n")
                for start, dur, text, took in state["transcripts"][m]:
                    f.write(f"[{start:7.1f}s +{dur:4.1f}s  decode {took:.2f}s] {text}\n")
                f.write("\n")
            r = ref.get("1.0", "end").strip()
            if r:
                f.write("== reference\n" + r + "\n")

    ttk.Button(btns, text="Score WER", command=score).pack(side="left")
    ttk.Button(btns, text="Clear", command=clear).pack(side="left", padx=6)
    ttk.Button(btns, text="Save transcripts…", command=save).pack(side="left")

    # --- session control
    def start_worker(session, m):
        w = ModelWorker(m, args.model_dir, lang_var.get(), args.threads, events)
        session.add_worker(w)
        heads[m].set(f"{m}  (loading…)")
        w.start()

    def start_capture(capture, label):
        s = Session(capture, args.model_dir, events)
        state["session"] = s
        for m in MODELS:
            if checks[m].get():
                start_worker(s, m)
        s.start()
        start_btn.config(text="Stop")
        file_btn.config(state="disabled")
        source_box.config(state="disabled")
        status_var.set(f"Listening to {label}")

    def stop_capture(then=None):
        s = state["session"]
        if s is None:
            if then:
                then()
            return
        state["session"] = None
        status_var.set("Stopping… (finishing the utterances already queued)")
        start_btn.config(state="disabled")

        def finish():
            s.stop()
            root.after(0, done)

        def done():
            start_btn.config(text="Start", state="normal")
            file_btn.config(state="normal")
            source_box.config(state="readonly")
            status_var.set("Stopped.")
            if then:
                then()

        threading.Thread(target=finish, daemon=True).start()

    def on_close():
        stop_capture(then=root.destroy)

    def toggle():
        if state["session"] is not None:
            stop_capture()
            return
        if not any(checks[m].get() for m in MODELS):
            messagebox.showinfo("No model", "Tick at least one model first.")
            return
        name = next((n for n, label in sources if label == source_var.get()), None)
        if name is None:
            messagebox.showerror("No source", "No PulseAudio source to capture. Is PulseAudio/PipeWire running?")
            return
        try:
            cap = PulseCapture(name)
        except OSError as e:
            messagebox.showerror("parec", f"Couldn't start parec: {e}")
            return
        start_capture(cap, name)

    def play_file():
        if state["session"] is not None:
            return
        if not any(checks[m].get() for m in MODELS):
            messagebox.showinfo("No model", "Tick at least one model first.")
            return
        path = filedialog.askopenfilename(filetypes=[("Audio", "*.wav *.flac *.ogg *.mp3 *.m4a"), ("All", "*")])
        if not path:
            return
        try:
            cap = FileCapture(path)
        except Exception as e:  # noqa: BLE001
            messagebox.showerror("File", f"Couldn't read {path}: {e}")
            return
        start_capture(cap, os.path.basename(path))

    start_btn.config(command=toggle)
    file_btn.config(command=play_file)

    # --- event pump
    def fmt_time(seconds):
        return f"{int(seconds // 60)}:{seconds % 60:04.1f}"

    def pump():
        try:
            while True:
                e = events.get_nowait()
                k = e["kind"]
                if k == "level":
                    level["value"] = max(0.0, min(60.0, e["db"] + 60))
                    if state["session"] is not None:
                        status_var.set(f"Listening · {fmt_time(e['seconds'])} · {state['session'].segments} utterances")
                elif k == "loaded":
                    m = e["model"]
                    heads[m].set(m)
                    stats[m].set(f"loaded in {e['seconds']:.1f} s")
                elif k == "text":
                    m = e["model"]
                    t = texts[m]
                    t.insert("end", f"[{fmt_time(e['start'])}  {e['duration']:.1f} s · decoded in {e['decode']:.2f} s · "
                                    f"RTF {e['decode'] / max(e['duration'], 1e-9):.2f}]\n", "meta")
                    if e["text"]:
                        t.insert("end", e["text"] + "\n\n")
                    else:
                        t.insert("end", "(nothing recognised)\n\n", "warn")
                    t.see("end")
                    state["transcripts"][m].append((e["start"], e["duration"], e["text"], e["decode"]))
                    stats[m].set(f"{e['count']} utterances · avg RTF {e['avg_rtf']:.2f} · "
                                 f"last result {e['latency']:.1f} s after speech ended · {e['queued']} waiting")
                elif k == "error":
                    m = e["model"]
                    texts[m].insert("end", f"error: {e['message']}\n", "warn")
                    heads[m].set(f"{m}  (error)")
                elif k == "ended":
                    msg = e.get("message")
                    if state["session"] is not None:
                        stop_capture()
                    status_var.set("Audio ended." + (f" {msg}" if msg else ""))
        except queue.Empty:
            pass
        root.after(80, pump)

    root.after(80, pump)
    closing = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: closing.set())
    signal.signal(signal.SIGTERM, lambda *_: closing.set())

    def watch_signals():
        if closing.is_set():
            on_close()
        else:
            root.after(200, watch_signals)

    root.after(200, watch_signals)
    if args.start:
        root.after(200, toggle)
    root.protocol("WM_DELETE_WINDOW", on_close)
    root.mainloop()


# ----------------------------------------------------------------------------- headless

def run_headless(args):
    available = staged_models(args.model_dir)
    models = [m for m in args.models if available[m]]
    missing = [m for m in args.models if not available[m]]
    if missing:
        print(f"not staged, skipping: {', '.join(missing)}", file=sys.stderr)
    if not models:
        sys.exit("no staged models to run")
    events = queue.Queue()
    if args.file:
        cap = FileCapture(args.file, realtime=not args.fast)
        label = args.file
    else:
        label = args.source or default_source()
        if not label:
            sys.exit("no PulseAudio source found; use --source or --file")
        cap = PulseCapture(label)
    s = Session(cap, args.model_dir, events)
    for m in models:
        w = ModelWorker(m, args.model_dir, args.language, args.threads, events)
        s.add_worker(w)
        w.start()
    s.start()
    print(f"listening to {label} for {args.headless:.0f} s with {', '.join(models)}", file=sys.stderr)
    deadline = time.monotonic() + args.headless
    ended = False
    transcripts = {m: [] for m in models}
    while time.monotonic() < deadline and not ended:
        try:
            e = events.get(timeout=0.2)
        except queue.Empty:
            continue
        if e["kind"] == "text":
            transcripts[e["model"]].append(e["text"])
            print(f"{e['model']:<20} [{e['start']:6.1f}s {e['duration']:4.1f}s decode {e['decode']:.2f}s] {e['text']}")
        elif e["kind"] == "loaded":
            print(f"{e['model']:<20} loaded in {e['seconds']:.1f} s", file=sys.stderr)
        elif e["kind"] == "error":
            print(f"{e['model']:<20} error: {e['message']}", file=sys.stderr)
        elif e["kind"] == "ended":
            ended = True
    # stop capturing, let the workers finish what is queued, then collect the stragglers
    s.stop_flag.set()
    s.thread.join(5)
    s.capture.close()
    s.wait_idle(600)
    while True:
        try:
            e = events.get_nowait()
        except queue.Empty:
            break
        if e["kind"] == "text":
            transcripts[e["model"]].append(e["text"])
            print(f"{e['model']:<20} [{e['start']:6.1f}s {e['duration']:4.1f}s decode {e['decode']:.2f}s] {e['text']}")
    s.stop()
    if args.reference:
        reference = open(args.reference, encoding="utf-8").read()
        for m in models:
            out = wer(reference, " ".join(transcripts[m]))
            if out:
                print(f"{m:<20} WER {100 * out.wer:.1f}%  (sub {out.substitutions}, del {out.deletions}, ins {out.insertions})")
    for m, w in s.workers.items():
        if w.count:
            print(f"{m:<20} {w.count} utterances, avg RTF {w.decode_time / w.decoded_seconds:.2f}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", default=DEFAULT_MODEL_DIR)
    ap.add_argument("--models", nargs="+", default=["nemotron-3.5-560ms", "whisper-small"], choices=MODELS,
                    help="models ticked at start (GUI) or run (headless)")
    ap.add_argument("--language", default="en", choices=LANGUAGES)
    ap.add_argument("--threads", type=int, default=4, help="ONNX threads per model")
    ap.add_argument("--headless", type=float, metavar="SECONDS", help="no window; print results for this long")
    ap.add_argument("--source", help="PulseAudio source name (default: the default output's monitor)")
    ap.add_argument("--start", action="store_true", help="GUI: start listening as soon as the window opens")
    ap.add_argument("--file", help="sound file to feed instead of live audio (headless)")
    ap.add_argument("--fast", action="store_true", help="with --file: don't pace at real time")
    ap.add_argument("--reference", help="text file to score the headless run against")
    args = ap.parse_args()
    if args.headless:
        run_headless(args)
    else:
        run_gui(args)


if __name__ == "__main__":
    main()
