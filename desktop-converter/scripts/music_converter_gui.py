"""Unified desktop front end for Jianpu, audio, MIDI, and Sky TXT inputs."""

from __future__ import annotations

import json
import os
import queue
import sys
import threading
import traceback
from dataclasses import dataclass
from pathlib import Path
from tkinter import BOTH, END, LEFT, RIGHT, X, Y, filedialog, messagebox, ttk
import tkinter as tk
from tkinter.scrolledtext import ScrolledText
from typing import Any, Mapping


PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR_NAME = "outputs"
SUPPORTED_MIDI_EXTENSIONS = {".mid", ".midi"}
SUPPORTED_AUDIO_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg"}
SUPPORTED_VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"}

if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from scripts.jianpu_to_midi import convert_jianpu_file  # noqa: E402
from scripts.audio_pipeline import convert_media_to_sky  # noqa: E402
from scripts.midi_to_sky import convert_midi_file_to_sky  # noqa: E402
from scripts.txt_to_color_sky import convert_sky_txt_file  # noqa: E402


@dataclass(frozen=True)
class ConversionPlan:
    source: Path
    kind: str
    output_dir: Path


def default_output_dir(project_root: str | Path, source: str | Path) -> Path:
    """Return the per-input output directory used by the unified GUI."""

    return Path(project_root) / OUTPUT_DIR_NAME / Path(source).stem


def _decode_text(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise ValueError(f"无法识别 TXT 编码：{path}")


def _is_sky_json_txt(path: Path) -> bool:
    try:
        payload = json.loads(_decode_text(path))
    except (OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError):
        return False
    songs: Any
    if isinstance(payload, list):
        songs = payload
    elif isinstance(payload, dict) and isinstance(payload.get("songs"), list):
        songs = payload["songs"]
    else:
        songs = [payload]
    return any(
        isinstance(song, dict) and isinstance(song.get("songNotes"), list)
        for song in songs
    )


def detect_input_kind(source: str | Path) -> str:
    """Return ``midi``, ``audio``, ``video``, ``jianpu``, or legacy ``sky-json``."""

    path = Path(source)
    suffix = path.suffix.lower()
    if suffix in SUPPORTED_MIDI_EXTENSIONS:
        return "midi"
    if suffix in SUPPORTED_AUDIO_EXTENSIONS:
        return "audio"
    if suffix in SUPPORTED_VIDEO_EXTENSIONS:
        return "video"
    if suffix == ".jpwabc":
        return "jianpu"
    if suffix == ".txt":
        if not path.is_file():
            raise FileNotFoundError(f"找不到 TXT 文件，无法判断格式：{path}")
        return "sky-json" if _is_sky_json_txt(path) else "jianpu"
    raise ValueError(
        f"不支持的输入格式：{suffix or '无扩展名'}；"
        "支持 MIDI、MP3/WAV/FLAC/M4A、MP4/MKV/MOV、简谱 TXT/.jpwabc 和 Sky JSON TXT"
    )


def input_option_state(kind: str | None) -> dict[str, bool]:
    """Describe which GUI controls apply to an input kind.

    Keeping this decision pure makes the headless part of the GUI contract
    testable and prevents an irrelevant audio setting from blocking MIDI or
    legacy Sky TXT conversion.
    """

    normalized = str(kind or "").strip().lower()
    audio = normalized in {"audio", "video"}
    mapping = normalized in {"midi", "jianpu", "audio", "video"}
    return {
        "audio_options_enabled": audio,
        "backend_enabled": audio,
        "separate_enabled": audio,
        "stem_enabled": audio,
        "device_enabled": audio,
        "demucs_model_enabled": audio,
        "demucs_shifts_enabled": audio,
        "key_enabled": mapping,
        "bpm_enabled": normalized == "jianpu",
        "policy_enabled": mapping,
        "song_index_enabled": normalized == "sky-json",
    }


def build_conversion_plan(
    source: str | Path,
    project_root: str | Path = PROJECT_ROOT,
    output_dir: str | Path | None = None,
) -> ConversionPlan:
    path = Path(source).expanduser()
    kind = detect_input_kind(path)
    output = Path(output_dir).expanduser() if output_dir else default_output_dir(project_root, path)
    return ConversionPlan(source=path, kind=kind, output_dir=output)


def convert_source_file(
    source: str | Path,
    output_dir: str | Path,
    *,
    project_root: str | Path = PROJECT_ROOT,
    key: str = "C",
    bpm: float = 120.0,
    chromatic_policy: str = "drop",
    song_index: int = 0,
    basic_pitch_executable: str | Path | None = None,
    backend: str = "tsumugi",
    separate: bool = False,
    stem: str = "guitar",
    demucs_model: str = "htdemucs_6s",
    demucs_shifts: int = 5,
    device: str = "auto",
) -> dict[str, Any]:
    """Dispatch an input file and always run the unified Sky stage when needed."""

    plan = build_conversion_plan(source, project_root, output_dir)
    if not plan.source.is_file():
        raise FileNotFoundError(f"找不到输入文件：{plan.source}")
    plan.output_dir.mkdir(parents=True, exist_ok=True)

    if plan.kind == "sky-json":
        legacy = convert_sky_txt_file(plan.source, plan.output_dir, song_index=song_index)
        return {"kind": plan.kind, "legacy": legacy, "color": legacy, "output_dir": plan.output_dir}

    if plan.kind == "midi":
        sky = convert_midi_file_to_sky(
            plan.source,
            plan.output_dir,
            key=key,
            chromatic_policy=chromatic_policy,
        )
        return {"kind": plan.kind, **sky, "output_dir": plan.output_dir}

    if plan.kind == "jianpu":
        jianpu = convert_jianpu_file(
            plan.source,
            plan.output_dir,
            default_key=key,
            default_bpm=bpm,
        )
        midi_path = plan.output_dir / jianpu["midi"]
        sky = convert_midi_file_to_sky(
            midi_path,
            plan.output_dir,
            key=key,
            chromatic_policy=chromatic_policy,
            title=(jianpu.get("source") or {}).get("name"),
        )
        return {"kind": plan.kind, "jianpu": jianpu, **sky, "output_dir": plan.output_dir}

    media = convert_media_to_sky(
        plan.source,
        plan.output_dir,
        project_root=project_root,
        backend=backend,
        separate=separate,
        stem=stem,
        demucs_model=demucs_model,
        demucs_shifts=demucs_shifts,
        key=key,
        chromatic_policy=chromatic_policy,
        device=device,
    )
    return {"kind": plan.kind, **media}


def _payload_for_summary(payload: Mapping[str, Any]) -> Mapping[str, Any]:
    if isinstance(payload.get("report"), Mapping):
        if isinstance(payload["report"].get("sky"), Mapping):
            return payload["report"]["sky"]
        return payload["report"]
    if isinstance(payload.get("color"), Mapping):
        return payload["color"]
    if isinstance(payload.get("legacy"), Mapping):
        return payload["legacy"]
    return payload


def format_conversion_summary(payload: Mapping[str, Any]) -> str:
    """Format counts from either the new unified or legacy converter result."""

    report = _payload_for_summary(payload)
    if "input_note_count" in report:
        black_count = report.get("black_image_count", 0)
        color_count = report.get("color_image_count", 0)
        mobile_pages = (
            (report.get("artifacts") or {}).get("color_mobile_png_pages")
            or (report.get("artifacts") or {}).get("mobile_png_pages")
            or []
        )
        summary = (
            f"转换完成：输入 {report.get('input_note_count', 0)} 个音符，"
            f"映射 {report.get('mapped_note_count', 0)} 个，"
            f"黑白图 {black_count} 张，彩色图 {color_count} 张，"
            f"手机竖版 {len(mobile_pages)} 张。"
        )
        warnings = report.get("warnings") or []
    else:
        pages = (report.get("artifacts") or {}).get("png_pages") or []
        mobile_pages = (
            (report.get("artifacts") or {}).get("mobile_png_pages")
            or (report.get("artifacts") or {}).get("color_mobile_png_pages")
            or []
        )
        summary = (
            f"转换完成：{report.get('source_note_count', 0)} 个音符、"
            f"{report.get('source_frame_count', 0)} 个源帧 → "
            f"{report.get('image_count', 0)} 张逻辑图，生成 {len(pages)} 张标准 PNG，"
            f"手机竖版 {len(mobile_pages)} 张。"
        )
        warnings = report.get("warnings") or []
    if warnings:
        summary += f" 另有 {len(warnings)} 条警告。"
    return summary


class ConverterApp:
    """Tkinter front end with a worker thread so conversion keeps the UI responsive."""

    def __init__(self, root: tk.Tk, project_root: str | Path = PROJECT_ROOT) -> None:
        self.root = root
        self.project_root = Path(project_root).resolve()
        self.result_queue: queue.Queue[tuple[str, Any]] = queue.Queue()
        self.busy = False
        self.current_kind: str | None = None

        self.source_var = tk.StringVar(value="")
        self.output_var = tk.StringVar(value="")
        self.key_var = tk.StringVar(value="C")
        self.bpm_var = tk.StringVar(value="120")
        self.policy_var = tk.StringVar(value="drop")
        self.song_index_var = tk.StringVar(value="0")
        self.backend_var = tk.StringVar(value="tsumugi")
        self.device_var = tk.StringVar(value="auto")
        self.separate_var = tk.BooleanVar(value=False)
        self.stem_var = tk.StringVar(value="guitar")
        self.demucs_model_var = tk.StringVar(value="htdemucs_6s")
        self.demucs_shifts_var = tk.StringVar(value="5")
        self.advanced_var = tk.BooleanVar(value=False)
        self.status_var = tk.StringVar(value="请选择 MIDI、音频或简谱文件")
        self._build_ui()

    def _build_ui(self) -> None:
        self.root.title("音乐 → Sky 谱转换器")
        self.root.geometry("960x700")
        self.root.minsize(820, 600)

        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill=BOTH, expand=True)
        ttk.Label(outer, text="音乐 → Sky 谱转换器", font=("Microsoft YaHei UI", 20, "bold")).pack(anchor="w")
        ttk.Label(
            outer,
            text="视频/音频 → 可选分离 → 选择 MIDI 后端 → MIDI → 黑白谱 + 黑红蓝彩谱；默认使用 Tsumugi guitar_v1_5。",
        ).pack(anchor="w", pady=(4, 16))

        input_frame = ttk.LabelFrame(outer, text="输入与输出", padding=12)
        input_frame.pack(fill=X)
        input_frame.columnconfigure(1, weight=1)
        ttk.Label(input_frame, text="输入文件").grid(row=0, column=0, sticky="w", padx=(0, 8), pady=5)
        ttk.Entry(input_frame, textvariable=self.source_var).grid(row=0, column=1, sticky="ew", pady=5)
        self.source_button = ttk.Button(input_frame, text="选择文件…", command=self._choose_source)
        self.source_button.grid(row=0, column=2, padx=(8, 0), pady=5)

        ttk.Label(input_frame, text="输出文件夹").grid(row=1, column=0, sticky="w", padx=(0, 8), pady=5)
        ttk.Entry(input_frame, textvariable=self.output_var).grid(row=1, column=1, sticky="ew", pady=5)
        self.output_button = ttk.Button(input_frame, text="选择文件夹…", command=self._choose_output)
        self.output_button.grid(row=1, column=2, padx=(8, 0), pady=5)

        options = ttk.LabelFrame(outer, text="常用设置", padding=12)
        options.pack(fill=X, pady=(12, 0))
        ttk.Label(options, text="音频 MIDI 后端").grid(row=0, column=0, sticky="w", padx=(0, 5))
        self.backend_combo = ttk.Combobox(
            options,
            textvariable=self.backend_var,
            values=("tsumugi", "basic_pitch", "pyin", "torchcrepe"),
            state="readonly",
            width=14,
        )
        self.backend_combo.grid(row=0, column=1, sticky="w", padx=(0, 18))
        self.separate_check = ttk.Checkbutton(
            options,
            text="先用 Demucs 分离",
            variable=self.separate_var,
        )
        self.separate_check.grid(row=0, column=2, sticky="w", padx=(0, 18))
        ttk.Label(options, text="Stem").grid(row=0, column=3, sticky="w", padx=(0, 5))
        self.stem_entry = ttk.Entry(options, textvariable=self.stem_var, width=10)
        self.stem_entry.grid(row=0, column=4, sticky="w", padx=(0, 18))
        self.advanced_toggle = ttk.Checkbutton(
            options,
            text="显示高级设置",
            variable=self.advanced_var,
            command=self._toggle_advanced,
        )
        self.advanced_toggle.grid(row=0, column=5, sticky="w")

        self.advanced_panel = ttk.LabelFrame(outer, text="高级设置", padding=12)
        self.advanced_panel.columnconfigure(1, weight=1)
        ttk.Label(self.advanced_panel, text="调性").grid(row=0, column=0, sticky="w", padx=(0, 5))
        self.key_entry = ttk.Entry(self.advanced_panel, textvariable=self.key_var, width=8)
        self.key_entry.grid(row=0, column=1, sticky="w", padx=(0, 18))
        ttk.Label(self.advanced_panel, text="默认 BPM").grid(row=0, column=2, sticky="w", padx=(0, 5))
        self.bpm_entry = ttk.Entry(self.advanced_panel, textvariable=self.bpm_var, width=8)
        self.bpm_entry.grid(row=0, column=3, sticky="w", padx=(0, 18))
        ttk.Label(self.advanced_panel, text="半音处理").grid(row=0, column=4, sticky="w", padx=(0, 5))
        self.policy_combo = ttk.Combobox(
            self.advanced_panel,
            textvariable=self.policy_var,
            values=("drop", "nearest", "error"),
            state="readonly",
            width=10,
        )
        self.policy_combo.grid(row=0, column=5, sticky="w", padx=(0, 18))
        ttk.Label(self.advanced_panel, text="Sky TXT 歌曲序号").grid(row=0, column=6, sticky="w", padx=(0, 5))
        self.song_index_spinbox = ttk.Spinbox(
            self.advanced_panel,
            from_=0,
            to=999,
            textvariable=self.song_index_var,
            width=7,
        )
        self.song_index_spinbox.grid(row=0, column=7, sticky="w")

        ttk.Label(self.advanced_panel, text="设备").grid(row=1, column=0, sticky="w", padx=(0, 5), pady=(10, 0))
        self.device_combo = ttk.Combobox(
            self.advanced_panel,
            textvariable=self.device_var,
            values=("auto", "cpu", "cuda", "mps"),
            state="readonly",
            width=8,
        )
        self.device_combo.grid(row=1, column=1, sticky="w", padx=(0, 18), pady=(10, 0))
        ttk.Label(self.advanced_panel, text="Demucs 模型").grid(row=1, column=2, sticky="w", padx=(0, 5), pady=(10, 0))
        self.demucs_model_entry = ttk.Entry(self.advanced_panel, textvariable=self.demucs_model_var, width=16)
        self.demucs_model_entry.grid(row=1, column=3, sticky="w", padx=(0, 18), pady=(10, 0))
        ttk.Label(self.advanced_panel, text="shifts").grid(row=1, column=4, sticky="w", padx=(0, 5), pady=(10, 0))
        self.demucs_shifts_entry = ttk.Entry(self.advanced_panel, textvariable=self.demucs_shifts_var, width=5)
        self.demucs_shifts_entry.grid(row=1, column=5, sticky="w", pady=(10, 0))

        self._action_frame = ttk.Frame(outer)
        self._action_frame.pack(fill=X, pady=(14, 10))
        self.convert_button = ttk.Button(self._action_frame, text="开始转换", command=self.start_conversion)
        self.convert_button.pack(side=LEFT)
        self.player_button = ttk.Button(self._action_frame, text="打开试听器", command=self.open_player)
        self.player_button.pack(side=LEFT, padx=(8, 0))
        self.output_folder_button = ttk.Button(self._action_frame, text="打开输出文件夹", command=self.open_output_dir)
        self.output_folder_button.pack(side=LEFT, padx=(8, 0))
        ttk.Button(self._action_frame, text="退出", command=self.root.destroy).pack(side=RIGHT)

        status_frame = ttk.Frame(outer)
        status_frame.pack(fill=X, pady=(0, 8))
        ttk.Label(status_frame, text="状态：").pack(side=LEFT)
        ttk.Label(status_frame, textvariable=self.status_var).pack(side=LEFT, fill=X, expand=True)
        log_frame = ttk.LabelFrame(outer, text="转换记录", padding=8)
        log_frame.pack(fill=BOTH, expand=True)
        self.log_text = ScrolledText(log_frame, height=16, wrap="word", font=("Consolas", 10), state="disabled")
        self.log_text.pack(fill=BOTH, expand=True)
        self._update_option_states(None)

    def _toggle_advanced(self) -> None:
        if self.advanced_var.get():
            self.advanced_panel.pack(fill=X, pady=(12, 0), before=self._action_frame)
        else:
            self.advanced_panel.pack_forget()

    def _update_option_states(self, kind: str | None) -> None:
        self.current_kind = kind
        options = input_option_state(kind)
        busy = self.busy

        def entry_state(enabled: bool) -> str:
            return "normal" if enabled and not busy else "disabled"

        def combo_state(enabled: bool) -> str:
            return "readonly" if enabled and not busy else "disabled"

        self.backend_combo.configure(state=combo_state(options["backend_enabled"]))
        self.separate_check.configure(
            state=entry_state(options["separate_enabled"])
        )
        self.stem_entry.configure(state=entry_state(options["stem_enabled"]))
        self.key_entry.configure(state=entry_state(options["key_enabled"]))
        self.bpm_entry.configure(state=entry_state(options["bpm_enabled"]))
        self.policy_combo.configure(state=combo_state(options["policy_enabled"]))
        self.song_index_spinbox.configure(
            state=entry_state(options["song_index_enabled"])
        )
        self.device_combo.configure(state=combo_state(options["device_enabled"]))
        self.demucs_model_entry.configure(
            state=entry_state(options["demucs_model_enabled"])
        )
        self.demucs_shifts_entry.configure(
            state=entry_state(options["demucs_shifts_enabled"])
        )

    def _append_log(self, message: str) -> None:
        self.log_text.configure(state="normal")
        self.log_text.insert(END, message.rstrip() + "\n")
        self.log_text.see(END)
        self.log_text.configure(state="disabled")

    def _choose_source(self) -> None:
        selected = filedialog.askopenfilename(
            title="选择音乐或谱面文件",
            initialdir=str(self.project_root),
            filetypes=[
                ("支持的文件", "*.mid *.midi *.mp3 *.wav *.flac *.m4a *.ogg *.mp4 *.mkv *.mov *.avi *.webm *.m4v *.txt *.jpwabc"),
                ("所有文件", "*.*"),
            ],
        )
        if selected:
            source = Path(selected)
            self.source_var.set(str(source))
            self.output_var.set(str(default_output_dir(self.project_root, source)))
            try:
                kind = detect_input_kind(source)
                self._update_option_states(kind)
                self.status_var.set(f"已选择：{source.name}（{kind}）")
            except (OSError, ValueError) as error:
                self.status_var.set(str(error))
            self._append_log(f"输入：{source}")

    def _choose_output(self) -> None:
        initial = Path(self.output_var.get().strip() or self.project_root / OUTPUT_DIR_NAME)
        selected = filedialog.askdirectory(
            title="选择输出文件夹",
            initialdir=str(initial if initial.is_dir() else self.project_root),
        )
        if selected:
            self.output_var.set(selected)

    def _set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = "disabled" if busy else "normal"
        for button in (self.source_button, self.output_button, self.convert_button, self.player_button, self.output_folder_button):
            button.configure(state=state)
        self._update_option_states(self.current_kind)

    def start_conversion(self) -> None:
        if self.busy:
            return
        source_text = self.source_var.get().strip()
        if not source_text:
            messagebox.showerror("缺少输入", "请先选择一个 MIDI、音频或简谱文件。")
            return
        source = Path(source_text).expanduser()
        try:
            plan = build_conversion_plan(source, self.project_root, self.output_var.get().strip() or None)
            if not source.is_file():
                raise FileNotFoundError(f"找不到输入文件：{source}")
            self._update_option_states(plan.kind)
            bpm = 120.0
            if plan.kind == "jianpu":
                bpm = float(self.bpm_var.get().strip())
                if bpm <= 0:
                    raise ValueError("BPM 必须是正数")
            song_index = 0
            if plan.kind == "sky-json":
                song_index = int(self.song_index_var.get().strip())
                if song_index < 0:
                    raise ValueError("歌曲序号不能小于 0")
            demucs_shifts = 5
            stem = "guitar"
            if plan.kind in {"audio", "video"}:
                demucs_shifts = int(self.demucs_shifts_var.get().strip())
                if demucs_shifts < 0:
                    raise ValueError("Demucs shifts 不能小于 0")
                stem = self.stem_var.get().strip() or "guitar"
        except (OSError, ValueError) as error:
            messagebox.showerror("输入参数无效", str(error))
            return

        self.output_var.set(str(plan.output_dir))
        self._set_busy(True)
        self.status_var.set(f"正在转换 {plan.kind}，请稍候……")
        self._append_log(f"开始转换：{source.name}（类型 {plan.kind}）")
        worker = threading.Thread(
            target=self._conversion_worker,
            args=(
                source,
                plan.output_dir,
                self.key_var.get().strip(),
                bpm,
                self.policy_var.get(),
                song_index,
                self.backend_var.get().strip(),
                bool(self.separate_var.get()),
                stem,
                self.demucs_model_var.get().strip() or "htdemucs_6s",
                demucs_shifts,
                self.device_var.get().strip() or "auto",
            ),
            daemon=True,
        )
        worker.start()
        self.root.after(80, self._poll_result)

    def _conversion_worker(
        self,
        source: Path,
        output: Path,
        key: str,
        bpm: float,
        chromatic_policy: str,
        song_index: int,
        backend: str,
        separate: bool,
        stem: str,
        demucs_model: str,
        demucs_shifts: int,
        device: str,
    ) -> None:
        try:
            payload = convert_source_file(
                source,
                output,
                project_root=self.project_root,
                key=key,
                bpm=bpm,
                chromatic_policy=chromatic_policy,
                song_index=song_index,
                backend=backend,
                separate=separate,
                stem=stem,
                demucs_model=demucs_model,
                demucs_shifts=demucs_shifts,
                device=device,
            )
            self.result_queue.put(("ok", payload))
        except Exception as error:
            self.result_queue.put(("error", f"{error}\n\n{traceback.format_exc()}"))

    def _poll_result(self) -> None:
        try:
            kind, value = self.result_queue.get_nowait()
        except queue.Empty:
            self.root.after(80, self._poll_result)
            return
        self._set_busy(False)
        if kind == "ok":
            summary = format_conversion_summary(value)
            output = value.get("output_dir", self.output_var.get())
            self.status_var.set(summary)
            self._append_log(summary)
            self._append_log(f"输出目录：{output}")
            report = value.get("report") or {}
            for warning in report.get("warnings") or []:
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
