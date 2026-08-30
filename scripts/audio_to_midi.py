"""Selectable audio-to-MIDI backends for the Sky conversion pipeline.

The module deliberately keeps heavy audio libraries lazy.  The core GUI and
MIDI-to-Sky converter can therefore start in the normal project environment;
only the selected audio backend needs its optional runtime.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Iterable, Sequence

import mido

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.music_events import NoteEvent, midi_event_sort_key, sort_note_events
from scripts.mp3_to_midi import transcribe_audio as transcribe_basic_pitch


AUDIO_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg"}
AUDIO_BACKENDS = ("tsumugi", "basic_pitch", "pyin", "torchcrepe")
DEFAULT_TSUMUGI_MODEL = "guitar_v1_5"
DEFAULT_TSUMUGI_MERGE_ONSET_MS = 50.0
DEFAULT_AUDIO_SAMPLE_RATE = 22_050
DEFAULT_AUDIO_HOP_LENGTH = 256
DEFAULT_MIDI_BPM = 120.0
DEFAULT_MIDI_PPQ = 480


class AudioToMidiError(RuntimeError):
    """Raised when an audio backend cannot produce a MIDI file."""


class BackendUnavailable(AudioToMidiError):
    """Raised when an optional backend is not installed or configured."""


def resolve_backend(name: str) -> str:
    """Normalize and validate a backend name for CLI and GUI callers."""

    normalized = str(name).strip().lower().replace("-", "_")
    if normalized not in AUDIO_BACKENDS:
        choices = "、".join(AUDIO_BACKENDS)
        raise ValueError(f"支持的 MIDI 后端：{choices}；收到 {name!r}")
    return normalized


def _command_text(command: Sequence[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline(list(command))
    return shlex.join(command)


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _write_process_log(
    path: Path,
    *,
    command: Sequence[str],
    result: subprocess.CompletedProcess[str] | None = None,
    error: str | None = None,
    elapsed_seconds: float | None = None,
) -> None:
    lines = [f"command: {_command_text(command)}"]
    if elapsed_seconds is not None:
        lines.append(f"elapsed_seconds: {elapsed_seconds:.2f}")
    if result is not None:
        lines.extend(
            [
                f"returncode: {result.returncode}",
                "--- stdout ---",
                result.stdout or "",
                "--- stderr ---",
                result.stderr or "",
            ]
        )
    if error:
        lines.extend(["--- adapter error ---", error])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _run_logged(
    command: Sequence[str],
    *,
    log_path: Path,
    cwd: Path | None = None,
    timeout_seconds: int | None = 3_600,
) -> subprocess.CompletedProcess[str]:
    started = time.time()
    environment = os.environ.copy()
    environment["PYTHONIOENCODING"] = "utf-8"
    try:
        result = subprocess.run(
            list(command),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=environment,
            cwd=str(cwd) if cwd is not None else None,
            timeout=timeout_seconds,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        _write_process_log(
            log_path,
            command=command,
            error=f"命令超时（{timeout_seconds} 秒）：{exc}",
            elapsed_seconds=time.time() - started,
        )
        raise AudioToMidiError(f"音频后端命令超时：{_command_text(command)}") from exc
    except OSError as exc:
        _write_process_log(
            log_path,
            command=command,
            error=str(exc),
            elapsed_seconds=time.time() - started,
        )
        raise AudioToMidiError(
            f"音频后端启动失败：{exc}；详见 {log_path}；命令：{_command_text(command)}"
        ) from exc
    _write_process_log(
        log_path,
        command=command,
        result=result,
        elapsed_seconds=time.time() - started,
    )
    return result


def _validate_audio(audio_path: str | Path) -> Path:
    audio = Path(audio_path).expanduser()
    if not audio.is_file():
        raise FileNotFoundError(f"找不到音频文件：{audio}")
    if audio.suffix.lower() not in AUDIO_EXTENSIONS:
        choices = ", ".join(sorted(AUDIO_EXTENSIONS))
        raise ValueError(f"不支持的音频格式：{audio.suffix or '无扩展名'}；支持 {choices}")
    return audio.resolve()


def _find_project_audio_python(project_root: Path) -> Path | None:
    candidates = (
        project_root / ".audio-venv" / "Scripts" / "python.exe",
        project_root / ".audio-venv" / "bin" / "python",
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return None


def find_tsumugi_python(repo: str | Path, project_root: str | Path) -> Path | None:
    """Find the interpreter for Tsumugi before falling back to the project audio env.

    Tsumugi owns its dependency lock file, so an environment created inside
    the submodule is the most reliable choice.  The project-level audio
    environment remains a documented fallback for existing installations.
    """

    repo_path = Path(repo).expanduser()
    root = Path(project_root).expanduser()
    candidates = (
        repo_path / ".venv" / "Scripts" / "python.exe",
        repo_path / ".venv" / "bin" / "python",
        root / ".audio-venv" / "Scripts" / "python.exe",
        root / ".audio-venv" / "bin" / "python",
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return None


def _find_tsumugi_repo(project_root: Path) -> Path:
    candidate = project_root / "vendor" / "tsumugi"
    if not (candidate / "infer.py").is_file():
        raise BackendUnavailable(
            "未找到 Tsumugi 子模块：请执行 git submodule update --init --recursive，"
            "确保 vendor/tsumugi/infer.py 存在。"
        )
    return candidate.resolve()


def _find_tsumugi_checkpoint(repo: Path, model: str) -> Path:
    checkpoint = repo / "checkpoints" / f"best_model_{model}.pth"
    if not checkpoint.is_file():
        raise BackendUnavailable(
            f"未找到 Tsumugi 模型权重：{checkpoint}；"
            "请按 docs/RUNBOOK.md 下载对应 checkpoint。"
        )
    return checkpoint.resolve()


def build_tsumugi_command(
    *,
    python: str | Path,
    repo: str | Path,
    audio: str | Path,
    output_midi: str | Path,
    checkpoint: str | Path,
    model: str = DEFAULT_TSUMUGI_MODEL,
    device: str = "auto",
    merge_onset_ms: float = DEFAULT_TSUMUGI_MERGE_ONSET_MS,
) -> list[str]:
    """Build the explicit Tsumugi inference command used by the adapter."""

    repo_path = Path(repo)
    return [
        str(Path(python)),
        str(repo_path / "infer.py"),
        "--audio",
        str(Path(audio).resolve()),
        "--output-midi",
        str(Path(output_midi).resolve()),
        "--checkpoint",
        str(Path(checkpoint).resolve()),
        "--type",
        str(model),
        "--device",
        str(device),
        "--merge-onset-ms",
        str(float(merge_onset_ms)),
    ]


def build_python_backend_command(
    *,
    python: str | Path,
    script: str | Path,
    audio: str | Path,
    output_midi: str | Path,
    project_root: str | Path,
    backend: str,
) -> list[str]:
    """Build a worker command for optional F0 backends in ``.audio-venv``."""

    return [
        str(Path(python)),
        str(Path(script)),
        str(Path(audio).resolve()),
        "--out-midi",
        str(Path(output_midi).resolve()),
        "--backend",
        str(backend),
        "--project-root",
        str(Path(project_root).resolve()),
    ]


def transcribe_tsumugi(
    audio_path: str | Path,
    output_midi: str | Path,
    *,
    project_root: str | Path,
    model: str = DEFAULT_TSUMUGI_MODEL,
    device: str = "auto",
    merge_onset_ms: float = DEFAULT_TSUMUGI_MERGE_ONSET_MS,
    timeout_seconds: int | None = 3_600,
) -> Path:
    """Run the local Tsumugi checkpoint and return its generated MIDI."""

    audio = _validate_audio(audio_path)
    root = Path(project_root).resolve()
    repo = _find_tsumugi_repo(root)
    checkpoint = _find_tsumugi_checkpoint(repo, model)
    python = find_tsumugi_python(repo, root) or Path(sys.executable).resolve()
    output = Path(output_midi).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    command = build_tsumugi_command(
        python=python,
        repo=repo,
        audio=audio,
        output_midi=output,
        checkpoint=checkpoint,
        model=model,
        device=device,
        merge_onset_ms=merge_onset_ms,
    )
    log_path = output.parent / f"{output.stem}.tsumugi.log"
    result = _run_logged(command, log_path=log_path, cwd=repo, timeout_seconds=timeout_seconds)
    if result.returncode != 0 or not output.is_file():
        tail = "\n".join((result.stderr or result.stdout or "").splitlines()[-20:])
        raise AudioToMidiError(
            f"Tsumugi 转 MIDI 失败（退出码 {result.returncode}）；详见 {log_path}\n{tail}"
        )
    return output.resolve()


def _load_audio(path: Path, *, sample_rate: int = DEFAULT_AUDIO_SAMPLE_RATE) -> tuple[Any, int]:
    try:
        import librosa

        return librosa.load(str(path), sr=sample_rate, mono=True)
    except ImportError as exc:
        raise BackendUnavailable(
            "当前音频环境缺少 librosa；请安装 requirements-audio-optional.txt。"
        ) from exc
    except Exception as exc:
        raise AudioToMidiError(f"加载音频失败：{path}：{exc}") from exc


def _track_to_note_events(
    f0_hz: Sequence[float],
    confidence: Sequence[float] | None,
    *,
    frame_times_ms: Sequence[float],
    frame_step_ms: float,
    min_duration_ms: int,
    confidence_threshold: float,
    source: str,
) -> tuple[NoteEvent, ...]:
    if len(f0_hz) != len(frame_times_ms):
        raise ValueError("f0 和 frame_times_ms 长度不一致")
    if confidence is not None and len(confidence) != len(f0_hz):
        raise ValueError("confidence 和 f0 长度不一致")

    notes: list[NoteEvent] = []
    active_pitch: int | None = None
    active_start: int | None = None
    active_confidences: list[float] = []

    def close(end_ms: float) -> None:
        nonlocal active_pitch, active_start, active_confidences
        if active_pitch is None or active_start is None:
            return
        end = max(active_start + 1, int(round(end_ms)))
        if end - active_start >= min_duration_ms:
            confidence_value = (
                sum(active_confidences) / len(active_confidences)
                if active_confidences
                else None
            )
            notes.append(
                NoteEvent(
                    start_ms=active_start,
                    duration_ms=end - active_start,
                    pitch=active_pitch,
                    velocity=80,
                    confidence=confidence_value,
                    source=source,
                )
            )
        active_pitch = None
        active_start = None
        active_confidences = []

    for index, value in enumerate(f0_hz):
        try:
            frequency = float(value)
        except (TypeError, ValueError):
            frequency = float("nan")
        score = 1.0 if confidence is None else float(confidence[index])
        valid = (
            math.isfinite(frequency)
            and frequency > 0
            and math.isfinite(score)
            and score >= confidence_threshold
        )
        pitch = (
            int(round(69.0 + 12.0 * math.log2(frequency / 440.0)))
            if valid
            else None
        )
        now_ms = float(frame_times_ms[index])
        if pitch is None:
            close(now_ms)
            continue
        if active_pitch is None:
            active_pitch = max(0, min(127, pitch))
            active_start = max(0, int(round(now_ms)))
        elif pitch != active_pitch:
            close(now_ms)
            active_pitch = max(0, min(127, pitch))
            active_start = max(0, int(round(now_ms)))
        active_confidences.append(score)

    final_time = float(frame_times_ms[-1]) + frame_step_ms if frame_times_ms else 0.0
    close(final_time)
    return sort_note_events(notes)


def transcribe_pyin(audio_path: str | Path) -> tuple[NoteEvent, ...]:
    """Extract one monophonic line with librosa's probabilistic YIN."""

    audio = _validate_audio(audio_path)
    y, sr = _load_audio(audio)
    try:
        import librosa
        import numpy as np

        hop_length = DEFAULT_AUDIO_HOP_LENGTH
        f0, voiced_flag, voiced_prob = librosa.pyin(
            y,
            fmin=librosa.note_to_hz("A1"),
            fmax=librosa.note_to_hz("A6"),
            sr=sr,
            frame_length=2_048,
            hop_length=hop_length,
            fill_na=np.nan,
        )
        confidence = np.where(voiced_flag, voiced_prob, 0.0)
        times = librosa.times_like(f0, sr=sr, hop_length=hop_length) * 1_000.0
        return _track_to_note_events(
            f0,
            confidence,
            frame_times_ms=times,
            frame_step_ms=hop_length / sr * 1_000.0,
            min_duration_ms=45,
            confidence_threshold=0.55,
            source="librosa.pyin",
        )
    except ImportError as exc:
        raise BackendUnavailable(
            "当前音频环境缺少 librosa/numpy；请安装 requirements-audio-optional.txt。"
        ) from exc
    except Exception as exc:
        raise AudioToMidiError(f"pYIN 识别失败：{audio}：{exc}") from exc


def transcribe_torchcrepe(audio_path: str | Path) -> tuple[NoteEvent, ...]:
    """Extract one monophonic line with the lightweight torchcrepe model."""

    audio = _validate_audio(audio_path)
    y, sr = _load_audio(audio, sample_rate=16_000)
    try:
        import numpy as np
        import torch
        import torchcrepe

        hop_length = int(sr / 200.0)
        audio_tensor = torch.as_tensor(y, dtype=torch.float32).unsqueeze(0)
        device = "cuda:0" if torch.cuda.is_available() else "cpu"
        pitch, periodicity = torchcrepe.predict(
            audio_tensor,
            sr,
            hop_length,
            fmin=55,
            fmax=1760,
            model="tiny",
            batch_size=2_048,
            device=device,
            return_periodicity=True,
        )
        pitch_array = pitch.detach().cpu().numpy().reshape(-1)
        confidence_array = periodicity.detach().cpu().numpy().reshape(-1)
        times = np.arange(len(pitch_array), dtype=float) * hop_length / sr * 1_000.0
        return _track_to_note_events(
            pitch_array,
            confidence_array,
            frame_times_ms=times,
            frame_step_ms=hop_length / sr * 1_000.0,
            min_duration_ms=45,
            confidence_threshold=0.35,
            source="torchcrepe-tiny",
        )
    except ImportError as exc:
        raise BackendUnavailable(
            "当前音频环境缺少 torchcrepe；请安装 requirements-audio-optional.txt。"
        ) from exc
    except Exception as exc:
        raise AudioToMidiError(f"torchcrepe 识别失败：{audio}：{exc}") from exc


def _ms_to_tick(ms: float, *, bpm: float, ticks_per_beat: int) -> int:
    return max(0, int(round(float(ms) * float(bpm) * ticks_per_beat / 60_000.0)))


def write_note_events_midi(
    events: Iterable[NoteEvent],
    output_path: str | Path,
    *,
    bpm: float = DEFAULT_MIDI_BPM,
    title: str = "Audio transcription",
    ticks_per_beat: int = DEFAULT_MIDI_PPQ,
) -> Path:
    """Write millisecond note events to a readable type-1 MIDI file."""

    if not math.isfinite(float(bpm)) or float(bpm) <= 0:
        raise ValueError("bpm 必须是正数")
    if ticks_per_beat <= 0:
        raise ValueError("ticks_per_beat 必须是正数")
    ordered = sort_note_events(events)
    output = Path(output_path).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)

    midi = mido.MidiFile(type=1, ticks_per_beat=int(ticks_per_beat))
    track = mido.MidiTrack()
    midi.tracks.append(track)
    safe_title = str(title)[:127].encode("latin-1", errors="replace").decode("latin-1")
    track.append(mido.MetaMessage("track_name", name=safe_title, time=0))
    track.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(float(bpm)), time=0))
    track.append(mido.MetaMessage("time_signature", numerator=4, denominator=4, time=0))

    absolute: list[tuple[int, str, int, int]] = []
    for event in ordered:
        start = _ms_to_tick(event.start_ms, bpm=bpm, ticks_per_beat=ticks_per_beat)
        end = _ms_to_tick(
            event.start_ms + event.duration_ms,
            bpm=bpm,
            ticks_per_beat=ticks_per_beat,
        )
        end = max(start + 1, end)
        absolute.append((start, "note_on", event.pitch, event.velocity))
        absolute.append((end, "note_off", event.pitch, 0))

    absolute.sort(key=lambda item: midi_event_sort_key((item[0], item[1], item[2])))
    previous_tick = 0
    for tick, event_type, pitch, velocity in absolute:
        track.append(
            mido.Message(
                event_type,
                channel=0,
                note=int(pitch),
                velocity=int(velocity),
                time=int(tick - previous_tick),
            )
        )
        previous_tick = tick
    track.append(mido.MetaMessage("end_of_track", time=0))
    midi.save(output)
    return output.resolve()


def _event_to_dict(event: NoteEvent) -> dict[str, Any]:
    return {
        "start_ms": event.start_ms,
        "duration_ms": event.duration_ms,
        "pitch": event.pitch,
        "velocity": event.velocity,
        "confidence": event.confidence,
        "source": event.source,
    }


def _backend_available_in_current_python(backend: str) -> bool:
    required = {
        "pyin": ("librosa", "numpy"),
        "torchcrepe": ("librosa", "numpy", "torch", "torchcrepe"),
    }[backend]
    return all(importlib.util.find_spec(name) is not None for name in required)


def _delegate_optional_backend(
    *,
    backend: str,
    audio: Path,
    target: Path,
    project_root: Path,
) -> Path | None:
    """Run an optional backend in the project audio environment when needed."""

    if _backend_available_in_current_python(backend):
        return None
    python = _find_project_audio_python(project_root)
    if python is None or python.resolve() == Path(sys.executable).resolve():
        return None
    command = build_python_backend_command(
        python=python,
        script=Path(__file__).resolve(),
        audio=audio,
        output_midi=target,
        project_root=project_root,
        backend=backend,
    )
    log_path = target.parent / f"{target.stem}.{backend}.delegate.log"
    result = _run_logged(command, log_path=log_path, cwd=project_root)
    if result.returncode != 0 or not target.is_file():
        tail = "\n".join((result.stderr or result.stdout or "").splitlines()[-20:])
        raise AudioToMidiError(
            f"{backend} 音频环境委托失败（退出码 {result.returncode}）；"
            f"详见 {log_path}\n{tail}"
        )
    return target.resolve()


def _write_event_sidecar(path: Path, events: Sequence[NoteEvent], backend: str) -> None:
    _write_json(
        path.with_suffix(".notes.json"),
        {
            "format": "audio-note-events-v1",
            "backend": backend,
            "events": [_event_to_dict(event) for event in sort_note_events(events)],
        },
    )


def transcribe_audio(
    audio_path: str | Path,
    output_midi: str | Path,
    *,
    backend: str = "tsumugi",
    project_root: str | Path | None = None,
    model: str = DEFAULT_TSUMUGI_MODEL,
    device: str = "auto",
    merge_onset_ms: float = DEFAULT_TSUMUGI_MERGE_ONSET_MS,
    basic_pitch_executable: str | Path | None = None,
) -> Path:
    """Run one selected backend and return its MIDI path.

    ``output_midi`` may be a file path or an output directory.  Directory
    inputs receive a stable ``<backend>.mid`` filename.
    """

    selected = resolve_backend(backend)
    audio = _validate_audio(audio_path)
    root = (
        Path(project_root).expanduser().resolve()
        if project_root is not None
        else Path(__file__).resolve().parents[1]
    )
    target = Path(output_midi).expanduser()
    if target.suffix.lower() not in {".mid", ".midi"}:
        target = target / f"{selected}__{audio.stem}.mid"
    target.parent.mkdir(parents=True, exist_ok=True)

    if selected == "basic_pitch":
        result = transcribe_basic_pitch(
            audio,
            target.parent,
            executable=basic_pitch_executable,
            project_root=root,
        )
        if result.resolve() != target.resolve():
            shutil.copy2(result, target)
        return target.resolve()

    if selected == "tsumugi":
        return transcribe_tsumugi(
            audio,
            target,
            project_root=root,
            model=model,
            device=device,
            merge_onset_ms=merge_onset_ms,
        )

    if selected == "pyin":
        delegated = _delegate_optional_backend(
            backend=selected,
            audio=audio,
            target=target,
            project_root=root,
        )
        if delegated is not None:
            return delegated
        events = transcribe_pyin(audio)
    else:
        delegated = _delegate_optional_backend(
            backend=selected,
            audio=audio,
            target=target,
            project_root=root,
        )
        if delegated is not None:
            return delegated
        events = transcribe_torchcrepe(audio)
    result = write_note_events_midi(events, target, title=f"{selected}: {audio.stem}")
    _write_event_sidecar(result, events, selected)
    return result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="使用可选音频后端将音频转换为 MIDI。")
    parser.add_argument("audio", type=Path, help="输入 MP3/WAV/FLAC/M4A/OGG 文件")
    parser.add_argument("--out-midi", type=Path, required=True, help="输出 MIDI 文件或目录")
    parser.add_argument("--backend", choices=AUDIO_BACKENDS, default="tsumugi")
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--model", default=DEFAULT_TSUMUGI_MODEL, help="Tsumugi 模型，默认 guitar_v1_5")
    parser.add_argument("--device", default="auto", help="Tsumugi/torch 设备：auto、cpu、cuda、mps")
    parser.add_argument("--merge-onset-ms", type=float, default=DEFAULT_TSUMUGI_MERGE_ONSET_MS)
    args = parser.parse_args(argv)
    try:
        result = transcribe_audio(
            args.audio,
            args.out_midi,
            backend=args.backend,
            project_root=args.project_root,
            model=args.model,
            device=args.device,
            merge_onset_ms=args.merge_onset_ms,
        )
    except (OSError, AudioToMidiError, ValueError) as exc:
        parser.error(str(exc))
    print(f"已生成 MIDI：{result}")
    return 0


__all__ = [
    "AUDIO_BACKENDS",
    "AUDIO_EXTENSIONS",
    "AudioToMidiError",
    "BackendUnavailable",
    "build_tsumugi_command",
    "build_python_backend_command",
    "find_tsumugi_python",
    "resolve_backend",
    "transcribe_audio",
    "transcribe_pyin",
    "transcribe_torchcrepe",
    "transcribe_tsumugi",
    "write_note_events_midi",
]


if __name__ == "__main__":
    raise SystemExit(main())
