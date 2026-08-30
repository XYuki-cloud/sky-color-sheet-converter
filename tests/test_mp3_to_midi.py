from pathlib import Path

import pytest

from scripts.mp3_to_midi import (
    find_basic_pitch_executable,
    transcribe_mp3,
)


def test_executable_discovery_prefers_explicit_and_audio_venv_paths(tmp_path: Path):
    explicit = tmp_path / "custom-basic-pitch.exe"
    explicit.write_text("placeholder", encoding="utf-8")
    assert find_basic_pitch_executable(tmp_path, explicit=explicit) == explicit

    bundled = tmp_path / ".audio-venv" / "Scripts" / "basic-pitch.exe"
    bundled.parent.mkdir(parents=True)
    bundled.write_text("placeholder", encoding="utf-8")
    assert find_basic_pitch_executable(tmp_path) == bundled


def test_missing_backend_error_points_to_setup_script(tmp_path: Path):
    with pytest.raises(FileNotFoundError, match="setup_audio_backend.ps1"):
        find_basic_pitch_executable(tmp_path)


def test_transcribe_rejects_non_mp3_and_discovers_mid_after_success(tmp_path: Path):
    fake = tmp_path / "fake-basic-pitch.cmd"
    fake.write_text(
        "@echo off\n"
        "if not exist \"%~1\" mkdir \"%~1\"\n"
        "> \"%~1\\detected.mid\" echo fake midi\n"
        "> \"%~1\\detected.csv\" echo fake events\n",
        encoding="utf-8",
    )
    audio = tmp_path / "tone.mp3"
    audio.write_bytes(b"not decoded by fake executable")
    output = tmp_path / "out"

    midi_path = transcribe_mp3(audio, output, executable=fake)

    assert midi_path == output / "detected.mid"
    assert midi_path.is_file()
    assert (output / "detected.csv").is_file()
    assert (output / "audio_to_midi.log").is_file()

    wav = tmp_path / "tone.wav"
    wav.write_bytes(b"wav")
    with pytest.raises(ValueError, match="MP3"):
        transcribe_mp3(wav, output, executable=fake)
