"""Small Tkinter GUI for the Sky colour-sheet converter.

The converter itself remains in :mod:`scripts.txt_to_color_sky`.  This file is
only the desktop-facing front end used by the project-root launcher.
"""

from __future__ import annotations

import os
import queue
import sys
import threading
import traceback
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, X, Y, filedialog, messagebox, ttk
import tkinter as tk
from tkinter.scrolledtext import ScrolledText
from typing import Any, Mapping


PROJECT_ROOT = Path(__file__).resolve().parents[1]
PENDING_DIR_NAME = "待转谱"
OUTPUT_DIR_NAME = "outputs"

if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from scripts.txt_to_color_sky import (  # noqa: E402
    SkyTxtFormatError,
    convert_sky_txt_file,
)


def find_default_source(project_root: str | Path) -> Path | None:
    """Return the first TXT sheet in ``待转谱``, sorted by filename."""

    pending_dir = Path(project_root) / PENDING_DIR_NAME
    if not pending_dir.is_dir():
        return None
    candidates = sorted(
        (
            path
            for path in pending_dir.iterdir()
            if path.is_file() and path.suffix.lower() == ".txt"
        ),
        key=lambda path: path.name.casefold(),
    )
    return candidates[0] if candidates else None


def default_output_dir(project_root: str | Path, source: str | Path) -> Path:
    """Return the per-song output folder used by the GUI."""

    return Path(project_root) / OUTPUT_DIR_NAME / Path(source).stem


def format_conversion_summary(payload: Mapping[str, Any]) -> str:
    """Format conversion counts for the status area and completion dialog."""

    artifacts = payload.get("artifacts") or {}
    pages = artifacts.get("png_pages") or []
    warnings = payload.get("warnings") or []
    summary = (
        f"转换完成：{payload.get('source_note_count', 0)} 个音符、"
        f"{payload.get('source_frame_count', 0)} 个源帧 → "
        f"{payload.get('image_count', 0)} 张逻辑图，生成 {len(pages)} 张 PNG。"
    )
    if warnings:
        summary += f" 另有 {len(warnings)} 条警告。"
    return summary


class ConverterApp:
    """Tkinter application for selecting, converting, and opening a sheet."""

    def __init__(self, root: tk.Tk, project_root: str | Path = PROJECT_ROOT) -> None:
        self.root = root
        self.project_root = Path(project_root).resolve()
        self.result_queue: queue.Queue[tuple[str, Any]] = queue.Queue()
        self.busy = False

        source = find_default_source(self.project_root)
        self.source_var = tk.StringVar(value=str(source) if source else "")
        self.output_var = tk.StringVar(
            value=str(default_output_dir(self.project_root, source)) if source else ""
        )
        self.song_index_var = tk.StringVar(value="0")
        self.status_var = tk.StringVar(value="请选择输入谱文件")
        self._build_ui()

        if source:
            self._append_log(f"已自动选择：{source.name}")
            self._append_log("歌曲序号默认为 0；多首歌曲时可改为 1、2……")

    def _build_ui(self) -> None:
        self.root.title("Sky 彩谱转换器")
        self.root.geometry("900x620")
        self.root.minsize(760, 520)

        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill=BOTH, expand=True)

        title = ttk.Label(outer, text="Sky 彩谱转换器", font=("Microsoft YaHei UI", 20, "bold"))
        title.pack(anchor="w")
        subtitle = ttk.Label(
            outer,
            text="选择 SkyStudio / 画世界 JSON TXT，转换为黑 → 红 → 蓝的无节奏彩色谱。",
        )
        subtitle.pack(anchor="w", pady=(4, 16))

        input_frame = ttk.LabelFrame(outer, text="输入与输出", padding=12)
        input_frame.pack(fill=X)
        input_frame.columnconfigure(1, weight=1)

        ttk.Label(input_frame, text="输入谱文件").grid(row=0, column=0, sticky="w", padx=(0, 8), pady=5)
        ttk.Entry(input_frame, textvariable=self.source_var).grid(
            row=0, column=1, sticky="ew", pady=5
        )
        self.source_button = ttk.Button(input_frame, text="选择文件…", command=self._choose_source)
        self.source_button.grid(row=0, column=2, padx=(8, 0), pady=5)

        ttk.Label(input_frame, text="输出文件夹").grid(row=1, column=0, sticky="w", padx=(0, 8), pady=5)
        ttk.Entry(input_frame, textvariable=self.output_var).grid(
            row=1, column=1, sticky="ew", pady=5
        )
        self.output_button = ttk.Button(input_frame, text="选择文件夹…", command=self._choose_output)
        self.output_button.grid(row=1, column=2, padx=(8, 0), pady=5)

        ttk.Label(input_frame, text="歌曲序号").grid(row=2, column=0, sticky="w", padx=(0, 8), pady=5)
        self.song_index_spinbox = ttk.Spinbox(
            input_frame,
            from_=0,
            to=999,
            textvariable=self.song_index_var,
            width=8,
        )
        self.song_index_spinbox.grid(row=2, column=1, sticky="w", pady=5)
        ttk.Label(input_frame, text="从 0 开始；单首歌曲保持 0").grid(
            row=2, column=1, sticky="w", padx=(90, 0), pady=5
        )

        action_frame = ttk.Frame(outer)
        action_frame.pack(fill=X, pady=(14, 10))
        self.convert_button = ttk.Button(
            action_frame, text="开始转换", command=self.start_conversion
        )
        self.convert_button.pack(side=LEFT)
        self.player_button = ttk.Button(
            action_frame, text="打开彩谱试听器", command=self.open_player
        )
        self.player_button.pack(side=LEFT, padx=(8, 0))
        self.output_folder_button = ttk.Button(
            action_frame, text="打开输出文件夹", command=self.open_output_dir
        )
        self.output_folder_button.pack(side=LEFT, padx=(8, 0))
        ttk.Button(action_frame, text="退出", command=self.root.destroy).pack(side=RIGHT)

        status_frame = ttk.Frame(outer)
        status_frame.pack(fill=X, pady=(0, 8))
        ttk.Label(status_frame, text="状态：").pack(side=LEFT)
        ttk.Label(status_frame, textvariable=self.status_var).pack(side=LEFT, fill=X, expand=True)

        log_frame = ttk.LabelFrame(outer, text="转换记录", padding=8)
        log_frame.pack(fill=BOTH, expand=True)
        self.log_text = ScrolledText(
            log_frame,
            height=14,
            wrap="word",
            font=("Consolas", 10),
            state="disabled",
        )
        self.log_text.pack(fill=BOTH, expand=True)

    def _append_log(self, message: str) -> None:
        self.log_text.configure(state="normal")
        self.log_text.insert(END, message.rstrip() + "\n")
        self.log_text.see(END)
        self.log_text.configure(state="disabled")

    def _choose_source(self) -> None:
        initial_dir = self.project_root / PENDING_DIR_NAME
        selected = filedialog.askopenfilename(
            title="选择 SkyStudio / 画世界谱文件",
            initialdir=str(initial_dir if initial_dir.is_dir() else self.project_root),
            filetypes=[("JSON TXT", "*.txt"), ("所有文件", "*.*")],
        )
        if selected:
            source = Path(selected)
            self.source_var.set(str(source))
            self.output_var.set(str(default_output_dir(self.project_root, source)))
            self.status_var.set(f"已选择：{source.name}")
            self._append_log(f"输入：{source}")

    def _choose_output(self) -> None:
        initial_dir = Path(self.output_var.get().strip() or self.project_root / OUTPUT_DIR_NAME)
        selected = filedialog.askdirectory(
            title="选择输出文件夹",
            initialdir=str(initial_dir if initial_dir.is_dir() else self.project_root),
        )
        if selected:
            self.output_var.set(selected)

    def _set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = "disabled" if busy else "normal"
        for button in (
            self.source_button,
            self.output_button,
            self.convert_button,
            self.player_button,
            self.output_folder_button,
        ):
            button.configure(state=state)
        self.song_index_spinbox.configure(state=state)

    def start_conversion(self) -> None:
        if self.busy:
            return
        source_text = self.source_var.get().strip()
        output_text = self.output_var.get().strip()
        if not source_text:
            messagebox.showerror("缺少输入", "请先选择待转换的 TXT 谱文件。")
            return
        source = Path(source_text).expanduser()
        if not source.is_file():
            messagebox.showerror("输入不存在", f"找不到输入文件：\n{source}")
            return
        if not output_text:
            output_text = str(default_output_dir(self.project_root, source))
            self.output_var.set(output_text)
        try:
            song_index = int(self.song_index_var.get().strip())
        except ValueError:
            messagebox.showerror("歌曲序号无效", "歌曲序号必须是从 0 开始的整数。")
            return
        if song_index < 0:
            messagebox.showerror("歌曲序号无效", "歌曲序号不能小于 0。")
            return

        self._set_busy(True)
        self.status_var.set("正在转换，请稍候……")
        self._append_log(f"开始转换：{source.name}（歌曲序号 {song_index}）")
        worker = threading.Thread(
            target=self._conversion_worker,
            args=(source, Path(output_text).expanduser(), song_index),
            daemon=True,
        )
        worker.start()
        self.root.after(80, self._poll_result)

    def _conversion_worker(self, source: Path, output: Path, song_index: int) -> None:
        try:
            payload = convert_sky_txt_file(source, output, song_index=song_index)
            self.result_queue.put(("ok", (payload, output)))
        except (OSError, SkyTxtFormatError, ValueError) as error:
            self.result_queue.put(("error", str(error)))
        except Exception as error:  # Keep unexpected failures visible in the GUI.
            self.result_queue.put(
                (
                    "error",
                    f"未预期错误：{error}\n\n{traceback.format_exc()}",
                )
            )

    def _poll_result(self) -> None:
        try:
            kind, value = self.result_queue.get_nowait()
        except queue.Empty:
            self.root.after(80, self._poll_result)
            return

        self._set_busy(False)
        if kind == "ok":
            payload, output = value
            summary = format_conversion_summary(payload)
            json_name = (payload.get("artifacts") or {}).get("json", "")
            self.status_var.set(summary)
            self._append_log(summary)
            self._append_log(f"输出目录：{output}")
            if json_name:
                self._append_log(f"结构化谱：{output / json_name}")
            for warning in payload.get("warnings") or []:
                self._append_log(f"警告：{warning}")
            messagebox.showinfo("转换完成", summary + f"\n\n输出目录：\n{output}")
        else:
            self.status_var.set("转换失败")
            self._append_log(f"失败：{value}")
            messagebox.showerror("转换失败", value)

    def open_output_dir(self) -> None:
        output = Path(self.output_var.get().strip())
        if not output.is_dir():
            messagebox.showinfo("输出目录尚未生成", "请先完成一次转换，或先选择已有输出目录。")
            return
        os.startfile(str(output))

    def open_player(self) -> None:
        player = self.project_root / "player" / "index.html"
        if not player.is_file():
            messagebox.showerror("试听器不存在", f"找不到试听器：\n{player}")
            return
        os.startfile(str(player))


def main() -> int:
    root = tk.Tk()
    ConverterApp(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
