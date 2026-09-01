# 生成结果目录

`outputs/` 只保存本机运行时生成的结果，不是源码目录，也不会提交到公开仓库。建议每首输入使用一个独立子目录：

```powershell
.\.venv\Scripts\python.exe scripts\audio_pipeline.py input.mp4 --out-dir outputs\song --backend tsumugi
```

音频路径通常生成：

```text
song/
├─ audio/       # 视频抽取的 WAV
├─ separation/  # 可选 Demucs stem
├─ midi/        # 选定后端生成的 MIDI
├─ sky/         # MIDI 转黑白/彩色谱的结果
└─ pipeline.report.json
```

MIDI 转谱目录会包含 `*.sky.json`、`*.color.json`、标准分页 PNG、手机竖版分页 PNG、`*.notes.json` 和 `*.report.json`。每首歌的 PNG 列表以 `*-000.png` 黄色标题封面开头，后接 `*-001.png` 等谱页；封面不计入谱页 `1/10` 页码，也不进入 JSON 的可演奏图像。手机版每页最多 32 张逻辑图，并忽略字幕。

真实歌曲、音视频、模型权重和生成的谱面只在本机使用；公开仓库使用 `examples/` 中的小型示例或测试素材。
