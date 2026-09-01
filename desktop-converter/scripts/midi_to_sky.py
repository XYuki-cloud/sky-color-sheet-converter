"""Convert MIDI note events into black/white and overlap-safe colour Sky sheets.

MIDI is read as the timed interchange format.  The two Sky payloads deliberately
drop playback timing from their visual sequence, but keep each source onset in
``source_time`` and in the report so the static player can offer an optional
time-based mode and diagnostics can explain every discarded note.
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

import mido

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.sky_mapping import parse_key, scale_index_for_pitch, sky_key_from_index
from scripts.music_events import NoteEvent, sort_note_events
from scripts.txt_to_color_sky import (
    COLOR_NAMES,
    FRAME_COLORS,
    KEY_LABELS,
    ColorImage,
    ColorLayer,
    ParsedSkySong,
    SourceFrame,
    build_black_payload,
    build_color_payload,
    compress_source_frames,
    render_black_pages,
    render_color_pages,
    render_mobile_color_pages,
)


SKY_KEY_COUNT = len(KEY_LABELS)
DEFAULT_TEMPO = 500000  # microseconds per beat, i.e. 120 BPM
CHROMATIC_POLICIES = ("error", "drop", "nearest")


class MidiToSkyError(ValueError):
    """Raised when conversion would otherwise lose a MIDI note silently."""


@dataclass(frozen=True)
class MidiNote:
    """One MIDI note with its original tick positions and canonical event."""

    start_tick: int
    end_tick: int
    track: int
    channel: int
    event: NoteEvent


@dataclass(frozen=True)
class MidiReadResult:
    notes: tuple[MidiNote, ...]
    ticks_per_beat: int
    warnings: tuple[str, ...] = ()

    @property
    def events(self) -> tuple[NoteEvent, ...]:
        return tuple(note.event for note in self.notes)


@dataclass(frozen=True)
class _TempoSegment:
    start_tick: int
    start_ms: float
    tempo: int


def _round_ms(value: float) -> int:
    return int(math.floor(value + 0.5))


def _tempo_segments(midi: mido.MidiFile) -> tuple[_TempoSegment, ...]:
    changes: list[tuple[int, int, int, int]] = []
    for track_index, track in enumerate(midi.tracks):
        absolute_tick = 0
        for message_index, message in enumerate(track):
            absolute_tick += int(message.time)
            if message.type == "set_tempo":
                changes.append(
                    (absolute_tick, track_index, message_index, int(message.tempo))
                )

    segments: list[_TempoSegment] = [_TempoSegment(0, 0.0, DEFAULT_TEMPO)]
    current_tick = 0
    current_ms = 0.0
    current_tempo = DEFAULT_TEMPO
    for tick, _track_index, _message_index, tempo in sorted(changes):
        if tick == current_tick:
            current_tempo = tempo
            segments[-1] = _TempoSegment(current_tick, current_ms, current_tempo)
            continue
        current_ms += (
            (tick - current_tick) * current_tempo / midi.ticks_per_beat / 1000.0
        )
        current_tick = tick
        current_tempo = tempo
        segments.append(_TempoSegment(current_tick, current_ms, current_tempo))
    return tuple(segments)


def _tick_to_ms(
    tick: int,
    ticks_per_beat: int,
    segments: Sequence[_TempoSegment],
) -> int:
    starts = [segment.start_tick for segment in segments]
    segment = segments[max(0, bisect.bisect_right(starts, tick) - 1)]
    milliseconds = segment.start_ms + (
        (tick - segment.start_tick) * segment.tempo / ticks_per_beat / 1000.0
    )
    return _round_ms(milliseconds)


def read_midi_events(path: str | Path) -> MidiReadResult:
    """Read note-on/off pairs from all MIDI tracks with a tempo-aware clock."""

    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(f"找不到 MIDI 文件：{source}")
    try:
        midi = mido.MidiFile(source)
    except (OSError, EOFError, ValueError) as exc:
        raise MidiToSkyError(f"无法读取 MIDI 文件：{source}：{exc}") from exc
    if midi.ticks_per_beat <= 0:
        raise MidiToSkyError("MIDI 的 ticks_per_beat 必须是正数")

    segments = _tempo_segments(midi)
    raw_notes: list[tuple[int, int, int, int, int, int]] = []
    warnings: list[str] = []
    for track_index, track in enumerate(midi.tracks):
        absolute_tick = 0
        track_end_tick = 0
        active: dict[tuple[int, int], list[tuple[int, int]]] = {}
        for message_index, message in enumerate(track):
            absolute_tick += int(message.time)
            track_end_tick = absolute_tick
            if message.type == "note_on" and message.velocity > 0:
                active.setdefault((message.channel, message.note), []).append(
                    (absolute_tick, int(message.velocity))
                )
                continue
            if message.type not in {"note_off", "note_on"}:
                continue
            if message.type == "note_on" and message.velocity > 0:
                continue
            note_key = (message.channel, message.note)
            starts = active.get(note_key)
            if not starts:
                warnings.append(
                    f"轨道 {track_index} 第 {message_index + 1} 条消息出现未匹配的 note_off："
                    f"音高 {message.note}"
                )
                continue
            start_tick, velocity = starts.pop(0)
            raw_notes.append(
                (
                    start_tick,
                    max(start_tick + 1, absolute_tick),
                    message.note,
                    velocity,
                    track_index,
                    message.channel,
                )
            )

        for (channel, pitch), starts in active.items():
            for start_tick, velocity in starts:
                end_tick = max(track_end_tick, start_tick + midi.ticks_per_beat)
                warnings.append(
                    f"轨道 {track_index} 的音符 {pitch} 缺少 note_off，已按一拍补齐"
                )
                raw_notes.append(
                    (start_tick, end_tick, pitch, velocity, track_index, channel)
                )

    notes: list[MidiNote] = []
    for start_tick, end_tick, pitch, velocity, track_index, channel in sorted(
        raw_notes, key=lambda item: (item[0], item[4], item[5], item[2], item[1])
    ):
        start_ms = _tick_to_ms(start_tick, midi.ticks_per_beat, segments)
        end_ms = _tick_to_ms(end_tick, midi.ticks_per_beat, segments)
        event = NoteEvent(
            start_ms=start_ms,
            duration_ms=max(1, end_ms - start_ms),
            pitch=int(pitch),
            velocity=int(velocity),
            source="midi",
        )
        notes.append(
            MidiNote(
                start_tick=start_tick,
                end_tick=end_tick,
                track=track_index,
                channel=channel,
                event=event,
            )
        )
    return MidiReadResult(
        notes=tuple(notes),
        ticks_per_beat=midi.ticks_per_beat,
        warnings=tuple(warnings),
    )


def read_midi_note_events(path: str | Path) -> tuple[tuple[NoteEvent, ...], int]:
    """Compatibility helper returning canonical events and the MIDI PPQ."""

    result = read_midi_events(path)
    return result.events, result.ticks_per_beat


def _quantize_tick(tick: int, ticks_per_beat: int, subdivisions: int) -> int:
    return math.floor(tick * subdivisions / ticks_per_beat + 0.5)


def _nearest_scale_pitch(pitch: int, key: str) -> int:
    candidates = [
        candidate
        for candidate in range(128)
        if scale_index_for_pitch(candidate, key) is not None
    ]
    return min(candidates, key=lambda candidate: (abs(candidate - pitch), candidate))


def _sort_sky_keys(keys: set[str]) -> tuple[str, ...]:
    order = {key: index for index, key in enumerate(KEY_LABELS)}
    return tuple(sorted(keys, key=order.__getitem__))


def _mapped_frames(
    read_result: MidiReadResult,
    *,
    key: str,
    subdivisions: int,
    shift: int | None,
    chromatic_policy: str,
) -> tuple[
    tuple[SourceFrame, ...],
    dict[str, Any],
]:
    if subdivisions <= 0:
        raise ValueError("subdivisions 必须是正整数")
    if chromatic_policy not in CHROMATIC_POLICIES:
        choices = ", ".join(CHROMATIC_POLICIES)
        raise ValueError(f"chromatic_policy 必须是 {choices} 之一")
    parse_key(key)  # validate before producing any output

    warnings = list(read_result.warnings)
    unsupported: set[int] = set()
    nearest_adjustments: list[dict[str, int]] = []
    mapped: list[tuple[MidiNote, int]] = []
    for midi_note in read_result.notes:
        pitch = midi_note.event.pitch
        scale_index = scale_index_for_pitch(pitch, key)
        if scale_index is None:
            unsupported.add(pitch)
            if chromatic_policy == "error":
                raise MidiToSkyError(
                    f"半音音符 {pitch} 不属于 {key} 大调；"
                    "请使用 --chromatic-policy drop 或 nearest"
                )
            if chromatic_policy == "drop":
                warnings.append(
                    f"已丢弃不在 {key} 大调内的半音音符：pitch={pitch}，"
                    f"time={midi_note.event.start_ms}ms"
                )
                continue
            nearest_pitch = _nearest_scale_pitch(pitch, key)
            nearest_index = scale_index_for_pitch(nearest_pitch, key)
            assert nearest_index is not None
            scale_index = nearest_index
            nearest_adjustments.append(
                {
                    "from_pitch": pitch,
                    "to_pitch": nearest_pitch,
                    "source_time": midi_note.event.start_ms,
                }
            )
            warnings.append(
                f"半音音符 pitch={pitch} 已就近映射到 pitch={nearest_pitch}"
            )
        mapped.append((midi_note, scale_index))

    scale_indexes = [scale_index for _, scale_index in mapped]
    if shift is None:
        auto_shift = -min(scale_indexes, default=0)
        if scale_indexes and max(scale_indexes) - min(scale_indexes) >= SKY_KEY_COUNT:
            warnings.append(
                "映射后的音域跨度超过 15 个 Sky 按键，已优先保留最低音；"
                "超出范围的音符会列入 out_of_range_pitches"
            )
        scale_shift = auto_shift
    else:
        if not isinstance(shift, int):
            raise ValueError("shift 必须是整数")
        scale_shift = shift

    frame_keys: dict[int, set[str]] = {}
    frame_times: dict[int, int] = {}
    out_of_range: set[int] = set()
    out_of_range_note_count = 0
    mapped_note_count = 0
    for midi_note, scale_index in mapped:
        shifted_index = scale_index + scale_shift
        if not 0 <= shifted_index < SKY_KEY_COUNT:
            out_of_range.add(midi_note.event.pitch)
            out_of_range_note_count += 1
            warnings.append(
                f"音符 pitch={midi_note.event.pitch} 超出 A1-C5："
                f"time={midi_note.event.start_ms}ms，未写入 Sky 帧"
            )
            continue
        slot = _quantize_tick(
            midi_note.start_tick, read_result.ticks_per_beat, subdivisions
        )
        frame_keys.setdefault(slot, set()).add(sky_key_from_index(shifted_index))
        frame_times[slot] = min(
            frame_times.get(slot, midi_note.event.start_ms), midi_note.event.start_ms
        )
        mapped_note_count += 1

    frames = tuple(
        SourceFrame(index=index, time=frame_times[slot], keys=_sort_sky_keys(frame_keys[slot]))
        for index, slot in enumerate(sorted(frame_keys), start=1)
    )
    report = {
        "ticks_per_beat": read_result.ticks_per_beat,
        "subdivisions_per_beat": subdivisions,
        "key": key,
        "scale_shift": scale_shift,
        "chromatic_policy": chromatic_policy,
        "input_note_count": len(read_result.notes),
        "mapped_note_count": mapped_note_count,
        "unsupported_pitches": sorted(unsupported),
        "nearest_adjustments": nearest_adjustments,
        "out_of_range_pitches": sorted(out_of_range),
        "out_of_range_note_count": out_of_range_note_count,
        "source_frame_count": len(frames),
        "warnings": warnings,
    }
    return frames, report


def _black_images(frames: Sequence[SourceFrame]) -> tuple[ColorImage, ...]:
    return tuple(
        ColorImage(
            index=index,
            layers=(
                ColorLayer(
                    index=0,
                    color="black",
                    hex="#000000",
                    source_frame_index=frame.index,
                    source_time=frame.time,
                    keys=frame.keys,
                ),
            ),
        )
        for index, frame in enumerate(frames, start=1)
    )


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def convert_midi_file_to_sky(
    midi_path: str | Path,
    output_dir: str | Path,
    *,
    key: str = "C",
    subdivisions: int = 4,
    shift: int | None = None,
    chromatic_policy: str = "drop",
    title: str | None = None,
    include_desktop_pages: bool = False,
) -> dict[str, Any]:
    """Write MIDI sidecars and mobile Sky pages.

    Desktop-oriented 6x4 pages are opt-in because the mobile pages are the
    primary shareable output.  Set ``include_desktop_pages`` when those pages
    are explicitly needed.
    """

    source = Path(midi_path)
    read_result = read_midi_events(source)
    frames, report = _mapped_frames(
        read_result,
        key=key,
        subdivisions=subdivisions,
        shift=shift,
        chromatic_policy=chromatic_policy,
    )
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    score_title = title or source.stem
    song = ParsedSkySong(
        title=score_title,
        author="",
        transcribed_by="midi_to_sky",
        note_count=len(read_result.notes),
        frames=frames,
        warnings=tuple(report["warnings"]),
    )
    black_images = _black_images(frames)
    color_images = compress_source_frames(frames)
    black_payload = build_black_payload(song, black_images, source_filename=source.name)
    color_payload = build_color_payload(song, color_images, source_filename=source.name)
    black_payload["key"] = key
    color_payload["key"] = key

    stem = source.stem
    black_json_path = output / f"{stem}.sky.json"
    color_json_path = output / f"{stem}.color.json"
    report_path = output / f"{stem}.report.json"
    notes_path = output / f"{stem}.notes.json"
    black_pages = (
        render_black_pages(black_images, output, stem=stem, title=score_title)
        if include_desktop_pages
        else ()
    )
    color_pages = (
        render_color_pages(color_images, output, stem=stem, title=score_title)
        if include_desktop_pages
        else ()
    )
    mobile_color_pages = render_mobile_color_pages(
        color_images, output, stem=stem, title=score_title
    )

    black_payload["artifacts"] = {
        "json": black_json_path.name,
        "png_pages": [path.name for path in black_pages],
        "cover_png": black_pages[0].name if black_pages else None,
    }
    color_payload["artifacts"] = {
        "json": color_json_path.name,
        "png_pages": [path.name for path in color_pages],
        "mobile_png_pages": [path.name for path in mobile_color_pages],
        "cover_png": (
            mobile_color_pages[0].name if mobile_color_pages else None
        ),
        "desktop_cover_png": color_pages[0].name if color_pages else None,
    }
    report = {
        **report,
        "source": source.name,
        "title": score_title,
        "black_image_count": len(black_images),
        "color_image_count": len(color_images),
        "artifacts": {
            "midi": source.name,
            "notes": notes_path.name,
            "black_json": black_json_path.name,
            "black_png_pages": [path.name for path in black_pages],
            "black_cover_png": black_pages[0].name if black_pages else None,
            "color_json": color_json_path.name,
            "color_png_pages": [path.name for path in color_pages],
            "color_mobile_png_pages": [path.name for path in mobile_color_pages],
            "color_cover_png": color_pages[0].name if color_pages else None,
            "color_mobile_cover_png": (
                mobile_color_pages[0].name if mobile_color_pages else None
            ),
        },
    }
    notes_payload = {
        "format": "sky-note-events-v1",
        "source": {"filename": source.name, "name": score_title},
        "midi": source.name,
        "events": [asdict(event) for event in sort_note_events(read_result.events)],
        "warnings": list(read_result.warnings),
    }
    _write_json(black_json_path, black_payload)
    _write_json(color_json_path, color_payload)
    _write_json(report_path, report)
    _write_json(notes_path, notes_payload)
    return {
        "black": black_payload,
        "color": color_payload,
        "report": report,
        "artifacts": report["artifacts"],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="将 MIDI 转换为黑白 Sky 谱和彩色 Sky 谱。"
    )
    parser.add_argument("midi", type=Path, help="输入 .mid/.midi 文件")
    parser.add_argument("--out-dir", type=Path, default=Path("outputs"))
    parser.add_argument("--key", default="C", help="大调主音，例如 C、F、Bb")
    parser.add_argument("--subdivisions", type=int, default=4, help="每拍量化格数")
    parser.add_argument("--shift", type=int, default=None, help="按音阶级数平移")
    parser.add_argument(
        "--chromatic-policy",
        choices=CHROMATIC_POLICIES,
        default="drop",
        help="半音处理：error 停止、drop 丢弃并报告、nearest 就近映射",
    )
    parser.add_argument("--title", default=None, help="覆盖输出谱面标题")
    parser.add_argument(
        "--desktop-pages",
        action="store_true",
        help="额外生成横版 6×4 PNG（默认不生成）",
    )
    args = parser.parse_args(argv)
    try:
        payload = convert_midi_file_to_sky(
            args.midi,
            args.out_dir,
            key=args.key,
            subdivisions=args.subdivisions,
            shift=args.shift,
            chromatic_policy=args.chromatic_policy,
            title=args.title,
            include_desktop_pages=args.desktop_pages,
        )
    except (OSError, MidiToSkyError, ValueError) as exc:
        parser.error(str(exc))
    report = payload["report"]
    print(
        f"已转换：输入 {report['input_note_count']} 音符，"
        f"映射 {report['mapped_note_count']}，"
        f"黑白图 {report['black_image_count']}，彩色图 {report['color_image_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
