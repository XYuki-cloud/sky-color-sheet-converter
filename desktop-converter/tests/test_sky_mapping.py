from pathlib import Path

import pytest

from scripts.sky_mapping import parse_key, scale_index_for_pitch, sky_key_from_index


def test_major_scale_mapping_exposes_stable_public_functions():
    assert parse_key(" C ") == 0
    assert parse_key("Bb") == 10
    assert scale_index_for_pitch(60, "C") == 0
    assert scale_index_for_pitch(62, "C") == 1
    assert scale_index_for_pitch(61, "C") is None
    assert sky_key_from_index(0) == "A1"
    assert sky_key_from_index(14) == "C5"


def test_mapping_rejects_invalid_midi_and_sky_indices():
    with pytest.raises(ValueError, match="between 0 and 127"):
        scale_index_for_pitch(128, "C")
    with pytest.raises(ValueError, match=r"\[0, 14\]"):
        sky_key_from_index(15)


def test_canonical_midi_converter_uses_public_mapping_module():
    source = Path("scripts/midi_to_sky.py").read_text(encoding="utf-8")

    assert "from scripts.sky_mapping import" in source
