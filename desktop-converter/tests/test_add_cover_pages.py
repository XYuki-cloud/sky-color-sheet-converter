import json
from pathlib import Path

from PIL import Image

from scripts.add_cover_pages import add_cover_pages


def test_add_cover_pages_migrates_existing_mobile_pages_and_metadata(tmp_path: Path):
    song_dir = tmp_path / "song"
    song_dir.mkdir()
    first = song_dir / "song.color-mobile-001.png"
    Image.new("RGB", (120, 100), "white").save(first)
    payload = {
        "format": "sky-color-v1",
        "source": {"name": "测试曲"},
        "artifacts": {
            "json": "song.color.json",
            "png_pages": [],
            "mobile_png_pages": [first.name],
        },
    }
    color_json = song_dir / "song.color.json"
    color_json.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    result = add_cover_pages(tmp_path)

    assert result == {"songs": 1, "covers": 1, "updated_json": 1}
    cover = song_dir / "song.color-mobile-000.png"
    assert cover.is_file()
    updated = json.loads(color_json.read_text(encoding="utf-8"))
    assert updated["artifacts"]["mobile_png_pages"] == [
        "song.color-mobile-000.png",
        "song.color-mobile-001.png",
    ]
    assert updated["artifacts"]["cover_png"] == "song.color-mobile-000.png"
