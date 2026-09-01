"""Public MIDI pitch to Sky key mapping primitives.

The Sky keyboard is represented as fifteen diatonic positions from A1 to C5.
These functions intentionally contain only the stable mapping rules so the
formal MIDI converter does not depend on archived experiment code.
"""

from __future__ import annotations

MAJOR_INTERVALS = (0, 2, 4, 5, 7, 9, 11)
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
SKY_KEY_LABELS = (
    "A1",
    "A2",
    "A3",
    "A4",
    "A5",
    "B1",
    "B2",
    "B3",
    "B4",
    "B5",
    "C1",
    "C2",
    "C3",
    "C4",
    "C5",
)
SKY_KEY_COUNT = 15


def parse_key(key: str) -> int:
    """Return the pitch class of a major-key root."""

    normalized = str(key).strip().replace("♯", "#").replace("♭", "b").upper()
    try:
        return KEY_PITCH_CLASSES[normalized]
    except KeyError as exc:
        raise ValueError(f"Unsupported key: {key!r}") from exc


def scale_index_for_pitch(pitch: int, key: str = "C") -> int | None:
    """Return the zero-based diatonic index for a MIDI pitch.

    The index is relative to C4 as index zero. Chromatic pitches return
    ``None`` so callers can apply their chosen policy.
    """

    if not 0 <= pitch <= 127:
        raise ValueError(f"MIDI pitch must be between 0 and 127: {pitch}")
    root_pitch_class = parse_key(key)
    scale_pitch_classes = [
        (root_pitch_class + interval) % 12 for interval in MAJOR_INTERVALS
    ]
    relative_pitch = pitch - 60
    midi_octave, semitone = divmod(relative_pitch, 12)
    try:
        degree = scale_pitch_classes.index((60 + semitone) % 12)
    except ValueError:
        return None
    return midi_octave * len(MAJOR_INTERVALS) + degree


def sky_key_from_index(index: int) -> str:
    """Return the Sky key label for an index in the fifteen-key range."""

    if not 0 <= index < SKY_KEY_COUNT:
        raise ValueError(f"Sky key index must be in [0, 14]: {index}")
    return SKY_KEY_LABELS[index]


__all__ = [
    "KEY_PITCH_CLASSES",
    "MAJOR_INTERVALS",
    "SKY_KEY_COUNT",
    "SKY_KEY_LABELS",
    "parse_key",
    "scale_index_for_pitch",
    "sky_key_from_index",
]
