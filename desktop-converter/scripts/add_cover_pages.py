"""Add ``000`` cover PNGs to already generated Sky score folders.

The normal converters create covers automatically.  This small migration tool
is for older output folders that were generated before cover pages existed.
It updates artifact lists but never adds the cover to a playable JSON
``images`` array.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from scripts.txt_to_color_sky import render_cover_page


_PAGE_NAME = re.compile(r"-(\d{3})\.png$")


def _read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON 顶层必须是对象：{path}")
    return value


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _title(payload: dict[str, Any], fallback: str) -> str:
    source = payload.get("source")
    if isinstance(source, dict):
        name = source.get("name")
        if isinstance(name, str) and name.strip():
            return name.strip()
    value = payload.get("title")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return fallback


def _page_names(directory: Path, stem: str, family: str) -> list[str]:
    prefix = f"{stem}.{family}-"
    names = []
    for path in directory.glob(f"{prefix}*.png"):
        if path.name.startswith(prefix) and _PAGE_NAME.search(path.name):
            names.append(path.name)
    return sorted(names, key=lambda name: int(_PAGE_NAME.search(name).group(1)))


def _prepend_page(
    artifacts: dict[str, Any],
    *,
    list_key: str,
    cover_key: str,
    cover_name: str,
    directory: Path,
    stem: str,
    family: str,
) -> bool:
    existing = artifacts.get(list_key)
    names = [name for name in existing if isinstance(name, str)] if isinstance(existing, list) else []
    for name in _page_names(directory, stem, family):
        if name not in names:
            names.append(name)
    ordered = [cover_name] + [name for name in names if name != cover_name]
    changed = artifacts.get(list_key) != ordered or artifacts.get(cover_key) != cover_name
    artifacts[list_key] = ordered
    artifacts[cover_key] = cover_name
    return changed


def _ensure_family_cover(
    payload: dict[str, Any],
    *,
    directory: Path,
    stem: str,
    family: str,
    list_key: str,
    cover_key: str,
    title: str,
) -> tuple[str | None, bool, bool]:
    first_page = directory / f"{stem}.{family}-001.png"
    if not first_page.is_file():
        return None, False, False
    cover_path = directory / f"{stem}.{family}-000.png"
    created = not cover_path.exists()
    if created:
        render_cover_page(first_page, cover_path, title=title)
    artifacts = payload.setdefault("artifacts", {})
    if not isinstance(artifacts, dict):
        raise ValueError(f"artifacts 必须是对象：{directory / (stem + '.json')}")
    changed = _prepend_page(
        artifacts,
        list_key=list_key,
        cover_key=cover_key,
        cover_name=cover_path.name,
        directory=directory,
        stem=stem,
        family=family,
    )
    return cover_path.name, created, changed


def _update_report_artifacts(
    report: dict[str, Any],
    *,
    family: str,
    cover_name: str,
    directory: Path,
    stem: str,
) -> bool:
    artifacts = report.setdefault("artifacts", {})
    if not isinstance(artifacts, dict):
        raise ValueError(f"报告 artifacts 必须是对象：{directory / (stem + '.report.json')}")
    if family == "color-mobile":
        list_key, cover_key = "color_mobile_png_pages", "color_mobile_cover_png"
        fallback_key = "mobile_png_pages"
    elif family == "color":
        list_key, cover_key = "color_png_pages", "color_cover_png"
        fallback_key = "png_pages"
    else:
        list_key, cover_key = "black_png_pages", "black_cover_png"
        fallback_key = "png_pages"
    if list_key not in artifacts and fallback_key in artifacts:
        artifacts[list_key] = artifacts[fallback_key]
    return _prepend_page(
        artifacts,
        list_key=list_key,
        cover_key=cover_key,
        cover_name=cover_name,
        directory=directory,
        stem=stem,
        family=family,
    )


def _migrate_payload(
    path: Path,
    *,
    family_specs: tuple[tuple[str, str, str], ...],
) -> tuple[int, int, list[str]]:
    payload = _read_json(path)
    if path.name.endswith(".color.json"):
        stem = path.name[: -len(".color.json")]
    elif path.name.endswith(".sky.json"):
        stem = path.name[: -len(".sky.json")]
    else:
        stem = path.stem
    title = _title(payload, stem)
    created_count = 0
    changed = False
    covers: list[str] = []
    for family, list_key, cover_key in family_specs:
        cover_name, created, family_changed = _ensure_family_cover(
            payload,
            directory=path.parent,
            stem=stem,
            family=family,
            list_key=list_key,
            cover_key=cover_key,
            title=title,
        )
        if cover_name:
            covers.append(cover_name)
            created_count += int(created)
            changed = changed or family_changed
    if changed:
        _write_json(path, payload)
    return created_count, int(changed), covers


def add_cover_pages(root: str | Path) -> dict[str, int]:
    """Migrate every generated colour score below ``root``.

    The returned counters are deliberately small and stable for scripts:
    ``songs`` counts colour JSON files, ``covers`` counts newly created PNGs,
    and ``updated_json`` counts JSON files whose artifact metadata changed.
    """

    root_path = Path(root)
    if not root_path.is_dir():
        raise FileNotFoundError(f"输出目录不存在：{root_path}")
    songs = 0
    covers = 0
    updated_json = 0
    color_specs = (
        ("color-mobile", "mobile_png_pages", "cover_png"),
        ("color", "png_pages", "desktop_cover_png"),
    )
    black_specs = (("sky", "png_pages", "cover_png"),)
    for color_json in sorted(root_path.rglob("*.color.json")):
        songs += 1
        created, changed, cover_names = _migrate_payload(
            color_json,
            family_specs=color_specs,
        )
        covers += created
        updated_json += changed
        report_path = color_json.with_name(color_json.name[: -len(".color.json")] + ".report.json")
        if report_path.is_file() and cover_names:
            report = _read_json(report_path)
            report_changed = False
            stem = color_json.name[: -len(".color.json")]
            for family, _, _ in color_specs:
                cover_path = color_json.parent / f"{stem}.{family}-000.png"
                if cover_path.is_file():
                    report_changed = _update_report_artifacts(
                        report,
                        family=family,
                        cover_name=cover_path.name,
                        directory=color_json.parent,
                        stem=stem,
                    ) or report_changed
            if report_changed:
                _write_json(report_path, report)
                updated_json += 1
    for sky_json in sorted(root_path.rglob("*.sky.json")):
        created, changed, _ = _migrate_payload(
            sky_json,
            family_specs=black_specs,
        )
        covers += created
        updated_json += changed
        report_path = sky_json.with_name(sky_json.name[: -len(".sky.json")] + ".report.json")
        cover_path = sky_json.parent / f"{sky_json.name[: -len('.sky.json')]}.sky-000.png"
        if report_path.is_file() and cover_path.is_file():
            report = _read_json(report_path)
            stem = sky_json.name[: -len(".sky.json")]
            if _update_report_artifacts(
                report,
                family="sky",
                cover_name=cover_path.name,
                directory=sky_json.parent,
                stem=stem,
            ):
                _write_json(report_path, report)
                updated_json += 1
    return {"songs": songs, "covers": covers, "updated_json": updated_json}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="为已有 Sky 谱 PNG 增加 000 黄色标题封面页")
    parser.add_argument("root", nargs="?", type=Path, default=Path("outputs"))
    args = parser.parse_args(argv)
    try:
        result = add_cover_pages(args.root)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(str(exc))
    print(
        f"已处理 {result['songs']} 首歌曲，新增 {result['covers']} 张封面，"
        f"更新 {result['updated_json']} 个 JSON 文件。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
