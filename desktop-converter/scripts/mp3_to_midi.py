"""Lazy subprocess adapter for the optional Spotify Basic Pitch backend."""

from __future__ import annotations

import argparse
import shutil
import shlex
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Sequence


AUDIO_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg"}


def _setup_hint(project_root: Path) -> str:
    return str(project_root / "scripts" / "setup_audio_backend.ps1")


def find_basic_pitch_executable(
    project_root: str | Path,
    explicit: str | Path | None = None,
) -> Path:
    """Find Basic Pitch without importing its heavy Python package."""

    root = Path(project_root)
    if explicit is not None:
        explicit_path = Path(explicit)
        if explicit_path.is_file():
            return explicit_path.resolve()
        resolved = shutil.which(str(explicit))
        if resolved:
            return Path(resolved).resolve()
        raise FileNotFoundError(
            f"找不到指定的 Basic Pitch 可执行文件：{explicit}；"
            f"请检查路径或运行 {_setup_hint(root)}"
        )

    candidates = (
        root / ".audio-venv" / "Scripts" / "basic-pitch.exe",
        root / ".audio-venv" / "Scripts" / "basic-pitch",
        root / ".audio-venv" / "bin" / "basic-pitch",
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    resolved = shutil.which("basic-pitch")
    if resolved:
        return Path(resolved).resolve()
    raise FileNotFoundError(
        f"未找到 Basic Pitch；请先运行 {_setup_hint(root)}，"
        "或在 PATH 中提供 basic-pitch"
    )


def _command_for_executable(executable: Path, arguments: Sequence[str]) -> list[str]:
    suffix = executable.suffix.lower()
    if suffix in {".cmd", ".bat"}:
        return ["cmd.exe", "/d", "/c", str(executable), *arguments]
    if suffix == ".py":
        return [sys.executable, str(executable), *arguments]
    return [str(executable), *arguments]


def _command_text(command: Sequence[str]) -> str:
    if sys.platform == "win32":
        return subprocess.list2cmdline(list(command))
    return shlex.join(command)


def _write_process_log(
    log_path: Path,
    *,
    command: Sequence[str],
    result: subprocess.CompletedProcess[str] | None = None,
    error: str | None = None,
) -> None:
    lines = [f"command: {_command_text(command)}"]
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
    log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def transcribe_audio(
    audio_path: str | Path,
    output_dir: str | Path,
    *,
    executable: str | Path | None = None,
    project_root: str | Path | None = None,
) -> Path:
    """Run Basic Pitch for a supported audio file and return its actual MIDI."""

    audio = Path(audio_path)
    if not audio.is_file():
        raise FileNotFoundError(f"找不到音频文件：{audio}")
    if audio.suffix.lower() not in AUDIO_EXTENSIONS:
        choices = ", ".join(sorted(AUDIO_EXTENSIONS))
        raise ValueError(f"不支持的音频格式：{audio.suffix or '无扩展名'}；支持 {choices}")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    root = Path(project_root) if project_root is not None else Path(__file__).resolve().parents[1]
    backend = find_basic_pitch_executable(root, explicit=executable)
    command = _command_for_executable(
        backend,
        [
            str(output.resolve()),
            str(audio.resolve()),
            "--save-midi",
            "--save-note-events",
        ],
    )
    log_path = output / "audio_to_midi.log"
    started_at = time.time()
    try:
        process_environment = os.environ.copy()
        process_environment["PYTHONIOENCODING"] = "utf-8"
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=process_environment,
            check=False,
        )
    except OSError as exc:
        _write_process_log(log_path, command=command, error=str(exc))
        raise RuntimeError(
            f"Basic Pitch 启动失败：{exc}；详见 {log_path}；命令：{_command_text(command)}"
        ) from exc
    _write_process_log(log_path, command=command, result=result)
    if result.returncode != 0:
        output_tail = "\n".join((result.stderr or result.stdout or "").splitlines()[-12:])
        raise RuntimeError(
            f"Basic Pitch 转换失败（退出码 {result.returncode}）；详见 {log_path}\n"
            f"命令：{_command_text(command)}\n{output_tail}"
        )

    midi_candidates = [
        candidate
        for pattern in ("*.mid", "*.midi")
        for candidate in output.glob(pattern)
        if candidate.is_file() and candidate.stat().st_mtime >= started_at - 2
    ]
    if not midi_candidates:
        raise RuntimeError(
            f"Basic Pitch 命令成功但输出目录没有 MIDI：{output}；详见 {log_path}"
        )
    return max(midi_candidates, key=lambda candidate: candidate.stat().st_mtime).resolve()


def transcribe_mp3(
    audio_path: str | Path,
    output_dir: str | Path,
    *,
    executable: str | Path | None = None,
    project_root: str | Path | None = None,
) -> Path:
    """Transcribe an MP3; use :func:`transcribe_audio` for other audio types."""

    audio = Path(audio_path)
    if audio.suffix.lower() != ".mp3":
        raise ValueError(f"transcribe_mp3 只接受 MP3 文件，收到：{audio.suffix or '无扩展名'}")
    return transcribe_audio(
        audio,
        output_dir,
        executable=executable,
        project_root=project_root,
    )


__all__ = [
    "AUDIO_EXTENSIONS",
    "find_basic_pitch_executable",
    "transcribe_audio",
    "transcribe_mp3",
]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="使用 Basic Pitch 将音频转换为 MIDI。")
    parser.add_argument("audio", type=Path, help="输入 MP3/WAV/FLAC/M4A/OGG 文件")
    parser.add_argument("--out-dir", type=Path, default=Path("outputs/audio-to-midi"))
    parser.add_argument("--basic-pitch", type=Path, default=None, help="Basic Pitch 可执行文件路径")
    args = parser.parse_args(argv)
    try:
        midi_path = transcribe_audio(
            args.audio,
            args.out_dir,
            executable=args.basic_pitch,
        )
    except (OSError, RuntimeError, ValueError) as exc:
        parser.error(str(exc))
    print(f"已生成 MIDI：{midi_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
