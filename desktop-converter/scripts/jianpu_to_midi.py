"""Parse timed Jianpu text and write a standards-compatible MIDI file.

This parser deliberately uses an explicit text grammar instead of depending on
the fixed-duration renderer in the vendored Sky Music Sheet Maker.  The
resulting :class:`JianpuSong` keeps real onset and duration values so the same
events can later be rendered as a numbered score, MIDI, or Sky sheet.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path
from typing import Iterable, Sequence

import mido

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.music_events import NoteEvent, midi_event_sort_key, sort_note_events


SCALE_INTERVALS = (0, 2, 4, 5, 7, 9, 11)
KEY_PITCH_CLASSES = {
    "C": 0,
    "C#": 1,
    "DB": 1,
    "D": 2,
    "D#": 3,
    "EB": 3,
    "E": 4,
    "F": 5,
    "F#": 6,
    "GB": 6,
    "G": 7,
    "G#": 8,
    "AB": 8,
    "A": 9,
    "A#": 10,
    "BB": 10,
    "B": 11,
}
CANONICAL_KEYS = {
    0: "C",
    1: "Db",
    2: "D",
    3: "Eb",
    4: "E",
    5: "F",
    6: "Gb",
    7: "G",
    8: "Ab",
    9: "A",
    10: "Bb",
    11: "B",
}
NOTE_PATTERN = re.compile(r"^(?P<acc1>[#b♯♭]?)(?P<degree>[1-7])(?P<acc2>[#b♯♭]?)(?P<octave>[+-]*)$")
DURATION_PATTERN = re.compile(r"^(?P<body>[^@]+?)(?:@(?P<duration>[0-9]+(?:\.[0-9]+)?))?$")
KEY_HEADER_PATTERN = re.compile(r"^\s*1\s*=\s*(?P<key>[A-Ga-g](?:#|b)?)\s*(?P<rest>.*)$")
TIME_PATTERN = re.compile(r"^(?P<numerator>[1-9][0-9]*)\s*/\s*(?P<denominator>[1-9][0-9]*)$")


class JianpuFormatError(ValueError):
    """Raised when Jianpu text cannot be converted without guessing."""


@dataclass(frozen=True)
class JianpuSong:
    title: str
    key: str
    bpm: float
    time_signature: str
    source_text: str
    events: tuple[NoteEvent, ...]
    warnings: tuple[str, ...] = ()


def _error(line: int, column: int, message: str) -> JianpuFormatError:
    return JianpuFormatError(f"第 {line} 行，第 {column} 列：{message}")


def _fraction(value: str) -> Fraction:
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError) as exc:
        raise ValueError(f"invalid fraction: {value}") from exc
    if result <= 0:
        raise ValueError("fraction must be positive")
    return result


def _round_fraction(value: Fraction) -> int:
    return (value.numerator * 2 + value.denominator) // (2 * value.denominator)


def normalize_key(key: str) -> str:
    normalized = key.strip().replace("♯", "#").replace("♭", "b")
    if not normalized:
        raise ValueError("key cannot be empty")
    letter = normalized[0].upper()
    suffix = normalized[1:]
    if suffix not in {"", "#", "b", "B"}:
        raise ValueError(f"unsupported key: {key}")
    candidate = letter + suffix
    pitch_class = KEY_PITCH_CLASSES.get(candidate.upper())
    if pitch_class is None:
        raise ValueError(f"unsupported key: {key}")
    if suffix in {"b", "B"}:
        return CANONICAL_KEYS[pitch_class]
    return letter + suffix


def _key_pitch_class(key: str) -> int:
    return KEY_PITCH_CLASSES[normalize_key(key).upper()]


def _note_pitch(token: str, key: str, line: int, column: int) -> int:
    normalized = token.strip().replace("♯", "#").replace("♭", "b")
    match = NOTE_PATTERN.fullmatch(normalized)
    if match is None:
        raise _error(line, column, f"无法识别音符 {token!r}")

    accidental = 0
    for symbol in (match.group("acc1"), match.group("acc2")):
        if symbol == "#":
            accidental += 1
        elif symbol == "b":
            accidental -= 1

    octave_markers = match.group("octave")
    if "+" in octave_markers and "-" in octave_markers:
        raise _error(line, column, f"八度标记不能同时包含 + 和 -：{token!r}")
    octave_shift = len(octave_markers) if "+" in octave_markers else -len(octave_markers)
    degree = int(match.group("degree")) - 1
    pitch = 60 + _key_pitch_class(key) + SCALE_INTERVALS[degree] + accidental + 12 * octave_shift
    if not 0 <= pitch <= 127:
        raise _error(line, column, f"音符 {token!r} 超出 MIDI 音域")
    return pitch


def _lex_tokens(line_text: str, line_number: int) -> list[tuple[str, int]]:
    """Split cells outside chord braces while retaining source columns."""

    tokens: list[tuple[str, int]] = []
    current: list[str] = []
    start_column = 1
    brace_depth = 0

    def flush() -> None:
        nonlocal current
        if current:
            tokens.append(("".join(current), start_column))
            current = []

    for index, char in enumerate(line_text, start=1):
        if char == "{" and brace_depth == 0:
            if not current:
                start_column = index
            brace_depth = 1
            current.append(char)
        elif char == "{" and brace_depth:
            raise _error(line_number, index, "和弦括号不能嵌套")
        elif char == "}" and brace_depth == 1:
            brace_depth = 0
            current.append(char)
        elif char == "}" and brace_depth == 0:
            raise _error(line_number, index, "出现多余的和弦右括号")
        elif brace_depth == 0 and (char.isspace() or char == "|"):
            flush()
        else:
            if not current:
                start_column = index
            current.append(char)

    if brace_depth:
        raise _error(line_number, len(line_text) + 1, "和弦缺少右括号")
    flush()
    return tokens


def _split_duration(token: str, line: int, column: int) -> tuple[str, Fraction | None]:
    match = DURATION_PATTERN.fullmatch(token)
    if match is None:
        raise _error(line, column, f"时值语法错误：{token!r}")
    duration_text = match.group("duration")
    if duration_text is None:
        return match.group("body"), None
    try:
        duration = _fraction(duration_text)
    except ValueError as exc:
        raise _error(line, column, f"时值必须是正数：{duration_text!r}") from exc
    return match.group("body"), duration


def _parse_note_list(text: str, line: int, column: int) -> list[str]:
    notes: list[str] = []
    index = 0
    while index < len(text):
        while index < len(text) and (text[index].isspace() or text[index] == ","):
            index += 1
        if index >= len(text):
            break
        match = re.match(r"[#b♯♭]?[1-7][#b♯♭]?[+-]*", text[index:])
        if match is None:
            raise _error(line, column + index, f"和弦内出现无法识别内容：{text[index:]!r}")
        notes.append(match.group(0))
        index += len(match.group(0))
    if not notes:
        raise _error(line, column, "和弦不能为空")
    return notes


def _group_notes(body: str, line: int, column: int) -> list[str] | None:
    if body == "0":
        return None
    if body.startswith("{") or body.endswith("}"):
        if not (body.startswith("{") and body.endswith("}")):
            raise _error(line, column, f"和弦括号不完整：{body!r}")
        return _parse_note_list(body[1:-1], line, column + 1)

    if NOTE_PATTERN.fullmatch(body):
        return [body]

    # Compatibility with the upstream Sky parser: an unseparated run such as
    # ``135`` represents a chord.  Sequential notes should use whitespace or
    # ``^`` explicitly.
    compact_notes = _parse_note_list(body, line, column)
    if len(compact_notes) > 1 and "".join(compact_notes) == body:
        return compact_notes
    raise _error(line, column, f"无法识别音符组：{body!r}")


def _parse_line(
    line_text: str,
    line_number: int,
    key: str,
    bpm: float,
    cursor: Fraction,
    events: list[NoteEvent],
) -> Fraction:
    tokens = _lex_tokens(line_text, line_number)
    for raw_token, column in tokens:
        body, explicit_duration = _split_duration(raw_token, line_number, column)
        groups = body.split("^")
        if any(not group for group in groups):
            raise _error(line_number, column, f"连续音符分隔符无效：{raw_token!r}")
        total_duration = explicit_duration or Fraction(1, 1)
        group_duration = total_duration / len(groups)
        for group_index, group in enumerate(groups):
            group_start = cursor + group_duration * group_index
            group_end = group_start + group_duration
            note_names = _group_notes(group, line_number, column)
            if note_names is not None:
                for note_name in note_names:
                    pitch = _note_pitch(note_name, key, line_number, column)
                    ms_per_beat = Fraction(60000, 1) / Fraction(str(bpm))
                    start_ms = _round_fraction(group_start * ms_per_beat)
                    end_ms = _round_fraction(group_end * ms_per_beat)
                    events.append(
                        NoteEvent(
                            start_ms=start_ms,
                            duration_ms=max(1, end_ms - start_ms),
                            pitch=pitch,
                            velocity=96,
                            source="jianpu",
                        )
                    )
        cursor += total_duration
    return cursor


def _read_text(path: str | Path) -> str:
    source = Path(path)
    raw = source.read_bytes()
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise JianpuFormatError(f"无法识别简谱文本编码：{source}")


def parse_jianpu(
    text: str,
    *,
    default_key: str = "C",
    default_bpm: float = 120.0,
) -> JianpuSong:
    """Parse the documented timed Jianpu grammar into note events."""

    try:
        key = normalize_key(default_key)
    except ValueError as exc:
        raise JianpuFormatError(str(exc)) from exc
    if not math.isfinite(float(default_bpm)) or float(default_bpm) <= 0:
        raise JianpuFormatError("默认 BPM 必须是正数")

    title = "未命名歌曲"
    bpm = float(default_bpm)
    time_signature = "4/4"
    cursor = Fraction(0, 1)
    events: list[NoteEvent] = []

    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.split("//", 1)[0].split(";", 1)[0].strip()
        if not line:
            continue
        if line.startswith("@"):
            match = re.fullmatch(r"@([A-Za-z_-]+)\s*=\s*(.*?)\s*", line)
            if match is None:
                raise _error(line_number, 1, f"元数据语法错误：{line!r}")
            name, value = match.groups()
            try:
                if name.lower() == "title":
                    title = value or "未命名歌曲"
                elif name.lower() == "key":
                    key = normalize_key(value)
                elif name.lower() == "bpm":
                    bpm = float(value)
                    if not math.isfinite(bpm) or bpm <= 0:
                        raise ValueError("BPM 必须是正数")
                elif name.lower() in {"time", "time_signature"}:
                    if TIME_PATTERN.fullmatch(value) is None:
                        raise ValueError("拍号必须形如 4/4")
                    time_signature = value.replace(" ", "")
                else:
                    raise ValueError(f"不支持的元数据字段：{name}")
            except ValueError as exc:
                raise _error(line_number, 1, str(exc)) from exc
            continue

        key_match = KEY_HEADER_PATTERN.fullmatch(line)
        if key_match is not None:
            try:
                key = normalize_key(key_match.group("key"))
            except ValueError as exc:
                raise _error(line_number, 1, str(exc)) from exc
            remainder = key_match.group("rest").strip()
            if remainder:
                cursor = _parse_line(remainder, line_number, key, bpm, cursor, events)
            continue

        cursor = _parse_line(line, line_number, key, bpm, cursor, events)

    return JianpuSong(
        title=title,
        key=key,
        bpm=bpm,
        time_signature=time_signature,
        source_text=text,
        events=sort_note_events(events),
    )


def parse_jianpu_file(
    path: str | Path,
    *,
    default_key: str = "C",
    default_bpm: float = 120.0,
) -> JianpuSong:
    return parse_jianpu(
        _read_text(path), default_key=default_key, default_bpm=default_bpm
    )


def _event_ticks(event: NoteEvent, *, bpm: float, ticks_per_beat: int) -> tuple[int, int]:
    ms_per_beat = Fraction(60000, 1) / Fraction(str(bpm))
    start = _round_fraction(Fraction(event.start_ms) / ms_per_beat * ticks_per_beat)
    end = _round_fraction(
        Fraction(event.start_ms + event.duration_ms) / ms_per_beat * ticks_per_beat
    )
    return start, max(start + 1, end)


def _midi_text(value: str, fallback: str) -> str:
    """Return text that can be encoded by mido's default MIDI charset."""

    try:
        value.encode("latin-1")
    except UnicodeEncodeError:
        return fallback
    return value


def write_midi(
    song: JianpuSong,
    output_path: str | Path,
    *,
    ticks_per_beat: int = 480,
) -> Path:
    """Write ``song`` as a one-track type-1 MIDI file."""

    if ticks_per_beat <= 0:
        raise ValueError("ticks_per_beat must be positive")
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    midi = mido.MidiFile(type=1, ticks_per_beat=ticks_per_beat)
    track = mido.MidiTrack()
    midi.tracks.append(track)
    track.append(
        mido.MetaMessage("track_name", name=_midi_text(song.title, "Jianpu"), time=0)
    )
    track.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(song.bpm), time=0))
    track.append(mido.MetaMessage("time_signature", numerator=int(song.time_signature.split("/")[0]), denominator=int(song.time_signature.split("/")[1]), time=0))
    track.append(mido.MetaMessage("key_signature", key=song.key, time=0))
    track.append(mido.Message("program_change", channel=0, program=0, time=0))

    absolute_events: list[tuple[int, str, int, int]] = []
    for event in song.events:
        start_tick, end_tick = _event_ticks(
            event, bpm=song.bpm, ticks_per_beat=ticks_per_beat
        )
        absolute_events.append((start_tick, "note_on", event.pitch, event.velocity))
        absolute_events.append((end_tick, "note_off", event.pitch, 0))

    absolute_events.sort(key=lambda item: midi_event_sort_key((item[0], item[1], item[2])))
    previous_tick = 0
    for tick, event_type, pitch, velocity in absolute_events:
        track.append(
            mido.Message(
                event_type,
                channel=0,
                note=pitch,
                velocity=velocity,
                time=tick - previous_tick,
            )
        )
        previous_tick = tick
    track.append(mido.MetaMessage("end_of_track", time=0))
    midi.save(output)
    return output


def _song_payload(song: JianpuSong, midi_name: str) -> dict:
    return {
        "format": "sky-note-events-v1",
        "source": {"name": song.title, "filename": ""},
        "midi": midi_name,
        "key": song.key,
        "bpm": song.bpm,
        "time_signature": song.time_signature,
        "events": [asdict(event) for event in song.events],
        "warnings": list(song.warnings),
    }


def convert_jianpu_file(
    source_path: str | Path,
    output_dir: str | Path,
    *,
    to_sky: bool = False,
    default_key: str = "C",
    default_bpm: float = 120.0,
) -> dict:
    source = Path(source_path)
    if not source.is_file():
        raise FileNotFoundError(f"找不到简谱文件：{source}")
    song = parse_jianpu_file(
        source, default_key=default_key, default_bpm=default_bpm
    )
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    midi_path = write_midi(song, output / f"{source.stem}.mid")
    payload = _song_payload(song, midi_path.name)
    payload["source"]["filename"] = source.name
    notes_path = output / f"{source.stem}.notes.json"
    report_path = output / f"{source.stem}.report.json"
    report: dict[str, object] = {
        "format": "jianpu-conversion-report-v1",
        "source": source.name,
        "key": song.key,
        "bpm": song.bpm,
        "input_event_count": len(song.events),
        "warning_count": len(song.warnings),
        "warnings": list(song.warnings),
        "artifacts": {
            "midi": midi_path.name,
            "notes": notes_path.name,
            "report": report_path.name,
        },
    }
    if to_sky:
        from scripts.midi_to_sky import convert_midi_file_to_sky

        payload["sky"] = convert_midi_file_to_sky(midi_path, output, key=song.key)
        report["sky"] = payload["sky"]["report"]
        report["artifacts"].update(payload["sky"]["artifacts"])
    payload["report"] = report
    payload["artifacts"] = report["artifacts"]
    notes_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return payload


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="将带时值的数字简谱文本转换为 MIDI。")
    parser.add_argument("input", type=Path, help="简谱文本文件")
    parser.add_argument("--out-dir", type=Path, default=Path("outputs"))
    parser.add_argument("--key", default="C", help="无元数据时的默认调性")
    parser.add_argument("--bpm", type=float, default=120.0, help="无元数据时的默认 BPM")
    parser.add_argument("--to-sky", action="store_true", help="转换 MIDI 后继续生成黑白和彩色 Sky 谱")
    args = parser.parse_args(argv)
    try:
        payload = convert_jianpu_file(
            args.input,
            args.out_dir,
            to_sky=args.to_sky,
            default_key=args.key,
            default_bpm=args.bpm,
        )
    except (OSError, JianpuFormatError, ValueError) as exc:
        parser.error(str(exc))
    print(
        f"已转换：{len(payload['events'])} 个音符 → "
        f"{payload['midi']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
