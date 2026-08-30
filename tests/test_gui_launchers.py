from __future__ import annotations

import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory


PROJECT_ROOT = Path(__file__).resolve().parents[1]
GUI_SCRIPT = PROJECT_ROOT / "scripts" / "sky_converter_gui.py"


def load_gui_module():
    assert GUI_SCRIPT.is_file(), f"缺少转换器 GUI：{GUI_SCRIPT}"
    spec = importlib.util.spec_from_file_location("sky_converter_gui", GUI_SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_converter_gui_uses_first_txt_in_pending_folder_as_default():
    gui = load_gui_module()
    with TemporaryDirectory(dir=PROJECT_ROOT) as temp_dir:
        root = Path(temp_dir)
        pending = root / "待转谱"
        pending.mkdir()
        (pending / "b.txt").write_text("[]", encoding="utf-8")
        (pending / "a.txt").write_text("[]", encoding="utf-8")
        (pending / "ignore.json").write_text("{}", encoding="utf-8")

        assert gui.find_default_source(root) == pending / "a.txt"


def test_converter_gui_defaults_output_to_a_song_subfolder():
    gui = load_gui_module()
    with TemporaryDirectory(dir=PROJECT_ROOT) as temp_dir:
        root = Path(temp_dir)
        source = root / "待转谱" / "sample_song.txt"

        assert gui.default_output_dir(root, source) == (
            root / "outputs" / "sample_song"
        )


def test_conversion_summary_contains_generated_counts():
    gui = load_gui_module()

    summary = gui.format_conversion_summary(
        {
            "source_frame_count": 12,
            "image_count": 5,
            "source_note_count": 30,
            "artifacts": {
                "png_pages": ["one.png", "two.png"],
                "mobile_png_pages": [
                    "mobile-one.png",
                    "mobile-two.png",
                    "mobile-three.png",
                ],
            },
            "warnings": ["重复按键"],
        }
    )

    assert "30 个音符" in summary
    assert "12 个源帧" in summary
    assert "5 张逻辑图" in summary
    assert "3 张手机竖版 PNG" in summary
    assert "另生成 2 张横版 PNG" in summary
    assert "1 条警告" in summary


def test_root_launchers_are_project_relative_and_clickable():
    converter = PROJECT_ROOT / "启动彩谱转换器.cmd"
    player = PROJECT_ROOT / "启动彩谱试听器.cmd"
    unified = PROJECT_ROOT / "启动音乐转换器.cmd"

    assert converter.is_file()
    assert player.is_file()
    converter_text = converter.read_text(encoding="utf-8-sig").lower()
    player_text = player.read_text(encoding="utf-8-sig").lower()
    assert "%~dp0scripts\\sky_converter_gui.py" in converter_text
    assert "pyw.exe" in converter_text
    assert "%~dp0player\\index.html" in player_text
    assert "start" in player_text
    unified_text = unified.read_text(encoding="utf-8-sig").lower()
    assert "%~dp0scripts\\music_converter_gui.py" in unified_text
    assert ".venv\\scripts\\pythonw.exe" in unified_text
    assert "start" in unified_text


def test_windows_shortcut_files_are_not_part_of_the_public_project():
    assert not list(PROJECT_ROOT.glob("*.lnk"))


def test_audio_setup_script_has_no_machine_specific_python_path():
    setup = (PROJECT_ROOT / "scripts" / "setup_audio_backend.ps1").read_text(
        encoding="utf-8-sig"
    )
    lowered = setup.lower()
    assert "c:\\users\\" not in lowered
    assert "py -3.12" in lowered
    assert "-3.10" in lowered
    assert "requirements-audio-separation.txt" in lowered
    assert "uv sync --locked" in lowered
    assert "ffmpeg" in lowered
