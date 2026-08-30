# Sky 彩谱转换器与试听器

## 启动

在项目根目录双击：

- `启动音乐转换器.cmd`：统一转换 GUI；
- `启动彩谱转换器.cmd`：旧 SkyStudio / 画世界 JSON TXT 兼容 GUI；
- `启动彩谱试听器.cmd`：静态彩谱播放器。

三个入口都使用项目相对路径，不依赖创建者的 Windows 用户目录。也可以直接打开 `player/index.html`。

## 生成彩谱

旧 SkyStudio / 画世界 JSON TXT：

```powershell
.\.venv\Scripts\python.exe scripts\txt_to_color_sky.py input-sky.txt --out-dir outputs\legacy-sky --song-index 0
```

MIDI：

```powershell
.\.venv\Scripts\python.exe scripts\midi_to_sky.py input.mid --out-dir outputs\song-sky --key C --shift -1 --chromatic-policy nearest
```

输出包括结构化 JSON、标准分页 PNG、手机竖版分页 PNG、音符侧车和报告。手机竖版每页最多 32 张逻辑图、4 列排列，右上角显示 `当前页/总页数`，例如 `1/10`。手机版只绘制标题、页码和无按键文字的 5×3 方格，不渲染字幕。

彩谱规则：同一时间的音符合为和弦；连续源帧只有在按键集合与当前图中所有已放入帧都不重叠时，才压入下一层；一旦重叠就新建逻辑图。每张逻辑图最多黑、红、蓝三层，不按时间间隔强行合并。原始 `time` / `source_time` 保留在 JSON 中，供自动播放和调试使用。

## 播放与调试

播放器支持加载原始 Sky JSON TXT、`*.sky.json` 和 `*.color.json`。加载谱面后：

- 空格播放当前层所有音符，并推进到下一层或下一张图；
- “按时间自动播放”按 JSON 中保存的 `source_time` 调度，点击再次停止；
- 点击缩略图、图号输入、前后按钮和重置都会静默定位，并停止自动播放；
- 长按空格不会因为 `KeyboardEvent.repeat` 连续推进；
- 音量控制后续音符音量；移调支持 `-12..+12` 半音；
- 同一层的和弦同时发声，快速操作时允许余音自然重叠。

播放器不播放原曲，也不依赖外部音频；声音由浏览器 Web Audio 合成。自动模式只使用谱面保存的源时间间隔，不读取 BPM，也不改变彩谱的逻辑图排列。
