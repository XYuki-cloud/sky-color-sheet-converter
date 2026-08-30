"""Video/audio preparation and end-to-end conversion orchestration."""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.audio_to_midi import AUDIO_BACKENDS, AUDIO_EXTENSIONS, transcribe_audio
from scripts.midi_to_sky import convert_midi_file_to_sky


VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"}
DEFAULT_SAMPLE_RATE = 44_100
DEFAULT_DEMUCS_MODEL = "htdemucs_6s"
DEFAULT_DEMUCS_SHIFTS = 5


class MediaPipelineError(RuntimeError):
    """Raised when media preparation or an end-to-end stage fails."""


@dataclass(frozen=True)
class OutputLayout:
    root: Path
    audio: Path
    separation: Path
    midi: Path
    sky: Path


def detect_media_kind(source: str | Path) -> str:
    suffix = Path(source).suffix.lower()
    if suffix in VIDEO_EXTENSIONS:
        return "video"
    if suffix in AUDIO_EXTENSIONS:
        return "audio"
    choices = ", ".join(sorted(VIDEO_EXTENSIONS | AUDIO_EXTENSIONS))
    raise ValueError(f"不支持的媒体格式：{suffix or '无扩展名'}；支持 {choices}")


def output_layout(
    root: str | Path,
    song_stem: str,
    *,
    backend: str,
    input_label: str,
) -> OutputLayout:
    base = Path(root).expanduser()
    label = f"{backend}__{input_label}"
    return OutputLayout(
        root=base,
        audio=base / "audio",
        separation=base / "separation",
        midi=base / "midi" / f"{label}.mid",
        sky=base / "sky" / label,
    )


def build_ffmpeg_extract_command(
    *,
    ffmpeg: str | Path,
    source: str | Path,
    output_audio: str | Path,
    sample_rate: int = DEFAULT_SAMPLE_RATE,
) -> list[str]:
    return [
        str(ffmpeg),
        "-y",
        "-i",
        str(Path(source).resolve()),
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(int(sample_rate)),
        str(Path(output_audio).resolve()),
    ]


def build_demucs_command(
    *,
    python: str | Path,
    source: str | Path,
    output_dir: str | Path,
    model: str = DEFAULT_DEMUCS_MODEL,
    shifts: int = DEFAULT_DEMUCS_SHIFTS,
) -> list[str]:
    return [
        str(python),
        "-m",
        "demucs",
        "-n",
        str(model),
        "--shifts",
        str(int(shifts)),
        "-o",
        str(Path(output_dir).resolve()),
        str(Path(source).resolve()),
    ]


def _command_text(command: Sequence[str]) -> str:
    if os.name == "nt":
        return subprocess.list2cmdline(list(command))
    return shlex.join(command)


def _find_ffmpeg(explicit: str | Path | None = None) -> Path:
    if explicit is not None:
        candidate = Path(explicit)
        if candidate.is_file():
            return candidate.resolve()
        resolved = shutil.which(str(explicit))
        if resolved:
            return Path(resolved).resolve()
        raise FileNotFoundError(f"找不到 ffmpeg：{explicit}")
    resolved = shutil.which("ffmpeg")
    if resolved:
        return Path(resolved).resolve()
    raise FileNotFoundError("未找到 ffmpeg；请安装 ffmpeg 并加入 PATH。")


def _find_audio_python(project_root: Path) -> Path:
    candidates = (
        project_root / ".audio-venv" / "Scripts" / "python.exe",
        project_root / ".audio-venv" / "bin" / "python",
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    return Path(sys.executable).resolve()


def _run_logged(command: Sequence[str], log_path: Path, *, cwd: Path | None = None) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
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
            check=False,
        )
    except OSError as exc:
        log_path.write_text(
            f"command: {_command_text(command)}\nerror: {exc}\n", encoding="utf-8"
        )
        raise MediaPipelineError(f"命令启动失败：{exc}；详见 {log_path}") from exc
    log_path.write_text(
        f"command: {_command_text(command)}\n"
        f"returncode: {result.returncode}\n"
        "--- stdout ---\n"
        f"{result.stdout or ''}\n"
        "--- stderr ---\n"
        f"{result.stderr or ''}\n",
        encoding="utf-8",
    )
    if result.returncode != 0:
        tail = "\n".join((result.stderr or result.stdout or "").splitlines()[-20:])
        raise MediaPipelineError(
            f"媒体处理失败（退出码 {result.returncode}）；详见 {log_path}\n{tail}"
        )


def extract_audio(
    source: str | Path,
    output_audio: str | Path,
    *,
    ffmpeg: str | Path | None = None,
    sample_rate: int = DEFAULT_SAMPLE_RATE,
    log_path: str | Path | None = None,
) -> Path:
    """Extract mono WAV from a video using ffmpeg."""

    media = Path(source).expanduser()
    if not media.is_file():
        raise FileNotFoundError(f"找不到媒体文件：{media}")
    if detect_media_kind(media) != "video":
        raise ValueError("extract_audio 只接受视频文件")
    output = Path(output_audio).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    command = build_ffmpeg_extract_command(
        ffmpeg=_find_ffmpeg(ffmpeg),
        source=media,
        output_audio=output,
        sample_rate=sample_rate,
    )
    log = Path(log_path) if log_path is not None else output.parent / "ffmpeg-extract.log"
    _run_logged(command, log)
    if not output.is_file():
        raise MediaPipelineError(f"ffmpeg 命令成功但没有生成音频：{output}")
    return output.resolve()


def _find_stem(output_dir: Path, stem_name: str) -> Path:
    candidates = sorted(
        path for path in output_dir.rglob(f"{stem_name}.wav") if path.is_file()
    )
    if not candidates:
        raise MediaPipelineError(f"Demucs 未找到 {stem_name}.wav：{output_dir}")
    return candidates[0].resolve()


def separate_audio(
    source_audio: str | Path,
    output_dir: str | Path,
    *,
    project_root: str | Path,
    model: str = DEFAULT_DEMUCS_MODEL,
    shifts: int = DEFAULT_DEMUCS_SHIFTS,
    stem: str = "guitar",
    log_path: str | Path | None = None,
) -> Path:
    """Separate audio with the optional Demucs environment and return a stem."""

    source = Path(source_audio).expanduser()
    if not source.is_file():
        raise FileNotFoundError(f"找不到待分离音频：{source}")
    output = Path(output_dir).expanduser()
    output.mkdir(parents=True, exist_ok=True)
    root = Path(project_root).expanduser().resolve()
    python = _find_audio_python(root)
    command = build_demucs_command(
        python=python,
        source=source,
        output_dir=output,
        model=model,
        shifts=shifts,
    )
    log = Path(log_path) if log_path is not None else output / "demucs.log"
    _run_logged(command, log, cwd=root)
    return _find_stem(output, stem)


def _write_pipeline_report(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def convert_media_to_sky(
    source: str | Path,
    output_dir: str | Path,
    *,
    project_root: str | Path,
    backend: str = "tsumugi",
    separate: bool = False,
    stem: str = "guitar",
    demucs_model: str = DEFAULT_DEMUCS_MODEL,
    demucs_shifts: int = DEFAULT_DEMUCS_SHIFTS,
    tsumugi_model: str = "guitar_v1_5",
    device: str = "auto",
    merge_onset_ms: float = 50.0,
    basic_pitch_executable: str | Path | None = None,
    key: str = "C",
    subdivisions: int = 4,
    shift: int | None = None,
    chromatic_policy: str = "drop",
    title: str | None = None,
) -> dict[str, Any]:
    """Run media preparation, one MIDI backend, and the unified Sky stage."""

    media = Path(source).expanduser().resolve()
    if not media.is_file():
        raise FileNotFoundError(f"找不到媒体文件：{media}")
    kind = detect_media_kind(media)
    backend_name = str(backend).strip().lower().replace("-", "_")
    if backend_name not in AUDIO_BACKENDS:
        raise ValueError(f"不支持的 MIDI 后端：{backend}")
    output = Path(output_dir).expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    layout = output_layout(output, media.stem, backend=backend_name, input_label=stem)
    layout.audio.mkdir(parents=True, exist_ok=True)
    layout.separation.mkdir(parents=True, exist_ok=True)

    if kind == "video":
        prepared_audio = extract_audio(
            media,
            layout.audio / f"{media.stem}.wav",
        )
    else:
        prepared_audio = media

    selected_audio = prepared_audio
    separation_path: Path | None = None
    if separate:
        separation_path = separate_audio(
            prepared_audio,
            layout.separation / demucs_model,
            project_root=project_root,
            model=demucs_model,
            shifts=demucs_shifts,
            stem=stem,
        )
        selected_audio = separation_path

    midi_path = transcribe_audio(
        selected_audio,
        layout.midi,
        backend=backend_name,
        project_root=project_root,
        model=tsumugi_model,
        device=device,
        merge_onset_ms=merge_onset_ms,
        basic_pitch_executable=basic_pitch_executable,
    )
    sky_payload = convert_midi_file_to_sky(
        midi_path,
        layout.sky,
        key=key,
        subdivisions=subdivisions,
        shift=shift,
        chromatic_policy=chromatic_policy,
        title=title or media.stem,
    )
    report = {
        "format": "audio-to-sky-v1",
        "source": str(media),
        "media_kind": kind,
        "prepared_audio": str(prepared_audio),
        "selected_audio": str(selected_audio),
        "separation": {
            "enabled": separate,
            "stem": stem,
            "model": demucs_model if separate else None,
            "shifts": demucs_shifts if separate else None,
            "path": str(separation_path) if separation_path else None,
        },
        "midi": {
            "backend": backend_name,
            "model": tsumugi_model if backend_name == "tsumugi" else None,
            "path": str(midi_path),
        },
        "sky": sky_payload["report"],
    }
    _write_pipeline_report(output / "pipeline.report.json", report)
    return {
        "kind": kind,
        "audio": prepared_audio,
        "selected_audio": selected_audio,
        "separation": separation_path,
        "midi": midi_path,
        "sky": sky_payload,
        "report": report,
        "output_dir": output,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="视频/音频 → 可选分离 → MIDI → Sky 黑白/彩谱")
    parser.add_argument("source", type=Path, help="输入视频或音频")
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--backend", choices=AUDIO_BACKENDS, default="tsumugi")
    parser.add_argument("--separate", action="store_true", help="先用 Demucs 分离并选择 guitar stem")
    parser.add_argument("--stem", default="guitar")
    parser.add_argument("--demucs-model", default=DEFAULT_DEMUCS_MODEL)
    parser.add_argument("--demucs-shifts", type=int, default=DEFAULT_DEMUCS_SHIFTS)
    parser.add_argument("--tsumugi-model", default="guitar_v1_5")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--merge-onset-ms", type=float, default=50.0)
    parser.add_argument("--key", default="C")
    parser.add_argument("--subdivisions", type=int, default=4)
    parser.add_argument("--shift", type=int, default=None)
    parser.add_argument("--chromatic-policy", choices=("error", "drop", "nearest"), default="drop")
    parser.add_argument("--title", default=None)
    args = parser.parse_args(argv)
    try:
        payload = convert_media_to_sky(
            args.source,
            args.out_dir,
            project_root=args.project_root,
            backend=args.backend,
            separate=args.separate,
            stem=args.stem,
            demucs_model=args.demucs_model,
            demucs_shifts=args.demucs_shifts,
            tsumugi_model=args.tsumugi_model,
            device=args.device,
            merge_onset_ms=args.merge_onset_ms,
            key=args.key,
            subdivisions=args.subdivisions,
            shift=args.shift,
            chromatic_policy=args.chromatic_policy,
            title=args.title,
        )
    except (OSError, MediaPipelineError, ValueError) as exc:
        parser.error(str(exc))
    report = payload["sky"]["report"]
    print(
        f"已完成：MIDI {payload['midi']}；"
        f"映射 {report['mapped_note_count']} 音符；"
        f"黑白图 {report['black_image_count']}；彩色图 {report['color_image_count']}"
    )
    return 0


__all__ = [
    "DEFAULT_DEMUCS_MODEL",
    "DEFAULT_DEMUCS_SHIFTS",
    "MediaPipelineError",
    "OutputLayout",
    "VIDEO_EXTENSIONS",
    "build_demucs_command",
    "build_ffmpeg_extract_command",
    "convert_media_to_sky",
    "detect_media_kind",
    "extract_audio",
    "output_layout",
    "separate_audio",
]


if __name__ == "__main__":
    raise SystemExit(main())
