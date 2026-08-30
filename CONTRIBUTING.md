# Contributing

感谢贡献。项目目前以 Windows 源码运行方式维护，变更应保持两条正式路径清晰、可复现、可测试。

## 环境

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-core.txt
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
```

只有涉及音频后端时才需要按 `docs/RUNBOOK.md` 初始化音频环境、ffmpeg、Tsumugi 子模块和模型权重。

## 提交前检查

```powershell
.\.venv\Scripts\python.exe -m pytest tests -q
node --test tests\player-core.test.mjs
node --check player\player.js
.\.venv\Scripts\python.exe -m compileall -q scripts
git diff --check
```

不要把真实歌曲、音视频、模型权重、虚拟环境、日志、生成结果或 Windows `.lnk` 提交到仓库。小型、可公开再分发的测试 MIDI 可以放在 `test_songs/`；通用简谱示例放在 `examples/`。

## 提交规范

- 每个提交只解决一个清晰问题；
- 提交信息使用简洁的英文 Conventional Commits 风格，例如 `feat: add midi mapping helper`；
- 修改转换规则时同时补充回归测试和文档；
- 不修改或覆盖与当前任务无关的用户产物；
- 提交前检查 `git status`，确认没有个人路径、密钥或大文件。

Apache-2.0 许可证和第三方边界见根目录的 `LICENSE` 与
`THIRD_PARTY_NOTICES.md`。
