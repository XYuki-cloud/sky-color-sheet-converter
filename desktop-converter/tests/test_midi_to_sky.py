import json
from pathlib import Path

import mido
import pytest
from PIL import Image

from scripts.midi_to_sky import MidiToSkyError, convert_midi_file_to_sky


def _write_midi(path: Path, notes: list[tuple[int, int, int]]) -> None:
    """Write (absolute_tick, pitch, duration_ticks) test notes."""

    midi = mido.MidiFile(type=1, ticks_per_beat=480)
    track = mido.MidiTrack()
    midi.tracks.append(track)
    track.append(mido.MetaMessage("set_tempo", tempo=500000, time=0))
    messages = []
    for start, pitch, duration in notes:
        messages.append((start, 1, pitch, 72))
        messages.append((start + duration, 0, pitch, 0))
    messages.sort(key=lambda item: (item[0], item[1]))
    previous = 0
    for tick, is_on, pitch, velocity in messages:
        track.append(
            mido.Message(
                "note_on" if is_on else "note_off",
                note=pitch,
                velocity=velocity,
                time=tick - previous,
            )
        )
        previous = tick
    midi.save(path)


def test_midi_conversion_writes_black_and_color_artifacts(tmp_path: Path):
    source = tmp_path / "song.mid"
    _write_midi(
        source,
        [
            (0, 60, 120),
            (480, 64, 240),
            (480, 67, 240),
            (960, 62, 120),
        ],
    )

    payload = convert_midi_file_to_sky(
        source,
        tmp_path / "out",
        key="C",
        include_desktop_pages=True,
    )

    assert payload["black"]["format"] == "sky-black-v1"
    assert payload["color"]["format"] == "sky-color-v1"
    assert payload["black"]["source_frame_count"] == 3
    assert [
        layer["color"]
        for image in payload["black"]["images"]
        for layer in image["layers"]
    ] == ["black", "black", "black"]
    assert [
        layer["source_time"]
        for image in payload["black"]["images"]
        for layer in image["layers"]
    ] == [0, 500, 1000]
    assert payload["color"]["colors"] == [
        {"name": "black", "hex": "#000000"},
        {"name": "red", "hex": "#FF0000"},
        {"name": "blue", "hex": "#0000FF"},
    ]
    assert all(
        len(
            [key for layer in image["layers"] for key in layer["keys"]]
        )
        == len({key for layer in image["layers"] for key in layer["keys"]})
        for image in payload["color"]["images"]
    )
    assert payload["color"]["images"][0]["layers"][1]["keys"] == ["A3", "A5"]

    output = tmp_path / "out"
    assert (output / "song.sky.json").is_file()
    assert (output / "song.sky-000.png").is_file()
    assert (output / "song.sky-001.png").is_file()
    assert (output / "song.color.json").is_file()
    assert (output / "song.color-000.png").is_file()
    assert (output / "song.color-001.png").is_file()
    assert payload["color"]["artifacts"]["mobile_png_pages"] == [
        "song.color-mobile-000.png",
        "song.color-mobile-001.png"
    ]
    assert payload["color"]["artifacts"]["cover_png"] == "song.color-mobile-000.png"
    assert payload["color"]["artifacts"]["desktop_cover_png"] == "song.color-000.png"
    assert payload["report"]["artifacts"]["color_mobile_png_pages"] == [
        "song.color-mobile-000.png",
        "song.color-mobile-001.png"
    ]
    assert payload["report"]["artifacts"]["color_mobile_cover_png"] == "song.color-mobile-000.png"
    assert (output / "song.color-mobile-000.png").is_file()
    assert (output / "song.color-mobile-001.png").is_file()
    black_page = Image.open(output / "song.sky-001.png").convert("RGB")
    assert black_page.getpixel((52, 130)) == (0, 0, 0)
    assert black_page.getpixel((172, 130)) == (255, 255, 255)


def test_midi_conversion_skips_desktop_pages_by_default(tmp_path: Path):
    source = tmp_path / "mobile_only.mid"
    _write_midi(source, [(0, 60, 120), (480, 64, 120)])

    payload = convert_midi_file_to_sky(source, tmp_path / "out", key="C")

    output = tmp_path / "out"
    assert payload["black"]["artifacts"]["png_pages"] == []
    assert payload["color"]["artifacts"]["png_pages"] == []
    assert payload["report"]["artifacts"]["black_png_pages"] == []
    assert payload["report"]["artifacts"]["color_png_pages"] == []
    assert not (output / "mobile_only.sky-001.png").exists()
    assert not (output / "mobile_only.color-001.png").exists()
    assert not (output / "mobile_only.color-000.png").exists()
    assert not (output / "mobile_only.sky-000.png").exists()
    assert (output / "mobile_only.color-mobile-000.png").is_file()
    assert (output / "mobile_only.color-mobile-001.png").is_file()


def test_repeated_key_starts_a_new_color_image(tmp_path: Path):
    source = tmp_path / "overlap.mid"
    _write_midi(source, [(0, 60, 120), (480, 60, 120), (960, 62, 120)])

    payload = convert_midi_file_to_sky(source, tmp_path / "out", key="C")

    assert len(payload["color"]["images"]) == 2
    assert [
        image["layers"][0]["source_frame_index"]
        for image in payload["color"]["images"]
    ] == [1, 2]
    assert payload["color"]["images"][1]["layers"][1]["keys"] == ["A2"]


def test_chromatic_and_out_of_range_pitches_are_reported(tmp_path: Path):
    source = tmp_path / "diagnostics.mid"
    _write_midi(source, [(0, 61, 120), (480, 60, 120)])

    payload = convert_midi_file_to_sky(
        source,
        tmp_path / "out",
        key="C",
        shift=20,
        chromatic_policy="drop",
    )

    report = payload["report"]
    assert report["unsupported_pitches"] == [61]
    assert report["out_of_range_pitches"] == [60]
    assert report["mapped_note_count"] == 0
    assert (tmp_path / "out" / "diagnostics.report.json").is_file()
    assert "61" in json.dumps(report, ensure_ascii=False)

    with pytest.raises(MidiToSkyError, match="半音音符"):
        convert_midi_file_to_sky(
            source,
            tmp_path / "error-out",
            key="C",
            chromatic_policy="error",
        )
