# Sky Color Sheet Converter

[![CI](https://github.com/XYuki-cloud/sky-color-sheet-converter/actions/workflows/ci.yml/badge.svg)](https://github.com/XYuki-cloud/sky-color-sheet-converter/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

面向《光遇》15 键演奏谱的开源工具集：把音频、视频、MIDI、数字简谱或旧 SkyStudio / 画世界谱面整理为黑白谱和彩谱，并提供桌面端与 Android 端试听能力。

> 彩谱不是重新编曲。黑、红、蓝表示同一张逻辑谱图中的演奏层，节奏仍由 `source_time` 保留并交给播放器处理。

## 项目一览

| 项目 | 定位 | 主要能力 |
| --- | --- | --- |
| [`desktop-converter`](desktop-converter/README.md) | Windows 桌面转换器 | 音频/视频识别、数字简谱、MIDI、旧 Sky TXT、PNG 渲染、静态试听 |
| [`midi-black`](midi-black/README.md) | MIDI Black Android | 批量 MIDI → 黑白谱、彩谱、手机 PNG 和报告 |
| [`black-color`](black-color/README.md) | Aurora Keys Android | 黑白谱/TXT → 彩谱、批量导出、独立试听器和慢速播放 |

两个 Android 工程完全独立，均使用原生 Java 和 Android SDK，不依赖 Python、Flutter、AndroidX 或对方源码。应用包名保持稳定，方便已安装版本升级：

```text
midi-black   → com.xyuki.skyconverter
black-color  → com.xyuki.skycolor.converter
```

## 能力边界

```text
音频 / 视频 ──→ WAV ──→ 可选分离 ──→ MIDI ──┐
数字简谱 ────────────────────────────────┤
旧 SkyStudio / 画世界 JSON TXT ───────────┘
                                           ↓
                                  黑白 Sky 谱
                                           ↓
                              黑 → 红 → 蓝彩谱
                                           ↓
                              手机 PNG / 试听器
```

黑白谱是稳定的中间层：音频识别、MIDI 编辑和谱面编排可以分别检查，不会把模型误差和谱面压缩混在一起。彩谱压缩只在连续帧的按键集合不重叠时进行，每张逻辑图最多三层，顺序固定为黑、红、蓝。

## 快速开始

### 桌面端

环境要求：Windows 10/11、PowerShell、Python 3.10–3.12。核心 MIDI/简谱转换只需要 `mido` 和 Pillow；音频识别额外需要 ffmpeg 以及对应后端。

```powershell
Set-Location .\desktop-converter
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-core.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt

# 运行完整桌面回归测试
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests\player-core.test.mjs
```

双击 `desktop-converter/` 内的中文 `.cmd` 启动器即可打开 GUI 或静态播放器。音频后端、Demucs、Tsumugi 子模块和模型权重属于可选环境，配置方法见 [`desktop-converter/docs/RUNBOOK.md`](desktop-converter/docs/RUNBOOK.md)。

### Android 端

环境要求：JDK 17、Android SDK 35。两个项目都自带 Gradle Wrapper，不需要全局安装 Gradle。

```powershell
# 从仓库根目录执行
Set-Location .\midi-black
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
.\gradlew.bat :app:assembleDebug --offline --no-daemon

Set-Location ..\black-color
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
.\gradlew.bat :app:assembleDebug --offline --no-daemon
```

如果 Windows 中文路径导致 Android Gradle Plugin 报路径错误，请将仓库复制到只含 ASCII 字符的目录后再构建。Debug APK 生成位置分别为：

```text
midi-black/app/build/outputs/apk/debug/app-debug.apk
black-color/app/build/outputs/apk/debug/app-debug.apk
```

## Android 文件与隐私边界

Android 应用使用系统 Storage Access Framework 选择文件和文件夹，并保存用户授予的 URI 权限：

- 不申请整机存储权限、录音权限或网络权限；
- 不把 Windows 路径拼接成 Android 文件路径；
- 不上传输入文件、谱面、音频或播放数据；
- 文件夹模式只递归读取用户明确授权的目录；
- 输出写入用户明确选择的目录；
- 若系统撤销授权，应用要求重新选择，不绕过系统权限。

详细输入格式和限制见 [`midi-black/README.md`](midi-black/README.md) 与 [`black-color/README.md`](black-color/README.md)。

## 输出约定

桌面端和 Android 端共享黑白/彩谱数据边界：

```text
song/
├── song.sky.json                 # sky-black-v1 黑白谱
├── song.color.json               # sky-color-v1 彩谱
├── song.notes.json               # MIDI 规范化音符（桌面/MIDI 路径）
├── song.report.json              # 映射、丢弃和告警信息
├── song.color-mobile-000.png     # 黄色标题封面，不进入可播放 images
└── song.color-mobile-001.png     # 从 001 开始的实际谱页
```

标题可以覆盖 JSON 元数据和封面，但不改变源文件名。已有同名结果不会被静默覆盖，批处理会创建带序号的副本目录。PNG、JSON、报告和真实歌曲默认被 Git 忽略。

## 仓库结构

```text
.
├── desktop-converter/   # Python 桌面工具、播放器、测试、文档和可选子模块
├── midi-black/          # MIDI Black Android 工程
├── black-color/         # Aurora Keys Android 工程
├── .github/workflows/   # 桌面 + 两个 Android 工程的 CI
├── archive/             # 本机实验归档，不发布
├── outputs/             # 本机生成结果，不发布
└── 待转谱/              # 本机输入素材，不发布
```

公开测试素材只放在 `desktop-converter/test_songs/` 或 `desktop-converter/examples/`，并保留来源说明。Tsumugi 以固定提交的 Git 子模块引用；模型权重、音视频和外部可执行文件不随仓库分发。

## 开发与贡献

CI 会在 Windows 环境执行桌面 Python/Node 检查，并分别构建两个 Android Debug APK。提交前请运行对应项目测试，检查 `git diff --check`，确认没有个人路径、密钥、真实歌曲、生成结果或大文件。

- 许可证：[`Apache-2.0`](LICENSE)
- 贡献规范：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- 行为准则：[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)
- 安全问题：[`SECURITY.md`](SECURITY.md)
- 第三方声明：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)

本项目只提供转换、渲染和试听工具，不授予任何歌曲、录音、演奏、游戏素材或用户输入内容的版权。使用者应自行确认输入、生成和发布行为符合适用法律及平台规则。
