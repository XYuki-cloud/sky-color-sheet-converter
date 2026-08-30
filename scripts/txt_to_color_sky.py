"""Convert SkyStudio/画世界 JSON TXT songs into overlap-safe colour sheets.

The source TXT stores notes as ``{"time": ..., "key": "1KeyN"}`` objects.
This converter groups notes with the same time into a chord and then packs
successive non-overlapping source frames into at most three colour layers:
black, red, and blue.  Source timing is retained only as diagnostic metadata;
the generated colour sheet is intentionally not a timed playback format.
"""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from PIL import Image, ImageDraw, ImageFont


SKY_ROWS = 3
SKY_COLUMNS = 5
SKY_KEY_COUNT = SKY_ROWS * SKY_COLUMNS
KEY_LABELS = tuple(
    f"{row}{column}"
    for row in ("A", "B", "C")
    for column in range(1, SKY_COLUMNS + 1)
)
RAW_KEY_PATTERN = re.compile(r"^(?:\d+)?Key(\d+)$", re.IGNORECASE)
FRAME_COLORS = ("#000000", "#FF0000", "#0000FF")
COLOR_NAMES = ("black", "red", "blue")
MAX_LAYERS = 3
PAGE_SIZE = 24
PAGE_COLUMNS = 6
PAGE_ROWS = 4
MOBILE_FRAME_COLORS = ("#000000", "#FF0000", "#22C7E8")
MOBILE_PAGE_SIZE = 32
MOBILE_PAGE_COLUMNS = 4
MOBILE_CARD_WIDTH = 138
MOBILE_CARD_HEIGHT = 84
MOBILE_GAP_X = 6
MOBILE_GAP_Y = 10
MOBILE_MARGIN_X = 15
MOBILE_HEADER_HEIGHT = 90
MOBILE_BOTTOM_MARGIN = 18
MOBILE_TITLE_COLOR = "#8A7BE4"
MOBILE_PAGE_NUMBER_COLOR = "#6B7280"
MOBILE_GRID_COLOR = "#A7ADB3"


class SkyTxtFormatError(ValueError):
    """Raised when a source TXT cannot be converted without losing notes."""


@dataclass(frozen=True)
class SourceFrame:
    """One non-empty source frame after same-time notes are grouped."""

    index: int
    time: int
    keys: tuple[str, ...]


@dataclass(frozen=True)
class ParsedSkySong:
    """The selected song and its normalized source frames."""

    title: str
    author: str
    transcribed_by: str
    note_count: int
    frames: tuple[SourceFrame, ...]
    warnings: tuple[str, ...] = ()


@dataclass(frozen=True)
class ColorLayer:
    """One coloured layer inside a logical colour image."""

    index: int
    color: str
    hex: str
    source_frame_index: int
    source_time: int
    keys: tuple[str, ...]


@dataclass(frozen=True)
class ColorImage:
    """One logical image containing one to three sequential layers."""

    index: int
    layers: tuple[ColorLayer, ...]


def _decode_source_bytes(raw: bytes, source: Path) -> str:
    """Decode the encodings used by exported SkyStudio/画世界 TXT files."""

    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        try:
            return raw.decode("utf-16")
        except UnicodeDecodeError as exc:
            raise SkyTxtFormatError(f"无法解码 UTF-16 文件：{source}") from exc

    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        try:
            return raw.decode("utf-16")
        except UnicodeDecodeError as exc:
            raise SkyTxtFormatError(
                f"无法识别文件编码：{source}；只支持 UTF-16LE 或 UTF-8"
            ) from exc


def _load_json(path: str | Path) -> Any:
    source = Path(path)
    if not source.is_file():
        raise SkyTxtFormatError(f"找不到输入文件：{source}")
    try:
        text = _decode_source_bytes(source.read_bytes(), source)
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise SkyTxtFormatError(
            f"JSON 格式错误：第 {exc.lineno} 行，第 {exc.colno} 列：{exc.msg}"
        ) from exc


def _song_objects(payload: Any) -> list[Mapping[str, Any]]:
    if isinstance(payload, Mapping):
        candidates: Any = payload.get("songs", [payload])
    elif isinstance(payload, list):
        candidates = payload
    else:
        raise SkyTxtFormatError("JSON 顶层必须是歌曲对象或歌曲对象数组")

    if not isinstance(candidates, list) or not candidates:
        raise SkyTxtFormatError("JSON 中没有可选择的歌曲")
    if not all(isinstance(song, Mapping) for song in candidates):
        raise SkyTxtFormatError("歌曲列表中的每一项都必须是对象")
    return list(candidates)


def _as_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value)


def _sort_keys(keys: Iterable[str]) -> tuple[str, ...]:
    order = {key: index for index, key in enumerate(KEY_LABELS)}
    normalized = set(keys)
    unknown = normalized.difference(order)
    if unknown:
        raise SkyTxtFormatError(f"内部出现不支持的 Sky 按键：{sorted(unknown)}")
    return tuple(sorted(normalized, key=order.__getitem__))


def _raw_key_to_label(raw_key: str) -> str | None:
    """Map KeyN with an optional numeric layer prefix to an ABC1-5 label."""

    match = RAW_KEY_PATTERN.fullmatch(raw_key)
    if not match:
        return None
    index = int(match.group(1))
    if not 0 <= index < len(KEY_LABELS):
        return None
    return KEY_LABELS[index]


def parse_sky_payload(payload: Any, *, song_index: int = 0) -> ParsedSkySong:
    """Parse a decoded Sky JSON payload into grouped source frames."""

    songs = _song_objects(payload)
    if not 0 <= song_index < len(songs):
        raise SkyTxtFormatError(
            f"歌曲序号越界：{song_index}；文件中共有 {len(songs)} 首歌曲"
        )
    song = songs[song_index]
    notes = song.get("songNotes")
    if not isinstance(notes, list):
        raise SkyTxtFormatError("歌曲缺少有效的 songNotes 数组")

    grouped: dict[int, set[str]] = {}
    seen: set[tuple[int, str]] = set()
    warnings: list[str] = []
    for note_index, note in enumerate(notes, start=1):
        if not isinstance(note, Mapping):
            raise SkyTxtFormatError(f"第 {note_index} 个音符不是对象")
        time = note.get("time")
        if isinstance(time, bool) or not isinstance(time, int) or time < 0:
            raise SkyTxtFormatError(
                f"第 {note_index} 个音符的 time 无效：必须是非负整数"
            )
        raw_key = note.get("key")
        if not isinstance(raw_key, str) or not raw_key.strip():
            raise SkyTxtFormatError(f"第 {note_index} 个音符缺少有效的 key")
        raw_key = raw_key.strip()
        label = _raw_key_to_label(raw_key)
        if label is None:
            raise SkyTxtFormatError(
                f"第 {note_index} 个音符使用不支持的按键：{raw_key}；"
                "支持带可选数字层前缀的 Key0 到 Key14，例如 1Key0、2Key0"
            )
        duplicate_key = (time, label)
        if duplicate_key in seen:
            warnings.append(
                f"第 {note_index} 个音符在 time={time} 重复按键 {raw_key}，已去重"
            )
            continue
        seen.add(duplicate_key)
        grouped.setdefault(time, set()).add(label)

    frames = tuple(
        SourceFrame(index=index, time=time, keys=_sort_keys(keys))
        for index, (time, keys) in enumerate(sorted(grouped.items()), start=1)
    )
    return ParsedSkySong(
        title=_as_text(song.get("name") or song.get("title")) or "未命名歌曲",
        author=_as_text(song.get("author")),
        transcribed_by=_as_text(song.get("transcribedBy")),
        note_count=len(notes),
        frames=frames,
        warnings=tuple(warnings),
    )


def parse_sky_txt(path: str | Path, *, song_index: int = 0) -> ParsedSkySong:
    """Read and parse a SkyStudio/画世界 JSON TXT file."""

    return parse_sky_payload(_load_json(path), song_index=song_index)


def compress_source_frames(
    frames: Sequence[SourceFrame], *, max_layers: int = MAX_LAYERS
) -> tuple[ColorImage, ...]:
    """Pack sequential frames only when all keys remain pairwise disjoint."""

    if not 1 <= max_layers <= len(FRAME_COLORS):
        raise ValueError(f"max_layers 必须在 1 到 {len(FRAME_COLORS)} 之间")

    images: list[ColorImage] = []
    current: list[ColorLayer] = []
    used_keys: set[str] = set()

    def flush() -> None:
        if current:
            images.append(ColorImage(index=len(images) + 1, layers=tuple(current)))

    for frame in frames:
        keys = _sort_keys(frame.keys)
        if not keys:
            continue
        conflicts = bool(used_keys.intersection(keys))
        if current and (len(current) >= max_layers or conflicts):
            flush()
            current.clear()
            used_keys.clear()
        layer_index = len(current)
        current.append(
            ColorLayer(
                index=layer_index,
                color=COLOR_NAMES[layer_index],
                hex=FRAME_COLORS[layer_index],
                source_frame_index=frame.index,
                source_time=frame.time,
                keys=keys,
            )
        )
        used_keys.update(keys)
    flush()
    return tuple(images)


def _layer_dict(layer: ColorLayer) -> dict[str, Any]:
    return {
        "index": layer.index,
        "color": layer.color,
        "hex": layer.hex,
        "source_frame_index": layer.source_frame_index,
        "source_time": layer.source_time,
        "keys": list(layer.keys),
    }


def build_color_payload(
    song: ParsedSkySong,
    images: Sequence[ColorImage],
    *,
    source_filename: str,
) -> dict[str, Any]:
    """Create the versioned, timing-independent player data structure."""

    return {
        "format": "sky-color-v1",
        "mode": "color",
        "source": {
            "filename": source_filename,
            "name": song.title,
            "author": song.author,
            "transcribed_by": song.transcribed_by,
        },
        "key_order": list(KEY_LABELS),
        "colors": [
            {"name": name, "hex": color}
            for name, color in zip(COLOR_NAMES, FRAME_COLORS)
        ],
        "source_note_count": song.note_count,
        "source_frame_count": len(song.frames),
        "image_count": len(images),
        "warnings": list(song.warnings),
        "images": [
            {"index": image.index, "layers": [_layer_dict(layer) for layer in image.layers]}
            for image in images
        ],
    }


def build_black_payload(
    song: ParsedSkySong,
    images: Sequence[ColorImage],
    *,
    source_filename: str,
) -> dict[str, Any]:
    """Create the timing-independent black/white Sky score payload."""

    for image in images:
        if len(image.layers) != 1 or image.layers[0].color != "black":
            raise ValueError("黑白谱的每张逻辑图必须且只能包含一个黑色层")
    return {
        "format": "sky-black-v1",
        "mode": "black-white",
        "source": {
            "filename": source_filename,
            "name": song.title,
            "author": song.author,
            "transcribed_by": song.transcribed_by,
        },
        "key_order": list(KEY_LABELS),
        "colors": [{"name": "black", "hex": "#000000"}],
        "source_note_count": song.note_count,
        "source_frame_count": len(song.frames),
        "image_count": len(images),
        "warnings": list(song.warnings),
        "images": [
            {"index": image.index, "layers": [_layer_dict(layer) for layer in image.layers]}
            for image in images
        ],
    }


def _font(size: int) -> ImageFont.ImageFont:
    candidates = (
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\msyhbd.ttc"),
        Path(r"C:\Windows\Fonts\segoeui.ttf"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    )
    for candidate in candidates:
        if candidate.is_file():
            try:
                return ImageFont.truetype(str(candidate), size=size)
            except OSError:
                continue
    return ImageFont.load_default()


def _title_font(size: int) -> ImageFont.ImageFont:
    candidates = (
        Path(r"C:\Windows\Fonts\STXINGKA.TTF"),
        Path(r"C:\Windows\Fonts\SIMLI.TTF"),
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\segoeui.ttf"),
    )
    for candidate in candidates:
        if candidate.is_file():
            try:
                return ImageFont.truetype(str(candidate), size=size)
            except OSError:
                continue
    return ImageFont.load_default()


def _text_center(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, font, fill) -> None:
    left, top, right, bottom = box
    bounds = draw.textbbox((0, 0), text, font=font)
    text_width = bounds[2] - bounds[0]
    text_height = bounds[3] - bounds[1]
    draw.text(
        ((left + right - text_width) / 2, (top + bottom - text_height) / 2 - bounds[1]),
        text,
        font=font,
        fill=fill,
    )


def format_mobile_page_number(page_number: int, page_count: int) -> str:
    """Return the compact page fraction printed on mobile colour pages."""

    if page_count < 1 or not 1 <= page_number <= page_count:
        raise ValueError(
            f"页码必须满足 1 <= page_number <= page_count，收到 {page_number}/{page_count}"
        )
    return f"{page_number}/{page_count}"


def _render_page(
    images: Sequence[ColorImage],
    output_path: Path,
    *,
    title: str,
    page_number: int,
    page_count: int,
    subtitle: str = "黑 / 红 / 蓝 · 每图最多三层 · 不含节奏",
) -> None:
    margin = 24
    header_height = 86
    card_width = 246
    card_height = 208
    gap = 12
    grid_size = 200
    cell_width = grid_size // SKY_COLUMNS
    cell_height = 120 // SKY_ROWS
    width = margin * 2 + PAGE_COLUMNS * card_width + (PAGE_COLUMNS - 1) * gap
    height = header_height + margin + PAGE_ROWS * card_height + (PAGE_ROWS - 1) * gap

    image = Image.new("RGB", (width, height), "#F5F7FB")
    draw = ImageDraw.Draw(image)
    title_font = _font(28)
    header_font = _font(15)
    card_font = _font(16)
    key_font = _font(11)
    small_font = _font(10)

    draw.text((margin, 18), title, fill="#111827", font=title_font)
    draw.text(
        (margin, 55),
        f"第 {page_number}/{page_count} 页 · {subtitle}",
        fill="#4B5563",
        font=header_font,
    )

    color_by_key: dict[str, str] = {}
    for local_index, logical_image in enumerate(images):
        color_by_key.clear()
        for layer in logical_image.layers:
            for key in layer.keys:
                if key in color_by_key:
                    raise ValueError(
                        f"逻辑图 {logical_image.index} 中按键 {key} 重叠，无法渲染"
                    )
                color_by_key[key] = layer.hex

        row, column = divmod(local_index, PAGE_COLUMNS)
        card_left = margin + column * (card_width + gap)
        card_top = header_height + row * (card_height + gap)
        card_right = card_left + card_width
        card_bottom = card_top + card_height
        draw.rectangle(
            (card_left, card_top, card_right, card_bottom),
            fill="#FFFFFF",
            outline="#CBD5E1",
            width=2,
        )
        draw.text(
            (card_left + 12, card_top + 8),
            f"#{logical_image.index:03d}",
            fill="#111827",
            font=card_font,
        )
        layer_summary = "  ".join(
            f"{COLOR_NAMES[layer.index]}:{len(layer.keys)}" for layer in logical_image.layers
        )
        draw.text(
            (card_left + 82, card_top + 11),
            layer_summary,
            fill="#64748B",
            font=small_font,
        )

        grid_left = card_left + 23
        grid_top = card_top + 39
        for key_index, key in enumerate(KEY_LABELS):
            grid_row, grid_column = divmod(key_index, SKY_COLUMNS)
            left = grid_left + grid_column * cell_width
            top = grid_top + grid_row * cell_height
            right = left + cell_width
            bottom = top + cell_height
            fill = color_by_key.get(key, "#FFFFFF")
            text_fill = "#FFFFFF" if key in color_by_key else "#64748B"
            draw.rectangle((left, top, right, bottom), fill=fill, outline="#CBD5E1", width=1)
            _text_center(draw, (left, top, right, bottom), key, key_font, text_fill)

        draw.text(
            (card_left + 14, card_bottom - 24),
            "  ".join(f"{COLOR_NAMES[layer.index]}={','.join(layer.keys)}" for layer in logical_image.layers),
            fill="#475569",
            font=small_font,
        )

    image.save(output_path, format="PNG", optimize=True)


def render_color_pages(
    images: Sequence[ColorImage],
    output_dir: str | Path,
    *,
    stem: str,
    title: str | None = None,
    page_size: int = PAGE_SIZE,
) -> tuple[Path, ...]:
    """Render logical images into numbered 6x4 square-grid PNG pages."""

    if page_size != PAGE_SIZE:
        raise ValueError(f"分页大小固定为 {PAGE_SIZE} 张逻辑图")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    page_count = max(1, math.ceil(len(images) / page_size))
    page_paths: list[Path] = []
    page_title = title or stem
    for page_number in range(1, page_count + 1):
        start = (page_number - 1) * page_size
        page_images = tuple(images[start : start + page_size])
        output_path = output / f"{stem}.color-{page_number:03d}.png"
        _render_page(
            page_images,
            output_path,
            title=page_title,
            page_number=page_number,
            page_count=page_count,
        )
        page_paths.append(output_path)
    return tuple(page_paths)


def _render_mobile_page(
    images: Sequence[ColorImage],
    output_path: Path,
    *,
    title: str,
    page_number: int,
    page_count: int,
) -> None:
    rows = max(1, math.ceil(len(images) / MOBILE_PAGE_COLUMNS))
    width = (
        MOBILE_MARGIN_X * 2
        + MOBILE_PAGE_COLUMNS * MOBILE_CARD_WIDTH
        + (MOBILE_PAGE_COLUMNS - 1) * MOBILE_GAP_X
    )
    height = (
        MOBILE_HEADER_HEIGHT
        + rows * MOBILE_CARD_HEIGHT
        + (rows - 1) * MOBILE_GAP_Y
        + MOBILE_BOTTOM_MARGIN
    )
    image = Image.new("RGB", (width, height), "#FFFFFF")
    draw = ImageDraw.Draw(image)
    draw.text(
        (MOBILE_MARGIN_X, 12),
        title,
        fill=MOBILE_TITLE_COLOR,
        font=_title_font(48),
    )
    _text_center(
        draw,
        (width - 105, 0, width - MOBILE_MARGIN_X, MOBILE_HEADER_HEIGHT),
        format_mobile_page_number(page_number, page_count),
        _font(24),
        MOBILE_PAGE_NUMBER_COLOR,
    )

    for local_index, logical_image in enumerate(images):
        color_by_key: dict[str, str] = {}
        for layer in logical_image.layers:
            try:
                layer_color = MOBILE_FRAME_COLORS[layer.index]
            except IndexError as exc:
                raise ValueError(
                    f"逻辑图 {logical_image.index} 使用了无效颜色层：{layer.index}"
                ) from exc
            for key in layer.keys:
                if key in color_by_key:
                    raise ValueError(
                        f"逻辑图 {logical_image.index} 中按键 {key} 重叠，无法渲染"
                    )
                color_by_key[key] = layer_color

        row, column = divmod(local_index, MOBILE_PAGE_COLUMNS)
        card_left = MOBILE_MARGIN_X + column * (MOBILE_CARD_WIDTH + MOBILE_GAP_X)
        card_top = MOBILE_HEADER_HEIGHT + row * (MOBILE_CARD_HEIGHT + MOBILE_GAP_Y)
        x_edges = [
            card_left + round(index * MOBILE_CARD_WIDTH / SKY_COLUMNS)
            for index in range(SKY_COLUMNS + 1)
        ]
        y_edges = [
            card_top + round(index * MOBILE_CARD_HEIGHT / SKY_ROWS)
            for index in range(SKY_ROWS + 1)
        ]

        for key_index, key in enumerate(KEY_LABELS):
            grid_row, grid_column = divmod(key_index, SKY_COLUMNS)
            left = x_edges[grid_column]
            top = y_edges[grid_row]
            right = x_edges[grid_column + 1]
            bottom = y_edges[grid_row + 1]
            draw.rectangle(
                (left + 1, top + 1, right - 1, bottom - 1),
                fill=color_by_key.get(key, "#FFFFFF"),
            )

        for edge in x_edges:
            draw.line((edge, card_top, edge, y_edges[-1]), fill=MOBILE_GRID_COLOR)
        for edge in y_edges:
            draw.line((card_left, edge, x_edges[-1], edge), fill=MOBILE_GRID_COLOR)

    image.save(output_path, format="PNG", optimize=True)


def render_mobile_color_pages(
    images: Sequence[ColorImage],
    output_dir: str | Path,
    *,
    stem: str,
    title: str | None = None,
    page_size: int = MOBILE_PAGE_SIZE,
) -> tuple[Path, ...]:
    """Render numbered 4-column phone pages with unlabeled 5x3 grids."""

    if page_size != MOBILE_PAGE_SIZE:
        raise ValueError(f"手机竖版分页大小固定为 {MOBILE_PAGE_SIZE} 张逻辑图")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    page_count = max(1, math.ceil(len(images) / page_size))
    page_paths: list[Path] = []
    page_title = title or stem
    for page_number in range(1, page_count + 1):
        start = (page_number - 1) * page_size
        page_images = tuple(images[start : start + page_size])
        output_path = output / f"{stem}.color-mobile-{page_number:03d}.png"
        _render_mobile_page(
            page_images,
            output_path,
            title=page_title,
            page_number=page_number,
            page_count=page_count,
        )
        page_paths.append(output_path)
    return tuple(page_paths)


def render_black_pages(
    images: Sequence[ColorImage],
    output_dir: str | Path,
    *,
    stem: str,
    title: str | None = None,
    page_size: int = PAGE_SIZE,
) -> tuple[Path, ...]:
    """Render one-black-layer logical images using the shared 6x4 layout."""

    if page_size != PAGE_SIZE:
        raise ValueError(f"分页大小固定为 {PAGE_SIZE} 张逻辑图")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    page_count = max(1, math.ceil(len(images) / page_size))
    page_paths: list[Path] = []
    page_title = title or stem
    for page_number in range(1, page_count + 1):
        start = (page_number - 1) * page_size
        page_images = tuple(images[start : start + page_size])
        output_path = output / f"{stem}.sky-{page_number:03d}.png"
        _render_page(
            page_images,
            output_path,
            title=page_title,
            page_number=page_number,
            page_count=page_count,
            subtitle="黑白谱 · 每图一层 · 不含节奏",
        )
        page_paths.append(output_path)
    return tuple(page_paths)


def convert_sky_txt_file(
    source_path: str | Path,
    output_dir: str | Path,
    *,
    song_index: int = 0,
) -> dict[str, Any]:
    """Convert one TXT file and write its JSON and paginated PNG artifacts."""

    source = Path(source_path)
    song = parse_sky_txt(source, song_index=song_index)
    images = compress_source_frames(song.frames)
    payload = build_color_payload(song, images, source_filename=source.name)

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    stem = source.stem
    json_path = output / f"{stem}.color.json"
    json_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    page_paths = render_color_pages(images, output, stem=stem, title=song.title)
    mobile_page_paths = render_mobile_color_pages(
        images, output, stem=stem, title=song.title
    )
    payload["artifacts"] = {
        "json": json_path.name,
        "png_pages": [path.name for path in page_paths],
        "mobile_png_pages": [path.name for path in mobile_page_paths],
    }
    json_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return payload


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="将 SkyStudio/画世界 JSON TXT 转换为无节奏彩色 Sky 谱。"
    )
    parser.add_argument("input", type=Path, help="输入 JSON TXT 文件")
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("outputs"),
        help="输出目录（默认：outputs）",
    )
    parser.add_argument(
        "--song-index",
        type=int,
        default=0,
        help="歌曲序号，从 0 开始（默认：0）",
    )
    args = parser.parse_args(argv)
    try:
        payload = convert_sky_txt_file(
            args.input,
            args.out_dir,
            song_index=args.song_index,
        )
    except (OSError, SkyTxtFormatError, ValueError) as exc:
        parser.error(str(exc))
    print(
        f"已转换：{payload['source_frame_count']} 个源帧 -> "
        f"{payload['image_count']} 张彩色图，PNG 分页 {len(payload['artifacts']['png_pages'])} 张"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
