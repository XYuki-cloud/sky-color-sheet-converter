"""Shared timed note-event primitives used by all conversion paths.

The project keeps timing and pitch in this small, dependency-free model before
writing MIDI or rendering either Sky sheet.  Parsers and model adapters should
validate at this boundary instead of silently clipping bad notes.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable


def _require_int(name: str, value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


@dataclass(frozen=True)
class NoteEvent:
    """One timed MIDI-compatible musical note."""

    start_ms: int
    duration_ms: int
    pitch: int
    velocity: int = 64
    confidence: float | None = None
    source: str = ""

    def __post_init__(self) -> None:
        validate_note_event(self)


def validate_note_event(event: NoteEvent) -> NoteEvent:
    """Validate and return ``event`` without changing any value."""

    start_ms = _require_int("start_ms", event.start_ms)
    duration_ms = _require_int("duration_ms", event.duration_ms)
    pitch = _require_int("pitch", event.pitch)
    velocity = _require_int("velocity", event.velocity)
    if start_ms < 0:
        raise ValueError("start_ms must be non-negative")
    if duration_ms <= 0:
        raise ValueError("duration_ms must be positive")
    if not 0 <= pitch <= 127:
        raise ValueError("pitch must be between 0 and 127")
    if not 0 <= velocity <= 127:
        raise ValueError("velocity must be between 0 and 127")
    if event.confidence is not None:
        if isinstance(event.confidence, bool) or not isinstance(
            event.confidence, (int, float)
        ):
            raise ValueError("confidence must be a number or None")
        if not math.isfinite(float(event.confidence)) or not 0 <= float(event.confidence) <= 1:
            raise ValueError("confidence must be between 0 and 1")
    if not isinstance(event.source, str):
        raise ValueError("source must be a string")
    return event


def sort_note_events(events: Iterable[NoteEvent]) -> tuple[NoteEvent, ...]:
    """Validate and deterministically order events by onset and pitch."""

    validated = [validate_note_event(event) for event in events]
    return tuple(
        sorted(
            validated,
            key=lambda event: (
                event.start_ms,
                event.pitch,
                event.duration_ms,
                event.velocity,
                event.source,
            ),
        )
    )


def midi_event_sort_key(event: tuple[int, str, int]) -> tuple[int, int, int]:
    """Sort absolute MIDI events, closing notes before opening new ones."""

    tick, event_type, pitch = event
    order = {"note_off": 0, "note_on": 1}
    return (int(tick), order.get(event_type, 2), int(pitch))
