# 第三方代码与模型

## Tsumugi

`vendor/tsumugi/` 是 Git 子模块，来源为：

`https://github.com/anime-song/tsumugi.git`

主项目固定到已验证的提交，并通过 `.gitmodules` 和 Gitlink 记录。初始化或更新：

```powershell
git submodule update --init --recursive
```

当前验证提交为 `68534106370860169148f09d168db105dbc17b00`。

`guitar_v1_5` 的 checkpoint 不在仓库中。请由使用者自行放置：

```text
vendor/tsumugi/checkpoints/best_model_guitar_v1_5.pth
```

Tsumugi 自身的许可证和依赖以子模块内的官方文件为准；主项目只通过 `scripts/audio_to_midi.py` 调用其公开推理入口。

## SkyMusic 参考

Sky Music Sheet Maker 只用于理解旧 SkyStudio / 画世界谱面格式和兼容行为，不是本项目的运行依赖。参考链接和许可证信息见 `THIRD_PARTY_NOTICES.md`。可执行文件、模型、音频和其他外部二进制不属于本项目源码。
