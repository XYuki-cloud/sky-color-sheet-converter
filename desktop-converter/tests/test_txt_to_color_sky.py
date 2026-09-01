import json
from pathlib import Path

import pytest
from PIL import Image

from scripts.txt_to_color_sky import (
    COLOR_NAMES,
    COVER_TITLE_COLOR,
    FRAME_COLORS,
    KEY_LABELS,
    MOBILE_FRAME_COLORS,
    MOBILE_PAGE_SIZE,
    ColorImage,
    ColorLayer,
    SkyTxtFormatError,
    SourceFrame,
    compress_source_frames,
    convert_sky_txt_file,
    format_mobile_page_number,
    parse_sky_txt,
    render_mobile_color_pages,
    render_color_pages,
    render_cover_page,
)


def _song(notes, *, name="测试曲", author="测试作者"):
    return [
        {
            "name": name,
            "author": author,
            "transcribedBy": "测试制谱者",
            "songNotes": notes,
        }
    ]


def test_utf16le_txt_groups_same_time_notes_into_a_sorted_chord(tmp_path: Path):
    source = tmp_path / "song.txt"
    source.write_bytes(
        (json.dumps(
            _song(
                [
                    {"time": 100, "key": "1Key3"},
                    {"time": 0, "key": "1Key2"},
                    {"time": 0, "key": "1Key0"},
                    {"time": 100, "key": "1Key3"},
                ]
            ),
            ensure_ascii=False,
        )).encode("utf-16")
    )

    song = parse_sky_txt(source)

    assert song.title == "测试曲"
    assert song.note_count == 4
    assert [(frame.time, frame.keys) for frame in song.frames] == [
        (0, ("A1", "A3")),
        (100, ("A4",)),
    ]
    assert any("重复" in warning for warning in song.warnings)


def test_unsupported_key_fails_without_silently_dropping_a_note(tmp_path: Path):
    source = tmp_path / "bad.txt"
    source.write_text(json.dumps(_song([{"time": 0, "key": "1Key99"}])), encoding="utf-8")

    with pytest.raises(SkyTxtFormatError, match="不支持的按键"):
        parse_sky_txt(source)


def test_layer_prefixes_share_the_same_fifteen_key_space(tmp_path: Path):
    source = tmp_path / "layered.txt"
    source.write_text(
        json.dumps(
            _song(
                [
                    {"time": 0, "key": "2Key0"},
                    {"time": 0, "key": "1Key3"},
                    {"time": 10, "key": "2Key14"},
                    {"time": 20, "key": "3Key7"},
                    {"time": 20, "key": "1Key7"},
                ]
            ),
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    song = parse_sky_txt(source)

    assert [(frame.time, frame.keys) for frame in song.frames] == [
        (0, ("A1", "A4")),
        (10, ("C5",)),
        (20, ("B3",)),
    ]
    assert any("重复" in warning and "1Key7" in warning for warning in song.warnings)


def test_compression_splits_on_any_key_overlap_and_uses_black_red_blue():
    frames = (
        SourceFrame(index=1, time=0, keys=("A1", "B2")),
        SourceFrame(index=2, time=100, keys=("C3",)),
        SourceFrame(index=3, time=200, keys=("B2",)),
        SourceFrame(index=4, time=300, keys=("A5",)),
    )

    images = compress_source_frames(frames)

    assert [[layer.keys for layer in image.layers] for image in images] == [
        [("A1", "B2"), ("C3",)],
        [("B2",), ("A5",)],
    ]
    assert [[layer.color for layer in image.layers] for image in images] == [
        ["black", "red"],
        ["black", "red"],
    ]
    assert FRAME_COLORS == ("#000000", "#FF0000", "#0000FF")


def test_compression_never_creates_a_fourth_layer():
    frames = tuple(
        SourceFrame(index=i, time=i, keys=(KEY_LABELS[i],))
        for i in range(4)
    )

    images = compress_source_frames(frames)

    assert [len(image.layers) for image in images] == [3, 1]
    assert all(len(image.layers) <= 3 for image in images)


def test_converter_writes_versioned_json_and_24_image_png_pages(tmp_path: Path):
    source = tmp_path / "sample_song.txt"
    source.write_text(
        json.dumps(
            _song(
                [
                    {"time": 0, "key": "1Key0"},
                    {"time": 1, "key": "1Key1"},
                    {"time": 2, "key": "1Key2"},
                    {"time": 3, "key": "1Key3"},
                ]
            ),
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    output_dir = tmp_path / "out"

    payload = convert_sky_txt_file(
        source,
        output_dir,
        include_desktop_pages=True,
    )

    assert payload["format"] == "sky-color-v1"
    assert payload["source_frame_count"] == 4
    assert payload["image_count"] == 2
    assert payload["colors"] == [
        {"name": "black", "hex": "#000000"},
        {"name": "red", "hex": "#FF0000"},
        {"name": "blue", "hex": "#0000FF"},
    ]
    assert (output_dir / "sample_song.color.json").is_file()
    pages = sorted(output_dir.glob("sample_song.color-[0-9][0-9][0-9].png"))
    assert [page.name for page in pages] == [
        "sample_song.color-000.png",
        "sample_song.color-001.png",
    ]
    assert payload["artifacts"]["png_pages"] == [page.name for page in pages]
    mobile_pages = payload["artifacts"]["mobile_png_pages"]
    assert mobile_pages == [
        "sample_song.color-mobile-000.png",
        "sample_song.color-mobile-001.png",
    ]
    assert payload["artifacts"]["cover_png"] == "sample_song.color-mobile-000.png"
    assert payload["artifacts"]["desktop_cover_png"] == "sample_song.color-000.png"
    assert all((output_dir / page).is_file() for page in mobile_pages)
    image = Image.open(output_dir / "sample_song.color-001.png").convert("RGB")
    pixels = set(image.get_flattened_data())
    assert (0, 0, 0) in pixels
    assert (255, 0, 0) in pixels
    assert (0, 0, 255) in pixels
    assert (255, 255, 255) in pixels
    # First card starts at (24, 86), and its 5x3 grid starts at (47, 125).
    # Sample away from the centered labels so these are cell-fill pixels.
    assert image.getpixel((52, 130)) == (0, 0, 0)  # A1 / black
    assert image.getpixel((92, 130)) == (255, 0, 0)  # A2 / red
    assert image.getpixel((132, 130)) == (0, 0, 255)  # A3 / blue
    assert image.getpixel((172, 130)) == (255, 255, 255)  # A4 / empty
    cover = Image.open(output_dir / "sample_song.color-mobile-000.png").convert("RGB")
    assert cover.size == Image.open(output_dir / "sample_song.color-mobile-001.png").size
    assert any(
        pixel == tuple(int(COVER_TITLE_COLOR[index : index + 2], 16) for index in (1, 3, 5))
        for pixel in cover.get_flattened_data()
    )


def test_converter_skips_desktop_pages_by_default(tmp_path: Path):
    source = tmp_path / "mobile_only.txt"
    source.write_text(
        json.dumps(
            _song(
                [
                    {"time": 0, "key": "1Key0"},
                    {"time": 1, "key": "1Key1"},
                ]
            ),
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    output_dir = tmp_path / "out"

    payload = convert_sky_txt_file(source, output_dir)

    assert payload["artifacts"]["png_pages"] == []
    assert not list(output_dir.glob("mobile_only.color-[0-9][0-9][0-9].png"))
    assert payload["artifacts"]["mobile_png_pages"] == [
        "mobile_only.color-mobile-000.png",
        "mobile_only.color-mobile-001.png",
    ]


def test_mobile_color_pages_use_four_columns_and_no_cell_labels(tmp_path: Path):
    images = tuple(
        ColorImage(
            index=index,
            layers=(
                (
                    ColorLayer(0, "black", "#000000", index, index, ("A1",)),
                    ColorLayer(1, "red", "#FF0000", index, index, ("A2",)),
                    ColorLayer(2, "blue", "#0000FF", index, index, ("A3",)),
                )
                if index == 1
                else (ColorLayer(0, "black", "#000000", index, index, ("A1",)),)
            ),
        )
        for index in range(1, MOBILE_PAGE_SIZE + 2)
    )

    pages = render_mobile_color_pages(images, tmp_path, stem="song", title="测试曲")

    assert len(pages) == 3
    assert pages[0].name == "song.color-mobile-000.png"
    assert pages[1].name == "song.color-mobile-001.png"
    assert pages[2].name == "song.color-mobile-002.png"
    cover = Image.open(pages[0]).convert("RGB")
    first = Image.open(pages[1]).convert("RGB")
    second = Image.open(pages[2]).convert("RGB")
    assert cover.size == first.size
    assert cover.tobytes() != first.tobytes()
    assert first.size == (600, 850)
    assert second.size == (600, 192)
    assert first.getpixel((28, 104)) == (0, 0, 0)
    assert first.getpixel((56, 104)) == (255, 0, 0)
    assert first.getpixel((84, 104)) == (34, 199, 232)
    assert first.getpixel((111, 104)) == (255, 255, 255)
    assert MOBILE_FRAME_COLORS == ("#000000", "#FF0000", "#22C7E8")


def test_mobile_color_pages_include_page_fraction_in_header(tmp_path: Path):
    images = tuple(
        ColorImage(
            index=index,
            layers=(ColorLayer(0, "black", "#000000", index, index, ("A1",)),),
        )
        for index in range(MOBILE_PAGE_SIZE + 1)
    )

    pages = render_mobile_color_pages(images, tmp_path, stem="song", title="测试曲")

    assert format_mobile_page_number(1, 2) == "1/2"
    assert format_mobile_page_number(2, 2) == "2/2"
    first = Image.open(pages[1]).convert("RGB")
    # The upper-right header is reserved for the page fraction.
    header_right = first.crop((500, 0, first.width, 90))
    assert any(pixel != (255, 255, 255) for pixel in header_right.get_flattened_data())


def test_render_color_pages_accepts_empty_image_list(tmp_path: Path):
    pages = render_color_pages(
        (),
        tmp_path,
        stem="empty",
        title="空曲谱",
    )

    assert len(pages) == 2
    assert pages[0].name == "empty.color-000.png"
    assert pages[1].name == "empty.color-001.png"
    assert all(page.is_file() for page in pages)


def test_cover_page_copies_first_page_and_adds_yellow_title(tmp_path: Path):
    first = tmp_path / "first.png"
    cover = tmp_path / "cover.png"
    Image.new("RGB", (160, 120), "white").save(first)

    result = render_cover_page(first, cover, title="花之舞")

    assert result == cover
    assert Image.open(result).size == (160, 120)
    pixels = set(Image.open(result).convert("RGB").get_flattened_data())
    expected = tuple(int(COVER_TITLE_COLOR[index : index + 2], 16) for index in (1, 3, 5))
    assert expected in pixels
