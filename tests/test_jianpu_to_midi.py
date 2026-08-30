from pathlib import Path

import mido
import pytest

from scripts.jianpu_to_midi import (
    JianpuFormatError,
    convert_jianpu_file,
    parse_jianpu,
    parse_jianpu_file,
    write_midi,
)


def test_parser_reads_metadata_accidentals_octaves_chords_and_subdivisions():
    song = parse_jianpu(
        """@title=测试曲
@key=C
@bpm=120
1 2 3 4 | 5@2 0 6+
{1,3,5}@2
1^2^3^4
#4 b7
"""
    )

    assert song.title == "测试曲"
    assert song.key == "C"
    assert song.bpm == 120
    assert [(event.start_ms, event.pitch) for event in song.events[:6]] == [
        (0, 60),
        (500, 62),
        (1000, 64),
        (1500, 65),
        (2000, 67),
        (3500, 81),
    ]
    chord = song.events[6:9]
    assert [event.pitch for event in chord] == [60, 64, 67]
    assert len({event.start_ms for event in chord}) == 1
    assert song.events[-2].pitch == 66  # #4 in C major
    assert song.events[-1].pitch == 70  # b7 in C major


def test_parser_accepts_one_equals_key_header_and_octave_suffixes():
    song = parse_jianpu("1=G\n1 2 7+ 1-\n")

    assert song.key == "G"
    # In G major, 7 is F#5 (78), while 7+ is the next octave (90).
    assert [event.pitch for event in song.events] == [67, 69, 90, 55]


def test_parser_rejects_unknown_tokens_with_line_and_column():
    with pytest.raises(JianpuFormatError, match=r"第 1 行，第 3 列"):
        parse_jianpu("1 9 2")


def test_writer_preserves_chord_starts_and_rest_time(tmp_path: Path):
    song = parse_jianpu("@bpm=120\n{1,3,5} 0 2\n")
    output = tmp_path / "song.mid"

    write_midi(song, output)
    midi = mido.MidiFile(output)
    events = []
    absolute = 0
    for message in midi.tracks[0]:
        absolute += message.time
        if message.type in {"note_on", "note_off"} and message.velocity:
            events.append((absolute, message.type, message.note))

    on_events = [event for event in events if event[1] == "note_on"]
    assert [note for _, _, note in on_events] == [60, 64, 67, 62]
    assert len({tick for tick, _, _ in on_events[:3]}) == 1
    assert on_events[-1][0] > on_events[0][0]
    assert on_events[-1][0] >= 960  # one-beat chord + one-beat rest


def test_file_conversion_writes_an_input_event_report(tmp_path: Path):
    source = tmp_path / "simple.jianpu.txt"
    source.write_text("@title=报告测试\n1 2 3\n", encoding="utf-8")

    payload = convert_jianpu_file(source, tmp_path / "out")

    report_path = tmp_path / "out" / "simple.jianpu.report.json"
    assert report_path.is_file()
    assert payload["report"]["input_event_count"] == 3


def test_file_parser_uses_gui_defaults_when_metadata_is_absent(tmp_path: Path):
    source = tmp_path / "defaults.txt"
    source.write_text("1 2\n", encoding="utf-8")

    song = parse_jianpu_file(source, default_key="G", default_bpm=90)

    assert song.key == "G"
    assert song.bpm == 90
