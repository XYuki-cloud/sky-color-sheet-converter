# 项目架构

## 正式数据流

```text
视频/音频
   │
   ├─ ffmpeg 制作单声道 WAV
   ├─ Demucs 分离（可选，选择 stem）
   └─ 一个音频 MIDI 后端
          ├─ Tsumugi guitar_v1_5（默认）
          ├─ Basic Pitch
          ├─ pYIN（单旋律）
          └─ torchcrepe（单旋律）
                    │
简谱 ────────────────┘
                    ↓
                 MIDI 中间层
                    ↓
          黑白 Sky 谱 + 黑红蓝彩谱
```

SkyStudio / 画世界 JSON TXT 是独立的兼容入口，不经过音频识别。正式维护的主路径只有“音频到 Sky”和“简谱到 Sky”。

## 模块边界

- `scripts/audio_pipeline.py`：识别媒体类型、视频抽音频、可选 Demucs 分离、调用一个音频后端并继续转 Sky；
- `scripts/audio_to_midi.py`：音频后端注册表和适配器，重型依赖按需导入或启动；
- `scripts/jianpu_to_midi.py`：确定性的简谱解析和 MIDI 写入；
- `scripts/midi_to_sky.py`：唯一正式的 MIDI 读取、映射和黑白/彩谱生成入口；
- `scripts/sky_mapping.py`：公开的调性、15 键音阶索引和 Sky 标签映射；
- `scripts/txt_to_color_sky.py`：旧 SkyStudio / 画世界 JSON TXT 兼容入口；
- `scripts/music_events.py`：带毫秒起止时间的规范音符事件；
- `scripts/music_converter_gui.py`：统一 Tkinter GUI；
- `player/`：独立静态 HTML/JavaScript 播放器，不依赖服务器或外部音频；
- `vendor/tsumugi/`：固定提交的 Git 子模块，不把模型权重纳入主仓库。

一次性对比实验和旧 MIDI 压缩实现不属于公开发布分支；它们不作为正式入口，也不纳入默认测试。

## MIDI 中间层

音频后端和简谱解析最终都产生 MIDI。MIDI 可以在进入 Sky 前单独试听、编辑或替换，是两条正式路径之间的稳定边界。读取 MIDI 后，转换器把事件规范化为：

```json
{
  "start_ms": 1200,
  "duration_ms": 300,
  "pitch": 60,
  "velocity": 80,
  "source": "midi"
}
```

音频识别的置信度只保留在音符侧车文件；MIDI→Sky 不依赖某个具体识别模型。

## MIDI 到 Sky 的规则

1. 读取所有轨道的 `note_on` / `note_off`，按 tempo 换算源时间；
2. 按每拍四格量化起音，同一位置的音符合为一个和弦帧；
3. 用选定大调把音符映射到连续 15 个 Sky 音阶位置；
4. 黑白谱每个源帧保留一个黑层；
5. 彩谱按连续源帧装箱：新帧必须与当前图所有已放入帧的按键集合完全不相交，才放入下一层；发生交集就开启下一张图；
6. 每张彩谱最多三层，颜色严格为黑、红、蓝。

因此彩谱压缩不会因为时间接近就把两个重叠帧误合并，也不会把不同时间的音符误当作和弦。`source_time` 仅用于报告和播放器自动播放。所有超出窗口、丢弃或就近替换的音符写入报告。

## GUI 状态

GUI 的常用区域保留输入、输出、音频后端、分离开关和 stem。调性、BPM、半音策略、Sky TXT 歌曲序号、设备、Demucs 模型和 shifts 收纳在可展开的高级设置中。

- 视频和音频：启用后端、分离、stem、设备和 Demucs 选项；
- MIDI：只启用调性、半音策略和通用转换按钮；
- 简谱：额外启用 BPM；
- Sky JSON TXT：只启用歌曲序号和旧 TXT 转换相关选项。

这种状态分发同时用于参数校验，避免用户填写无关字段而阻塞转换。

## 可再现性边界

主仓库只管理源码、测试、文档、示例和启动入口。Tsumugi 通过 `.gitmodules` 固定源码提交；模型权重、音视频、真实歌曲谱面、虚拟环境、日志和 `outputs/` 均由使用者在本机准备或生成。
