from pathlib import Path

from scripts.audio_pipeline import (
    build_demucs_command,
    build_ffmpeg_extract_command,
    detect_media_kind,
    extract_audio,
    output_layout,
    separate_audio,
)


def test_detect_media_kind_supports_video_and_audio(tmp_path: Path):
    assert detect_media_kind(tmp_path / "song.mp4") == "video"
    assert detect_media_kind(tmp_path / "song.mkv") == "video"
    assert detect_media_kind(tmp_path / "song.wav") == "audio"
    assert detect_media_kind(tmp_path / "song.mp3") == "audio"


def test_ffmpeg_extract_command_removes_video_and_normalizes_audio(tmp_path: Path):
    command = build_ffmpeg_extract_command(
        ffmpeg=tmp_path / "ffmpeg.exe",
        source=tmp_path / "sample_song.mp4",
        output_audio=tmp_path / "audio" / "sample_song.wav",
        sample_rate=44_100,
    )

    assert command == [
        str(tmp_path / "ffmpeg.exe"),
        "-y",
        "-i",
        str((tmp_path / "sample_song.mp4").resolve()),
        "-vn",
        "-ac",
        "1",
        "-ar",
        "44100",
        str((tmp_path / "audio" / "sample_song.wav").resolve()),
    ]


def test_demucs_command_uses_guitar_separation_defaults(tmp_path: Path):
    command = build_demucs_command(
        python=tmp_path / "python.exe",
        source=tmp_path / "song.wav",
        output_dir=tmp_path / "separation",
        model="htdemucs_6s",
        shifts=5,
    )

    assert command == [
        str(tmp_path / "python.exe"),
        "-m",
        "demucs",
        "-n",
        "htdemucs_6s",
        "--shifts",
        "5",
        "-o",
        str((tmp_path / "separation").resolve()),
        str((tmp_path / "song.wav").resolve()),
    ]


def test_output_layout_separates_audio_midi_and_sky_artifacts(tmp_path: Path):
    layout = output_layout(tmp_path, "sample_song", backend="tsumugi", input_label="guitar_highpass")

    assert layout.audio == tmp_path / "audio"
    assert layout.separation == tmp_path / "separation"
    assert layout.midi == tmp_path / "midi" / "tsumugi__guitar_highpass.mid"
    assert layout.sky == tmp_path / "sky" / "tsumugi__guitar_highpass"


def test_extract_audio_uses_logged_ffmpeg_and_returns_generated_wav(tmp_path: Path, monkeypatch):
    source = tmp_path / "song.mp4"
    source.write_bytes(b"video")
    ffmpeg = tmp_path / "ffmpeg.exe"
    ffmpeg.write_bytes(b"executable")
    calls = []

    def fake_run(command, log_path, *, cwd=None):
        calls.append((command, log_path, cwd))
        Path(command[-1]).write_bytes(b"wav")

    monkeypatch.setattr("scripts.audio_pipeline._run_logged", fake_run)
    output = extract_audio(source, tmp_path / "audio" / "song.wav", ffmpeg=ffmpeg)

    assert output == (tmp_path / "audio" / "song.wav").resolve()
    assert calls[0][0][0] == str(ffmpeg.resolve())
    assert calls[0][1].name == "ffmpeg-extract.log"


def test_separate_audio_returns_requested_stem(tmp_path: Path, monkeypatch):
    source = tmp_path / "song.wav"
    source.write_bytes(b"wav")
    calls = []

    def fake_run(command, log_path, *, cwd=None):
        calls.append((command, log_path, cwd))
        stem = Path(command[command.index("-o") + 1]) / "htdemucs_6s" / "song" / "guitar.wav"
        stem.parent.mkdir(parents=True)
        stem.write_bytes(b"guitar")

    monkeypatch.setattr("scripts.audio_pipeline._run_logged", fake_run)
    output = separate_audio(source, tmp_path / "separation", project_root=tmp_path)

    assert output == (tmp_path / "separation" / "htdemucs_6s" / "song" / "guitar.wav").resolve()
    assert calls[0][0][2:6] == ["demucs", "-n", "htdemucs_6s", "--shifts"]


def test_convert_media_to_sky_runs_the_selected_stages_in_order(tmp_path: Path, monkeypatch):
    import scripts.audio_pipeline as pipeline

    source = tmp_path / "song.mp4"
    source.write_bytes(b"video")
    calls = []

    def fake_extract(source_path, output_audio):
        calls.append(("extract", source_path, output_audio))
        output_audio.parent.mkdir(parents=True, exist_ok=True)
        output_audio.write_bytes(b"wav")
        return output_audio.resolve()

    def fake_separate(source_audio, output_dir, **options):
        calls.append(("separate", source_audio, output_dir, options))
        stem = output_dir / "guitar.wav"
        stem.parent.mkdir(parents=True, exist_ok=True)
        stem.write_bytes(b"guitar")
        return stem.resolve()

    def fake_transcribe(source_audio, output_midi, **options):
        calls.append(("midi", source_audio, output_midi, options))
        output_midi.parent.mkdir(parents=True, exist_ok=True)
        output_midi.write_bytes(b"MThd")
        return output_midi.resolve()

    def fake_sky(source_midi, sky_dir, **options):
        calls.append(("sky", source_midi, sky_dir, options))
        return {"report": {"input_note_count": 1, "mapped_note_count": 1}}

    monkeypatch.setattr(pipeline, "extract_audio", fake_extract)
    monkeypatch.setattr(pipeline, "separate_audio", fake_separate)
    monkeypatch.setattr(pipeline, "transcribe_audio", fake_transcribe)
    monkeypatch.setattr(pipeline, "convert_midi_file_to_sky", fake_sky)

    result = pipeline.convert_media_to_sky(
        source,
        tmp_path / "out",
        project_root=tmp_path,
        backend="tsumugi",
        separate=True,
        stem="guitar",
        key="C",
        chromatic_policy="nearest",
    )

    assert [call[0] for call in calls] == ["extract", "separate", "midi", "sky"]
    assert result["midi"] == (
        tmp_path / "out" / "midi" / "tsumugi__guitar.mid"
    ).resolve()
    report = (tmp_path / "out" / "pipeline.report.json").read_text(encoding="utf-8")
    assert '"backend": "tsumugi"' in report
    assert '"enabled": true' in report
