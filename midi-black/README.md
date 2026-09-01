# MIDI Black

`MIDI Black` 是一个独立的原生 Android 应用：把标准 MIDI 文件转换为适用于《光遇》15 键演奏的黑白谱，并可同时生成彩谱、手机谱页和结构化报告。

[返回仓库总览](../README.md) · [贡献指南](../CONTRIBUTING.md) · [安全策略](../SECURITY.md)

## 项目定位

这个工程只负责 **MIDI → Sky 谱面**。它不依赖仓库中的桌面 Python 工具，也不依赖 `black-color` Android 工程；两个 Android 项目可以分别构建、安装和发布。

| 项目属性 | 值 |
| --- | --- |
| 显示名称 | `MIDI Black` |
| Android 包名 | `com.xyuki.skyconverter` |
| 最低版本 | Android 8.0（API 26） |
| 编译/目标版本 | Android SDK 35 |
| 技术栈 | Java 17、Android SDK、SAF、原生 View |
| UI 依赖 | 无 AndroidX、无第三方运行时 |

## 功能

- 通过系统文件夹选择器批量选择 MIDI，递归扫描子文件夹；
- 支持 `.mid` 和 `.midi`；
- 读取标准 MIDI 的 PPQ 时基、多轨道音符、速度变化和轨道标题；
- 生成 `sky-black-v1` 黑白谱、`sky-color-v1` 彩谱、`sky-note-events-v1` 音符侧车和映射报告；
- 生成适合手机查看的四列分页 PNG，并输出 `000` 黄色标题封面；
- 配置调性、每拍细分、半音处理策略和音阶位移；
- 单个文件失败后继续处理，并显示进度、错误和取消状态；
- 记住系统授予的输入/输出目录 URI 和上次转换参数。

## 使用流程

1. 启动应用，选择输入文件或输入文件夹；
2. 检查文件列表和转换参数；
3. 选择一个与输入目录不同的输出文件夹；
4. 点击生成，等待每个 MIDI 的状态更新；
5. 在输出目录中查看 JSON、PNG 和报告。

应用不会静默覆盖旧结果。相同名称的结果目录会自动使用 `歌曲 (2)`、`歌曲 (3)` 等副本名称；同一次任务在已确定的结果目录内更新本次生成的同名产物。

## 输入与输出

单个 `song.mid` 的典型输出如下：

```text
song/
├── song.sky.json
├── song.color.json
├── song.notes.json
├── song.report.json
├── song.sky-000.png
├── song.sky-001.png
├── song.color-mobile-000.png
└── song.color-mobile-001.png
```

`000` 是标题封面，不进入 JSON 的可演奏 `images`；从 `001` 开始才是实际谱页。黑白页使用黑色按键，彩色页使用黑、红、青色显示；JSON 层仍保留兼容色值 `#000000`、`#FF0000` 和 `#0000FF`。

MIDI 音符会映射到 Sky 15 键。超出 `A1–C5` 的音符会写入报告并跳过；半音处理策略为：

- `drop`：丢弃不属于当前大调的半音，并记录告警；
- `nearest`：映射到最近的大调音；
- `error`：遇到半音时使当前 MIDI 失败。

彩谱按照源帧顺序压缩。只有连续帧之间的按键集合不重叠时，才会依次进入黑、红、蓝层；每张逻辑图最多三层。

当前应用不包含音频识别、MP3/视频转 MIDI、旧 SkyStudio TXT 导入或桌面试听器，这些能力位于 `desktop-converter` 或 `black-color`。

## 文件访问与隐私

应用只使用 Android Storage Access Framework（SAF）：

- 输入和输出通过系统选择器获取 URI 授权；
- 不申请整机存储权限、录音权限或网络权限；
- 不把 Windows 文件路径拼接成 Android 路径；
- 不上传 MIDI、谱面、音频、播放数据或诊断信息；
- 输入目录和输出目录不能选择为同一个目录；
- 若系统撤销授权，需要重新选择目录。

仓库根目录中的真实歌曲、批量输出、日志和本机缓存默认不纳入 Git。完整边界见仓库根目录的 [README](../README.md)、[许可证](../LICENSE) 和 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 构建

环境要求：JDK 17、Android SDK 35，以及 Windows PowerShell。工程自带 Gradle Wrapper，不需要全局安装 Gradle。

从仓库根目录执行：

```powershell
Set-Location .\midi-black
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
.\gradlew.bat :app:assembleDebug --offline --no-daemon
```

如果中文路径触发 Android Gradle Plugin 的路径处理问题，请把仓库复制到只含 ASCII 字符的目录后重试。Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

提交前还应执行 `git diff --check`，并确认构建目录、APK、真实歌曲和本机路径没有进入提交。
