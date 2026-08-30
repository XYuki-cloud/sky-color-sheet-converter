from pathlib import Path

import mido
import pytest

from scripts.audio_to_midi import (
    AUDIO_BACKENDS,
    build_python_backend_command,
    build_tsumugi_command,
    find_tsumugi_python,
    resolve_backend,
    write_note_events_midi,
)
from scripts.music_events import NoteEvent


def test_audio_backend_registry_has_preferred_and_fallback_routes():
    assert AUDIO_BACKENDS == ("tsumugi", "basic_pitch", "pyin", "torchcrepe")
    assert resolve_backend("Tsumugi") == "tsumugi"
    assert resolve_backend(" basic_pitch ") == "basic_pitch"

    with pytest.raises(ValueError, match="支持的 MIDI 后端"):
        resolve_backend("unknown")


def test_tsumugi_command_is_explicit_and_reproducible(tmp_path: Path):
    command = build_tsumugi_command(
        python=tmp_path / "python.exe",
        repo=tmp_path / "tsumugi",
        audio=tmp_path / "guitar.wav",
        output_midi=tmp_path / "song.mid",
        checkpoint=tmp_path / "guitar.pth",
        model="guitar_v1_5",
        device="auto",
        merge_onset_ms=50.0,
    )

    assert command == [
        str(tmp_path / "python.exe"),
        str(tmp_path / "tsumugi" / "infer.py"),
        "--audio",
        str((tmp_path / "guitar.wav").resolve()),
        "--output-midi",
        str((tmp_path / "song.mid").resolve()),
        "--checkpoint",
        str((tmp_path / "guitar.pth").resolve()),
        "--type",
        "guitar_v1_5",
        "--device",
        "auto",
        "--merge-onset-ms",
        "50.0",
    ]


def test_tsumugi_python_prefers_the_submodule_environment(tmp_path: Path):
    repo = tmp_path / "vendor" / "tsumugi"
    submodule_python = repo / ".venv" / "Scripts" / "python.exe"
    project_python = tmp_path / ".audio-venv" / "Scripts" / "python.exe"
    submodule_python.parent.mkdir(parents=True)
    project_python.parent.mkdir(parents=True)
    submodule_python.write_bytes(b"python")
    project_python.write_bytes(b"python")

    assert find_tsumugi_python(repo, tmp_path) == submodule_python.resolve()


def test_tsumugi_submodule_metadata_is_public_and_stable():
    metadata = Path(".gitmodules").read_text(encoding="utf-8")
    assert "path = vendor/tsumugi" in metadata
    assert "https://github.com/anime-song/tsumugi.git" in metadata


def test_optional_backend_worker_command_uses_the_audio_environment(tmp_path: Path):
    command = build_python_backend_command(
        python=tmp_path / "audio-python.exe",
        script=tmp_path / "scripts" / "audio_to_midi.py",
        audio=tmp_path / "song.wav",
        output_midi=tmp_path / "song.mid",
        project_root=tmp_path,
        backend="pyin",
    )

    assert command == [
        str(tmp_path / "audio-python.exe"),
        str(tmp_path / "scripts" / "audio_to_midi.py"),
        str((tmp_path / "song.wav").resolve()),
        "--out-midi",
        str((tmp_path / "song.mid").resolve()),
        "--backend",
        "pyin",
        "--project-root",
        str(tmp_path.resolve()),
    ]


def test_write_note_events_midi_preserves_absolute_timing_and_chords(tmp_path: Path):
    target = tmp_path / "events.mid"
    events = (
        NoteEvent(start_ms=0, duration_ms=250, pitch=60, velocity=90),
        NoteEvent(start_ms=0, duration_ms=250, pitch=64, velocity=80),
        NoteEvent(start_ms=500, duration_ms=125, pitch=67, velocity=70),
    )

    result = write_note_events_midi(events, target, bpm=120, ticks_per_beat=480)
    assert result == target

    midi = mido.MidiFile(target)
    assert len(midi.tracks) == 1
    absolute = 0
    note_on_times = []
    for message in midi.tracks[0]:
        absolute += message.time
        if message.type == "note_on" and message.velocity > 0:
            note_on_times.append((absolute, message.note))
    assert note_on_times[:2] == [(0, 60), (0, 64)]
    assert note_on_times[2][0] == 480


def test_basic_pitch_can_receive_an_explicit_executable(tmp_path: Path, monkeypatch):
    import scripts.audio_to_midi as audio_to_midi

    audio = tmp_path / "song.wav"
    audio.write_bytes(b"wav")
    executable = tmp_path / "basic-pitch.exe"
    captured = {}

    def fake_basic_pitch(audio_path, output_dir, **options):
        captured.update(options)
        generated = Path(output_dir) / "detected.mid"
        generated.write_bytes(b"MThd")
        return generated

    monkeypatch.setattr(audio_to_midi, "transcribe_basic_pitch", fake_basic_pitch)
    target = tmp_path / "wanted.mid"
    result = audio_to_midi.transcribe_audio(
        audio,
        target,
        backend="basic_pitch",
        project_root=tmp_path,
        basic_pitch_executable=executable,
    )

    assert result == target.resolve()
    assert target.read_bytes() == b"MThd"
    assert captured["executable"] == executable
    assert captured["project_root"] == tmp_path.resolve()
