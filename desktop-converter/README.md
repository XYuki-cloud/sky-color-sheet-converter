# Sky 彩色谱转换器

> 把视频、音频、MIDI 或数字简谱，整理成适合《光遇》15 键演奏的黑白谱和彩色谱。

这是一个面向 Windows 的源码项目。它把音频识别、MIDI 编辑和 Sky 谱面渲染拆成清晰的阶段，方便试听、替换中间结果、调整音域，以及在生成彩谱后进行人工演奏调试。

项目名称中的“彩色谱”指黑、红、蓝三层逻辑图：每张图最多包含三个连续音符帧。彩色层不是节奏记谱，而是为了在不发生按键重叠的前提下压缩显示；节奏仍保存在中间数据中，并由播放器提供可选的按时间自动播放。

## 功能概览

| 能力 | 说明 |
| --- | --- |
| 音频路径 | 视频/音频 → WAV → 可选 Demucs 分离 → 选择 MIDI 后端 → MIDI → Sky 谱 |
| 简谱路径 | 带时值数字简谱 → MIDI → 黑白谱和彩色谱 |
| MIDI 中间层 | 可以先单独试听或编辑 MIDI，再进入谱面转换 |
| 音频后端 | Tsumugi `guitar_v1_5`、Basic Pitch、pYIN、torchcrepe |
| 旧格式兼容 | SkyStudio / 画世界 JSON TXT，可合并同一时刻和弦 |
| 彩谱渲染 | 黑→红→蓝，默认手机竖版分页，横版分页可选 |
| 播放调试 | 本地静态播放器，支持空格手动推进、鼠标跳转和按时间自动播放 |
| 诊断报告 | 记录映射、丢弃、半音就近替换、源时间和生成文件 |

正式维护的主路径只有两条：

```text
视频 / MP3 / WAV
        │
        ├─ 视频经 ffmpeg 制作单声道 WAV
        ├─ 可选 Demucs 分离 guitar、vocals 等 stem
        └─ 选择一个 MIDI 后端
                    │
简谱 ────────────────┘
                    ↓
                 MIDI 中间层
                    ↓
          黑白 Sky 谱 + 黑红蓝彩色谱
```

旧 SkyStudio / 画世界 TXT 是独立的兼容入口，不参与音频识别。

## 快速开始

### 环境要求

- Windows 10/11；PowerShell；
- Python 3.10–3.12，推荐 Python 3.12；
- Git；初始化 Tsumugi 子模块时需要网络；
- Node.js：只在运行播放器测试时需要，播放器本身可直接打开 HTML；
- `ffmpeg`：处理视频或需要统一抽取 WAV 时必须安装并加入 PATH；
- `uv`：可选，用于自动同步 Tsumugi 自己的锁定依赖。

核心谱面转换只需要 `mido` 和 Pillow。音频模型、Demucs 和开发测试依赖分开安装，不会因为只处理 MIDI 或简谱而强制安装全部机器学习依赖。

### 克隆项目

从仓库根目录克隆项目：

```powershell
git clone --recurse-submodules https://github.com/XYuki-cloud/sky-color-sheet-converter.git
cd sky-color-sheet-converter\desktop-converter
```

如果已经克隆但没有初始化子模块：

```powershell
git submodule update --init --recursive
```

### 安装核心环境

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements-core.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

核心依赖包括：

- `mido`：读取和写入 MIDI；
- `Pillow`：生成标准和彩色手机 PNG；
- `pytest`：仅用于开发测试。

### 安装音频环境

先确认：

```powershell
ffmpeg -version
```

然后执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup_audio_backend.ps1
```

脚本会在项目内创建 `.audio-venv`，并安装 Basic Pitch、librosa/pYIN、torchcrepe 和 Demucs。若检测到 `uv`，还会尝试在 `vendor\tsumugi` 中执行 `uv sync --locked`。

Tsumugi 的模型权重不在仓库中。使用 `guitar_v1_5` 前，需要按照上游项目的许可和说明自行准备：

```text
vendor/tsumugi/checkpoints/best_model_guitar_v1_5.pth
```

快速检查可选音频环境：

```powershell
.\.audio-venv\Scripts\python.exe -c "import basic_pitch, librosa, torch, torchcrepe, demucs; print('audio backends ready')"
```

如果只处理 MIDI 或简谱，可以跳过音频环境、ffmpeg、Demucs 和 Tsumugi 权重。

## 一键入口

在项目根目录双击即可使用：

| 文件 | 用途 |
| --- | --- |
| `启动音乐转换器.cmd` | 统一 GUI，自动识别视频、音频、MIDI、简谱和旧 Sky TXT |
| `启动彩谱转换器.cmd` | 旧 SkyStudio / 画世界 JSON TXT 兼容 GUI |
| `启动彩谱试听器.cmd` | 打开彩谱静态试听器 |

三个启动器都使用项目相对路径，并优先调用项目 `.venv`，项目移动到其他文件夹后仍可使用。工作区根目录也保留了三个兼容启动器，它们会转发到本目录。Windows 快捷方式 `.lnk` 不属于源码项目，仓库不会保存绑定本机路径的快捷方式。

统一 GUI 的常用区域只保留输入、输出、MIDI 后端、是否分离和 stem；调性、BPM、半音策略、设备、Demucs 模型及 shifts 放在“高级设置”。GUI 会根据输入类型禁用无关选项，减少误配置。

## 路径一：音频到 Sky

### 一条命令完成视频到谱面

下面的例子使用 Tsumugi 吉他模型，并先用 Demucs 选择 `guitar` stem：

```powershell
.\.venv\Scripts\python.exe scripts\audio_pipeline.py `
  "path\to\song.mp4" `
  --out-dir "outputs\song" `
  --backend tsumugi `
  --separate `
  --stem guitar `
  --tsumugi-model guitar_v1_5 `
  --device auto `
  --merge-onset-ms 50 `
  --key C `
  --subdivisions 4 `
  --shift -1 `
  --chromatic-policy nearest `
  --title "示例歌曲"
```

如果输入已经是较干净的 guitar WAV，可以省略 `--separate`：

```powershell
.\.venv\Scripts\python.exe scripts\audio_pipeline.py `
  "path\to\guitar.wav" `
  --out-dir "outputs\guitar-song" `
  --backend tsumugi `
  --tsumugi-model guitar_v1_5 `
  --device auto `
  --key C `
  --shift -1 `
  --chromatic-policy nearest
```

没有安装 Demucs 时，不要把 `--separate` 当成可用选项；程序会明确报错，不会悄悄把混音当作分离后的 guitar。分离得到的 stem 也可能包含漏出的其他乐器，最终应以 MIDI 试听结果为准。

### 分阶段运行，先试听 MIDI

如果想在转谱前判断音频识别是否正确，可以把 MIDI 单独输出：

```powershell
.\.venv\Scripts\python.exe scripts\audio_to_midi.py `
  "path\to\guitar.wav" `
  --out-midi "outputs\song\midi\tsumugi.mid" `
  --backend tsumugi `
  --model guitar_v1_5 `
  --device auto `
  --merge-onset-ms 50
```

确认 MIDI 后，再转换为黑白和彩色谱：

```powershell
.\.venv\Scripts\python.exe scripts\midi_to_sky.py `
  "outputs\song\midi\tsumugi.mid" `
  --out-dir "outputs\song\sky" `
  --key C `
  --subdivisions 4 `
  --shift -1 `
  --chromatic-policy nearest `
  --title "示例歌曲"
```

### MIDI 后端选择

一次正式流程只选择一个后端。不同后端输出的 MIDI 可以分别试听，再选定一个进入 Sky 转换：

| 后端 | 适合场景 | 是否保留和弦 |
| --- | --- | --- |
| `tsumugi` | 已分离或较干净的吉他音频；默认模型为 `guitar_v1_5` | 可以，取决于模型识别结果 |
| `basic_pitch` | 通用音频转 MIDI 基线 | 可以，但混音输入容易混入其他乐器 |
| `pyin` | 想提取一条连续主旋律 | 不生成和弦 |
| `torchcrepe` | 另一种神经网络单旋律路线 | 不生成和弦 |

可以把同一音频换后端测试：

```powershell
.\.venv\Scripts\python.exe scripts\audio_to_midi.py `
  "path\to\audio.wav" `
  --out-midi "outputs\song\midi\basic-pitch.mid" `
  --backend basic_pitch
```

可选后端的运行环境和模型权重不属于本项目源码。缺少某个后端时，程序会报告具体的安装或配置原因。

### 音频路径的输出结构

使用 `audio_pipeline.py --out-dir outputs\song` 时，典型目录如下：

```text
outputs/song/
├── audio/                         # 视频抽出的单声道 WAV
├── separation/                    # Demucs stem（如果启用）
├── midi/
│   └── tsumugi__guitar.mid        # 选定后端生成的 MIDI
├── sky/
│   └── tsumugi__guitar/           # 黑白/彩色 Sky 结果
└── pipeline.report.json           # 各阶段路径、参数和统计
```

`outputs/` 默认被 Git 忽略，真实歌曲、音频和识别结果不会随源码提交。

## 路径二：数字简谱到 Sky

简谱是确定性的输入，不需要音频模型。项目提供了可直接运行的示例：

```text
@title=简单示例
@key=C
@bpm=100
1 1 5 5 6 6 5@2
4 4 3 3 2 2 1@2
```

文件位置：`examples/simple.jianpu.txt`。

直接生成 MIDI、黑白谱和彩色谱：

```powershell
.\.venv\Scripts\python.exe scripts\jianpu_to_midi.py `
  examples\simple.jianpu.txt `
  --out-dir outputs\simple-jianpu `
  --to-sky
```

也可以先只生成 MIDI，试听确认后再执行 `midi_to_sky.py`。

### 简谱语法速查

| 写法 | 含义 |
| --- | --- |
| `1`–`7` | 当前调性的一个八度内音级 |
| `0` | 休止 |
| `1+` / `1-` | 向上 / 向下一个八度；可连续使用多个标记 |
| `#4`、`b7` | 升、降半音 |
| `1@2` | 该音持续 2 拍；没有 `@` 时默认 1 拍 |
| `1^2^3` | 在一个时值内平均分成三个连续音 |
| `{1,3,5}` | 同时出现的和弦 |
| `135` | 兼容写法，表示同一时刻的和弦 |
| `@title`、`@key`、`@bpm`、`@time` | 标题、调性、BPM、拍号元数据 |

简谱中的 BPM 和时值只影响 MIDI 的试听节奏；进入彩谱后，显示压缩仍遵守按键不重叠规则。

## MIDI 到黑白谱和彩色谱

`scripts/midi_to_sky.py` 是唯一正式的 MIDI 转谱入口：

```powershell
.\.venv\Scripts\python.exe scripts\midi_to_sky.py `
  "path\to\song.mid" `
  --out-dir "outputs\song-sky" `
  --key C `
  --subdivisions 4 `
  --shift -1 `
  --chromatic-policy nearest
```

### 转换规则

1. 读取 MIDI 全部轨道的 `note_on` / `note_off`，按 tempo 换算源时间；
2. 按每拍若干格量化音符起音，默认每拍 4 格；同一量化位置的音符合并为一个和弦帧；
3. 依据指定大调，把音符映射到连续的 15 个 Sky 按键 `A1`–`C5`；
4. 黑白谱保留每个源帧的一层黑色按键；
5. 彩谱按源帧顺序装箱：只有当新帧与当前图中所有已放入帧的按键集合都没有交集时，才加入下一层；出现交集就开启下一张图；
6. 每张彩谱最多三层，颜色顺序固定为黑 → 红 → 蓝。

这里的“压缩”只利用按键集合不重叠这一事实，不会为了接近的时间强行合并重叠按键。`source_time` 会写入 JSON 和报告，供播放器自动播放；它不是彩谱图像本身的节奏定义。

### 关键参数

| 参数 | 作用 |
| --- | --- |
| `--key C` | 指定大调根音，例如 `C`、`F`、`Bb` |
| `--subdivisions 4` | 每拍量化格数，默认 4；数值越大，时间分辨率越细 |
| `--shift -1` | 按 Sky 音阶级数整体平移；不是半音移调 |
| `--chromatic-policy drop` | 半音处理：`drop` 丢弃并报告、`nearest` 就近映射、`error` 遇到即停止 |
| `--title` | 覆盖输出谱面标题 |
| `--desktop-pages` | 额外生成横版 6×4 PNG；默认不生成 |

建议先查看 `*.report.json` 再决定 `--shift` 和半音策略。报告会列出超出 15 键范围、被丢弃和被就近替换的音符，不静默隐藏转换损失。

### 生成文件

对于 `song.mid`，输出目录通常包括：

```text
song.sky.json                  # 黑白谱结构化数据
song.color.json                # sky-color-v1 彩谱数据
song.color-mobile-000.png      # 手机竖版黄色标题封面（基于第 001 页）
song.color-mobile-001.png      # 手机竖版彩色分页图，4 列，每页最多 32 张逻辑图
song.notes.json                # 规范化 MIDI 音符侧车数据
song.report.json               # 映射和告警报告
```

默认只生成 JSON 和手机竖版 PNG。需要电脑端横版分页图时，给 `midi_to_sky.py` 或 `txt_to_color_sky.py` 增加 `--desktop-pages`；该选项会额外生成黑白/彩色横版 PNG，其中彩色分页为 6×4、每页 24 张逻辑图。

每首歌都会额外生成一个 `000` 封面页：它复制第一张分页图，只在中部叠加带深色描边的黄色曲名，适合直接作为发布作品的首图。真正的谱页仍从 `001` 开始，右上角保留 `1/10` 这种“当前页/总页数”序号；封面不计入这个分数。`artifacts.mobile_png_pages` 和 `artifacts.png_pages` 都把 `000` 放在列表第一项，并额外提供 `cover_png`，方便批量上传脚本识别封面。封面不是 `*.color.json` / `*.sky.json` 的可演奏 `images`，播放器不会为它发声。PNG 中每个音符格整格填色，空格保持白色。

## 旧 SkyStudio / 画世界 TXT 兼容

对于包含 `songNotes` 的 JSON TXT，可以直接转换：

```powershell
.\.venv\Scripts\python.exe scripts\txt_to_color_sky.py `
  "path\to\sky-song.txt" `
  --out-dir outputs\legacy-sky `
  --song-index 0
```

兼容入口支持 UTF-8/UTF-16 JSON TXT、多个歌曲对象，以及 `1Key0` 到 `1Key14` 这类旧键名。同一 `time` 的音符会先合并为和弦；同一时刻重复的同一按键会去重并产生警告。

这个入口只负责旧谱面格式，不调用音频模型，也不改变正式 MIDI→Sky 的映射规则。

## 彩谱试听器

双击 `启动彩谱试听器.cmd`，或直接打开 `player/index.html`。播放器是独立静态 HTML/JavaScript，不需要服务器、Node.js 或原曲音频。

### 两种播放方式

- 空格：播放当前层的全部按键，然后推进到下一层/下一张图；
- 按时间自动播放：按照谱面保留的 `source_time` 播放和推进；
- 最后一层之后再次按空格：静默回到第一张图；
- 点击缩略图、图号、上一张/下一张：静默跳转，不发声；
- 长按空格不会因为 `KeyboardEvent.repeat` 重复推进；
- 支持和弦同时发声、音量调节和 `-12..+12` 半音全局移调，快速操作时余音可以自然重叠。

播放器可载入原始黑白 TXT、`*.sky.json` 和 `*.color.json`。如果 TXT 内有多首歌曲，会先显示歌曲选择框。

## 项目结构

```text
.
├── scripts/
│   ├── audio_pipeline.py       # 视频/音频 → MIDI → Sky 总流程
│   ├── audio_to_midi.py        # 音频后端适配器
│   ├── jianpu_to_midi.py       # 数字简谱 → MIDI
│   ├── midi_to_sky.py          # 唯一正式 MIDI → Sky 入口
│   ├── sky_mapping.py          # 15 键音阶映射公共模块
│   ├── add_cover_pages.py      # 给旧 outputs 补生成 000 封面
│   ├── txt_to_color_sky.py     # 旧 Sky TXT 兼容入口
│   └── music_converter_gui.py  # 统一 Tkinter GUI
├── player/                     # 静态彩谱播放器
├── tests/                      # Python 和 Node 回归测试
├── examples/                   # 可提交的小型简谱示例
├── test_songs/                 # 有来源说明的小型 MIDI 测试素材
├── docs/                       # 架构、运行手册、决策和播放器说明
├── vendor/tsumugi/             # 固定提交的 Git 子模块
├── requirements-*.txt          # 核心、音频、分离和开发依赖
├── 启动音乐转换器.cmd           # Windows GUI 启动器
└── ../LICENSE                 # 根目录共享 Apache-2.0 许可证
```

本机运行时还可能出现以下目录，但它们不属于公开源码：`.venv/`、`.audio-venv/`、`outputs/`、`logs/`、模型 checkpoint、音视频和 Codex 临时文件。整理后的工作区还会在根目录保留已有的 `待转谱/`、`outputs/`、`logs/`、`archive/` 和 `checkpoints/` 用户数据；新克隆的桌面项目可以在自身目录中创建同名目录。`.gitignore` 已将这些内容排除。

## 测试与开发

### 运行测试

```powershell
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests\player-core.test.mjs
node --check player\player.js
.\.venv\Scripts\python.exe -m compileall -q scripts
```

只做一个不依赖音频模型的端到端冒烟测试：

```powershell
.\.venv\Scripts\python.exe scripts\midi_to_sky.py `
  test_songs\ode_to_joy.mid `
  --out-dir outputs\smoke-test `
  --key C `
  --chromatic-policy nearest
```

开发时请保持以下边界：

- 新的正式 MIDI 行为写入 `scripts/midi_to_sky.py` 及其测试；
- 音符数据优先经过 `scripts/music_events.py` 的规范化结构；
- 音频后端只负责产生 MIDI，不在后端内部复制 Sky 彩谱逻辑；
- 实验性方案请放在本地未跟踪目录或独立私有分支，不要把真实歌曲和实验产物带入发布分支；
- 不提交模型、音视频、真实歌曲谱面、生成 PNG、虚拟环境、日志或个人快捷方式。

贡献规范见根目录的 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

## 版权与第三方边界

本项目是转换和试听工具，不授予任何歌曲、录音、演奏或游戏素材的版权。使用者需要自行确认输入音频、MIDI、简谱、生成谱面以及发布平台上的使用方式符合适用法律、平台规则和权利人的授权范围。即使标注原作者，也不等于自动获得录音或改编传播许可。

仓库不分发真实歌曲、原曲音频、模型权重或外部可执行文件：

- Tsumugi 以 Git 子模块提供源码引用，固定版本和模型边界见根目录的 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)；
- `guitar_v1_5` checkpoint 由使用者自行准备，并遵守上游模型条款；
- Sky Music Sheet Maker 只作为旧格式参考，不是正式运行依赖；
- ffmpeg、Python 包、Demucs、音频模型和 Node.js 都有各自的许可证与分发条件。

详见根目录的 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 许可证

本项目源码使用根目录的 [Apache License 2.0](../LICENSE)。第三方子模块、模型、依赖和用户输入内容不随本许可证重新授权。
