from pathlib import Path

import pytest

from scripts.music_converter_gui import (
    ConversionPlan,
    build_conversion_plan,
    default_output_dir,
    detect_input_kind,
    format_conversion_summary,
    input_option_state,
)


def test_gui_dispatches_supported_input_types(tmp_path: Path):
    midi = tmp_path / "song.mid"
    midi.write_bytes(b"MThd")
    audio = tmp_path / "song.mp3"
    audio.write_bytes(b"audio")
    jianpu = tmp_path / "song.jpwabc"
    jianpu.write_text("1 2 3", encoding="utf-8")
    jianpu_txt = tmp_path / "song.txt"
    jianpu_txt.write_text("@key=C\n1 2 3", encoding="utf-8")
    sky_txt = tmp_path / "sky.txt"
    sky_txt.write_text('[{"songNotes": []}]', encoding="utf-8")

    assert detect_input_kind(midi) == "midi"
    assert detect_input_kind(audio) == "audio"
    assert detect_input_kind(jianpu) == "jianpu"
    assert detect_input_kind(jianpu_txt) == "jianpu"
    assert detect_input_kind(sky_txt) == "sky-json"


def test_gui_rejects_unknown_input_with_chinese_error(tmp_path: Path):
    source = tmp_path / "song.pdf"
    source.write_bytes(b"pdf")

    with pytest.raises(ValueError, match="不支持的输入格式"):
        detect_input_kind(source)


def test_gui_plan_defaults_to_outputs_song_subfolder(tmp_path: Path):
    source = tmp_path / "sample_song.mp3"

    assert default_output_dir(tmp_path, source) == tmp_path / "outputs" / "sample_song"
    plan = build_conversion_plan(source, tmp_path)
    assert isinstance(plan, ConversionPlan)
    assert plan.kind == "audio"
    assert plan.output_dir == tmp_path / "outputs" / "sample_song"


def test_gui_detects_video_for_the_audio_pipeline(tmp_path: Path):
    video = tmp_path / "song.mp4"
    video.write_bytes(b"video")

    assert detect_input_kind(video) == "video"


def test_gui_audio_dispatch_passes_backend_and_separation_options(tmp_path: Path, monkeypatch):
    import scripts.music_converter_gui as gui

    source = tmp_path / "song.wav"
    source.write_bytes(b"audio")
    calls = []

    def fake_convert(source_path, output_path, **options):
        calls.append((source_path, output_path, options))
        return {"kind": "audio", "output_dir": output_path}

    monkeypatch.setattr(gui, "convert_media_to_sky", fake_convert)
    result = gui.convert_source_file(
        source,
        tmp_path / "out",
        project_root=tmp_path,
        key="C",
        chromatic_policy="nearest",
        backend="tsumugi",
        separate=True,
        stem="guitar",
        demucs_model="htdemucs_6s",
        demucs_shifts=5,
        device="auto",
    )

    assert result["kind"] == "audio"
    assert calls == [
        (
            source,
            tmp_path / "out",
            {
                "project_root": tmp_path,
                "backend": "tsumugi",
                "separate": True,
                "stem": "guitar",
                "demucs_model": "htdemucs_6s",
                "demucs_shifts": 5,
                "key": "C",
                "chromatic_policy": "nearest",
                "device": "auto",
            },
        )
    ]


def test_gui_midi_dispatch_calls_unified_sky_stage(tmp_path: Path, monkeypatch):
    import scripts.music_converter_gui as gui

    source = tmp_path / "song.mid"
    source.write_bytes(b"MThd")
    calls = []

    def fake_convert(source_path, output_path, **options):
        calls.append((source_path, output_path, options))
        return {"report": {"input_note_count": 0}}

    monkeypatch.setattr(gui, "convert_midi_file_to_sky", fake_convert)
    result = gui.convert_source_file(source, tmp_path / "out", key="G", chromatic_policy="nearest")

    assert result["kind"] == "midi"
    assert calls == [
        (
            source,
            tmp_path / "out",
            {"key": "G", "chromatic_policy": "nearest"},
        )
    ]


def test_gui_summary_reports_mobile_png_page_count():
    summary = format_conversion_summary(
        {
            "report": {
                "input_note_count": 3,
                "mapped_note_count": 3,
                "black_image_count": 3,
                "color_image_count": 2,
                "artifacts": {
                    "color_mobile_png_pages": [
                        "song.color-mobile-001.png",
                        "song.color-mobile-002.png",
                    ]
                },
            }
        }
    )

    assert "手机竖版 2 张" in summary


def test_gui_option_state_keeps_audio_controls_out_of_non_audio_workflows():
    audio = input_option_state("audio")
    midi = input_option_state("midi")
    jianpu = input_option_state("jianpu")
    sky_json = input_option_state("sky-json")

    assert audio["audio_options_enabled"] is True
    assert midi["audio_options_enabled"] is False
    assert midi["bpm_enabled"] is False
    assert jianpu["bpm_enabled"] is True
    assert sky_json["song_index_enabled"] is True
    assert midi["song_index_enabled"] is False
