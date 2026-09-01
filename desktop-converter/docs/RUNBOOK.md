# 运行手册

## 1. 核心环境

项目面向 Windows 源码运行。推荐 Python 3.12；Python 3.10 也可用于音频环境。

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-core.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

播放器只需要 Node.js 的测试命令，运行时直接打开 `player/index.html` 即可。

## 2. 音频环境

先确保 `ffmpeg` 已安装并在 PATH 中，然后初始化 Tsumugi 子模块：

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File scripts\setup_audio_backend.ps1
```

安装脚本会创建 `.audio-venv`，安装 Basic Pitch、pYIN、torchcrepe 和 Demucs，并在有 `uv` 时进入 `vendor/tsumugi/` 执行 `uv sync --locked`。缺少 `uv`、ffmpeg、子模块或模型时会给出明确警告；视频抽取、分离或 Tsumugi 推理不会静默退回其他路线。

Tsumugi 源码固定在 `.gitmodules` 记录的提交。模型权重由使用者自行下载并放置为：

```text
vendor/tsumugi/checkpoints/best_model_guitar_v1_5.pth
```

Tsumugi 适配器优先使用 `vendor/tsumugi/.venv/Scripts/python.exe`，其次使用项目 `.audio-venv/Scripts/python.exe`，最后才使用当前 Python。可选 F0 后端和 Demucs 使用项目 `.audio-venv`。

可用性检查：

```powershell
.\.audio-venv\Scripts\python.exe -c "import basic_pitch, librosa, torch, torchcrepe, demucs; print('audio backends ready')"
```

## 3. 音频路径

完整流程：视频/音频 → WAV → 可选分离 → 一个 MIDI 后端 → MIDI → Sky。

```powershell
.\.venv\Scripts\python.exe scripts\audio_pipeline.py examples\song.mp4 --out-dir outputs\song --backend tsumugi --separate --stem guitar --key C --shift -1 --chromatic-policy nearest
```

已有音频可跳过视频抽取：

```powershell
.\.venv\Scripts\python.exe scripts\audio_to_midi.py input.wav --out-midi outputs\song\midi\tsumugi.mid --backend tsumugi --model guitar_v1_5 --device auto --merge-onset-ms 50
```

后端说明：Tsumugi 适合 guitar stem 并保留吉他和弦；Basic Pitch 是通用基线；pYIN 和 torchcrepe 只产生单旋律。若需要换后端，重新运行 `audio_to_midi.py` 或在 GUI 中选择，不改变后续 MIDI→Sky 阶段。

## 4. 简谱路径

`examples/simple.jianpu.txt` 可直接验证简谱 → MIDI → Sky：

```powershell
.\.venv\Scripts\python.exe scripts\jianpu_to_midi.py examples\simple.jianpu.txt --out-dir outputs\simple-jianpu --to-sky
```

也可以先只生成 MIDI，试听确认后再运行 `midi_to_sky.py`。

## 5. MIDI 转谱参数

```powershell
.\.venv\Scripts\python.exe scripts\midi_to_sky.py input.mid --out-dir outputs\song-sky --key C --shift -1 --chromatic-policy nearest
```

参数要点：

- `--key`：当前大调根音；
- `--shift`：在 15 个连续 Sky 音阶位置上的整体平移；
- `--chromatic-policy`：半音 `drop`、`nearest` 或 `error`；
- `--subdivisions`：每拍量化格，默认 4；
- `--title`：覆盖谱面标题；
- `--desktop-pages`：额外生成横版 6×4 PNG，默认不生成。

默认输出黑白/彩色 JSON、手机竖版彩色分页 PNG、音符侧车和报告。每首歌的手机 PNG 列表第一项是 `*-000.png` 黄色标题封面，随后才是 `*-001.png` 等谱页；封面不计入谱页右上角的 `1/10` 页码。`artifacts.cover_png` 指向封面，封面不写入可演奏 JSON 的 `images`。手机竖版每页最多 32 张逻辑图。需要横版分页 PNG 时显式添加 `--desktop-pages`。

升级前已经生成的结果可以批量补封面：

```powershell
.\.venv\Scripts\python.exe -m scripts.add_cover_pages outputs
```

该命令只读取已有的 `001` PNG，生成 `000` 封面并更新 artifact 列表，不会修改谱面 JSON 的 `images` 音符内容。

## 6. 旧 Sky TXT 兼容路径

```powershell
.\.venv\Scripts\python.exe scripts\txt_to_color_sky.py input-sky.txt --out-dir outputs\legacy-sky --song-index 0
```

它支持 UTF-8/UTF-16 JSON TXT、多个歌曲对象和 `1Key0` 等旧式键名。默认只生成 JSON、`000` 黄色标题封面和手机竖版彩色 PNG；需要额外生成横版 PNG 时添加 `--desktop-pages`。旧格式的输出仍可在静态播放器中加载。

## 7. GUI 与播放器

双击根目录的 `启动音乐转换器.cmd`，可以选择输入、输出、后端和是否分离。调性、BPM、半音策略、歌曲序号、设备以及 Demucs 细节位于“高级设置”；GUI 会按输入类型禁用无关选项。

双击 `启动彩谱试听器.cmd` 或打开 `player/index.html`。空格播放并推进，自动播放按 `source_time` 调度；点击缩略图、图号和重置均静默。浏览器会合成和弦与余音，不需要原曲音频。

## 8. 验证与排查

```powershell
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests\player-core.test.mjs
node --check player\player.js
.\.venv\Scripts\python.exe -m compileall -q scripts
```

常见问题：

- 找不到 ffmpeg：安装后重新打开终端，确认 `ffmpeg -version` 可执行；
- 找不到 Tsumugi：执行 `git submodule update --init --recursive`；
- 找不到 checkpoint：检查文件名和 `vendor/tsumugi/checkpoints/` 位置；
- 音频环境依赖冲突：删除本地 `.audio-venv` 后重新运行安装脚本，或手动按锁定文件重建 Tsumugi `.venv`；
- 彩谱音域不合适：先查看 `*.report.json`，再调整 `--shift` 或半音策略。
