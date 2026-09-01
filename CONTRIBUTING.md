# Contributing

感谢贡献。请先阅读根目录的项目说明、行为准则和第三方声明，再提交针对单一问题的变更。

## 开发环境

桌面项目：

```powershell
Set-Location .\desktop-converter
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-core.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

只有涉及音频识别时才需要按 [`desktop-converter/docs/RUNBOOK.md`](desktop-converter/docs/RUNBOOK.md) 配置 ffmpeg、音频环境、Tsumugi 子模块和模型权重。

Android 项目使用各自目录内的 Gradle Wrapper，不需要 Python 或 Flutter：

```powershell
Set-Location .\midi-black
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon

Set-Location ..\black-color
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon
```

## 提交前检查

```powershell
Set-Location .\desktop-converter
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests\player-core.test.mjs
node --check player\player.js
.\.venv\Scripts\python.exe -m compileall -q scripts
git diff --check
```

修改转换规则时，请同时补充回归测试和文档。不要提交真实歌曲、音视频、模型权重、虚拟环境、日志、生成结果、密钥或 Windows `.lnk` 快捷方式。小型且可公开再分发的 MIDI 只能放在 `desktop-converter/test_songs/`，并保留来源说明。

## 提交规范

- 每个提交只解决一个清晰问题；
- 使用简洁的英文 Conventional Commits 信息，例如 `feat: add midi mapping helper`；
- 不修改或覆盖与当前任务无关的用户产物；
- 提交前检查 `git status`，确认没有个人路径、密钥或大文件；
- 不要把构建产物或本地 APK 提交到仓库。

许可证和第三方边界见 [`LICENSE`](LICENSE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
