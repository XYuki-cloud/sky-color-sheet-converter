import pytest

from scripts.music_events import NoteEvent, midi_event_sort_key, sort_note_events, validate_note_event


def test_note_event_accepts_valid_timed_midi_note():
    event = NoteEvent(start_ms=0, duration_ms=250, pitch=60, velocity=80)

    assert validate_note_event(event) == event


@pytest.mark.parametrize(
    "kwargs",
    [
        {"start_ms": -1, "duration_ms": 250, "pitch": 60},
        {"start_ms": 0, "duration_ms": 0, "pitch": 60},
        {"start_ms": 0, "duration_ms": 250, "pitch": -1},
        {"start_ms": 0, "duration_ms": 250, "pitch": 128},
    ],
)
def test_note_event_rejects_invalid_values(kwargs):
    with pytest.raises(ValueError):
        NoteEvent(**kwargs)


def test_sort_note_events_uses_onset_then_pitch():
    events = (
        NoteEvent(start_ms=100, duration_ms=100, pitch=64),
        NoteEvent(start_ms=0, duration_ms=100, pitch=67),
        NoteEvent(start_ms=0, duration_ms=100, pitch=60),
    )

    assert [(event.start_ms, event.pitch) for event in sort_note_events(events)] == [
        (0, 60),
        (0, 67),
        (100, 64),
    ]


def test_midi_sort_key_places_note_off_before_note_on_at_same_tick():
    assert midi_event_sort_key((480, "note_off", 60)) < midi_event_sort_key(
        (480, "note_on", 60)
    )
