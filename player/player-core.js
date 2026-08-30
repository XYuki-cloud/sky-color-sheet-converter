(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.SkyPlayerCore = factory();
  }
})(typeof globalThis === "object" ? globalThis : this, function () {
  "use strict";

  const KEY_LABELS = [];
  for (const row of ["A", "B", "C"]) {
    for (let column = 1; column <= 5; column += 1) {
      KEY_LABELS.push(`${row}${column}`);
    }
  }
  const KEY_ORDER = new Map(KEY_LABELS.map((key, index) => [key, index]));
  const RAW_KEY_PATTERN = /^(?:\d+)?Key(\d+)$/i;
  const COLORS = [
    { name: "black", hex: "#000000" },
    { name: "red", hex: "#FF0000" },
    { name: "blue", hex: "#0000FF" },
  ];
  const SCALE_INTERVALS = [0, 2, 4, 5, 7, 9, 11];
  const KEY_PITCH_CLASSES = {
    C: 0,
    "C#": 1,
    DB: 1,
    D: 2,
    "D#": 3,
    EB: 3,
    E: 4,
    F: 5,
    "F#": 6,
    GB: 6,
    G: 7,
    "G#": 8,
    AB: 8,
    A: 9,
    "A#": 10,
    BB: 10,
    B: 11,
  };

  function formatError(message) {
    return new Error(`谱面格式错误：${message}`);
  }

  function sortKeys(keys) {
    const unique = [...new Set(keys)];
    for (const key of unique) {
      if (!KEY_ORDER.has(key)) {
        throw formatError(`不支持的 Sky 按键：${key}`);
      }
    }
    unique.sort((left, right) => KEY_ORDER.get(left) - KEY_ORDER.get(right));
    return unique;
  }

  function textValue(value, fallback = "") {
    return value === undefined || value === null ? fallback : String(value);
  }

  function midiPitchesForKey(key = "C") {
    const normalized = textValue(key, "C")
      .trim()
      .replace("♯", "#")
      .replace("♭", "b");
    const pitchClass = KEY_PITCH_CLASSES[normalized.toUpperCase()];
    if (pitchClass === undefined) {
      throw formatError(`不支持的调性：${key}`);
    }
    return Array.from({ length: 15 }, (_, index) =>
      60 + pitchClass + SCALE_INTERVALS[index % SCALE_INTERVALS.length] +
        12 * Math.floor(index / SCALE_INTERVALS.length),
    );
  }

  function rawKeyToLabel(rawKey) {
    const match = RAW_KEY_PATTERN.exec(rawKey);
    if (!match) return null;
    const index = Number(match[1]);
    if (!Number.isInteger(index) || index < 0 || index >= KEY_LABELS.length) {
      return null;
    }
    return KEY_LABELS[index];
  }

  function normalizeRawSong(song) {
    if (!song || typeof song !== "object" || Array.isArray(song)) {
      throw formatError("歌曲必须是对象");
    }
    if (!Array.isArray(song.songNotes)) {
      throw formatError("歌曲缺少有效的 songNotes 数组");
    }

    const grouped = new Map();
    const seen = new Set();
    const warnings = [];
    for (let position = 0; position < song.songNotes.length; position += 1) {
      const note = song.songNotes[position];
      if (!note || typeof note !== "object" || Array.isArray(note)) {
        throw formatError(`第 ${position + 1} 个音符不是对象`);
      }
      const time = note.time;
      if (!Number.isInteger(time) || time < 0) {
        throw formatError(`第 ${position + 1} 个音符的 time 无效`);
      }
      const rawKey = typeof note.key === "string" ? note.key.trim() : "";
      const key = rawKeyToLabel(rawKey);
      if (!key) {
        throw formatError(
          `第 ${position + 1} 个音符使用不支持的按键：${rawKey || "空值"}`,
        );
      }
      const duplicateId = `${time}\u0000${key}`;
      if (seen.has(duplicateId)) {
        warnings.push(`time=${time} 重复按键 ${rawKey}，已去重`);
        continue;
      }
      seen.add(duplicateId);
      if (!grouped.has(time)) grouped.set(time, []);
      grouped.get(time).push(key);
    }

    const frames = [...grouped.entries()]
      .sort(([left], [right]) => left - right)
      .map(([time, keys], index) => ({
        index: index + 1,
        time,
        keys: sortKeys(keys),
      }));

    return {
      format: "sky-normalized-v1",
      mode: "raw",
      key: "C",
      source: {
        filename: "",
        name: textValue(song.name || song.title, "未命名歌曲"),
        author: textValue(song.author),
        transcribed_by: textValue(song.transcribedBy),
      },
      keyOrder: [...KEY_LABELS],
      colors: COLORS.map((color) => ({ ...color })),
      sourceNoteCount: song.songNotes.length,
      sourceFrameCount: frames.length,
      imageCount: frames.length,
      warnings,
      images: frames.map((frame, index) => ({
        index: index + 1,
        layers: [
          {
            index: 0,
            color: "black",
            hex: "#000000",
            sourceFrameIndex: frame.index,
            sourceTime: frame.time,
            keys: frame.keys,
          },
        ],
      })),
    };
  }

  function normalizeColorScore(payload) {
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw formatError("彩谱 JSON 必须是对象");
    }
    if (payload.format !== "sky-color-v1") {
      throw formatError("不支持的彩谱版本");
    }
    if (!Array.isArray(payload.images)) {
      throw formatError("彩谱缺少 images 数组");
    }

    const images = payload.images.map((image, imagePosition) => {
      if (!image || typeof image !== "object" || !Array.isArray(image.layers)) {
        throw formatError(`第 ${imagePosition + 1} 张图缺少 layers 数组`);
      }
      if (image.layers.length < 1 || image.layers.length > 3) {
        throw formatError(`第 ${imagePosition + 1} 张图的层数必须为 1 到 3`);
      }
      const layers = image.layers.map((layer, layerPosition) => {
        if (!layer || typeof layer !== "object" || !Array.isArray(layer.keys)) {
          throw formatError(
            `第 ${imagePosition + 1} 张图的第 ${layerPosition + 1} 层无效`,
          );
        }
        const expectedColor = COLORS[layerPosition];
        const color = layer.color || expectedColor.name;
        if (color !== expectedColor.name) {
          throw formatError(
            `第 ${imagePosition + 1} 张图的颜色顺序必须为黑、红、蓝`,
          );
        }
        const keys = sortKeys(layer.keys);
        return {
          index: layerPosition,
          color,
          hex: layer.hex || expectedColor.hex,
          sourceFrameIndex:
            layer.sourceFrameIndex ?? layer.source_frame_index ?? null,
          sourceTime: layer.sourceTime ?? layer.source_time ?? null,
          keys,
        };
      });
      const usedKeys = new Set();
      for (const layer of layers) {
        for (const key of layer.keys) {
          if (usedKeys.has(key)) {
            throw formatError(`第 ${imagePosition + 1} 张图存在重叠按键：${key}`);
          }
          usedKeys.add(key);
        }
      }
      return {
        index: image.index ?? imagePosition + 1,
        layers,
      };
    });

    return {
      format: "sky-color-v1",
      mode: "color",
      key: textValue(payload.key || payload.source?.key, "C"),
      source: payload.source || {},
      keyOrder: [...KEY_LABELS],
      colors: COLORS.map((color) => ({ ...color })),
      sourceNoteCount: payload.source_note_count ?? payload.sourceNoteCount ?? 0,
      sourceFrameCount: payload.source_frame_count ?? payload.sourceFrameCount ?? 0,
      imageCount: images.length,
      warnings: Array.isArray(payload.warnings) ? [...payload.warnings] : [],
      images,
    };
  }

  function normalizeBlackScore(payload) {
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw formatError("黑白谱 JSON 必须是对象");
    }
    if (payload.format !== "sky-black-v1") {
      throw formatError("不支持的黑白谱版本");
    }
    if (!Array.isArray(payload.images)) {
      throw formatError("黑白谱缺少 images 数组");
    }

    const images = payload.images.map((image, imagePosition) => {
      if (!image || typeof image !== "object" || !Array.isArray(image.layers)) {
        throw formatError(`第 ${imagePosition + 1} 张图缺少 layers 数组`);
      }
      if (image.layers.length !== 1) {
        throw formatError(`第 ${imagePosition + 1} 张黑白图必须只有一层`);
      }
      const layer = image.layers[0];
      if (!layer || typeof layer !== "object" || !Array.isArray(layer.keys)) {
        throw formatError(`第 ${imagePosition + 1} 张图的黑色层无效`);
      }
      if ((layer.color || "black") !== "black") {
        throw formatError(`第 ${imagePosition + 1} 张黑白图的层颜色必须是 black`);
      }
      return {
        index: image.index ?? imagePosition + 1,
        layers: [
          {
            index: 0,
            color: "black",
            hex: layer.hex || "#000000",
            sourceFrameIndex:
              layer.sourceFrameIndex ?? layer.source_frame_index ?? null,
            sourceTime: layer.sourceTime ?? layer.source_time ?? null,
            keys: sortKeys(layer.keys),
          },
        ],
      };
    });

    return {
      format: "sky-black-v1",
      mode: "black-white",
      key: textValue(payload.key || payload.source?.key, "C"),
      source: payload.source || {},
      keyOrder: [...KEY_LABELS],
      colors: [{ ...COLORS[0] }],
      sourceNoteCount: payload.source_note_count ?? payload.sourceNoteCount ?? 0,
      sourceFrameCount: payload.source_frame_count ?? payload.sourceFrameCount ?? 0,
      imageCount: images.length,
      warnings: Array.isArray(payload.warnings) ? [...payload.warnings] : [],
      images,
    };
  }

  function normalizeRawPayload(payload, songIndex = 0) {
    let songs;
    if (Array.isArray(payload)) {
      songs = payload;
    } else if (payload && typeof payload === "object" && Array.isArray(payload.songs)) {
      songs = payload.songs;
    } else {
      songs = [payload];
    }
    if (!Number.isInteger(songIndex) || songIndex < 0 || songIndex >= songs.length) {
      throw formatError(`歌曲序号越界：${songIndex}`);
    }
    return normalizeRawSong(songs[songIndex]);
  }

  function normalizePayload(payload, songIndex = 0) {
    if (payload && payload.format === "sky-color-v1") {
      return normalizeColorScore(payload);
    }
    if (payload && payload.format === "sky-black-v1") {
      return normalizeBlackScore(payload);
    }
    return normalizeRawPayload(payload, songIndex);
  }

  function buildTimedEvents(score) {
    if (!score || !Array.isArray(score.images)) {
      throw formatError("谱面缺少 images 数组，无法按 time 播放");
    }

    const events = [];
    let previousTime = -Infinity;
    for (let imageIndex = 0; imageIndex < score.images.length; imageIndex += 1) {
      const image = score.images[imageIndex];
      if (!image || !Array.isArray(image.layers)) {
        throw formatError(`第 ${imageIndex + 1} 张图缺少 layers 数组`);
      }
      for (let layerIndex = 0; layerIndex < image.layers.length; layerIndex += 1) {
        const layer = image.layers[layerIndex];
        const rawSourceTime = layer?.sourceTime ?? layer?.source_time;
        if (
          typeof rawSourceTime !== "number" ||
          !Number.isFinite(rawSourceTime) ||
          rawSourceTime < 0
        ) {
          throw formatError(
            `第 ${imageIndex + 1} 张图的第 ${layerIndex + 1} 层缺少有效 source time，无法自动播放`,
          );
        }
        if (rawSourceTime < previousTime) {
          throw formatError("source time 必须按谱面顺序递增");
        }
        previousTime = rawSourceTime;
        events.push({
          imageIndex,
          layerIndex,
          sourceTime: rawSourceTime,
          color: layer.color,
          keys: [...layer.keys],
        });
      }
    }
    return events;
  }

  function createPlaybackState(score) {
    if (!score || !Array.isArray(score.images)) {
      throw new TypeError("score.images must be an array");
    }
    return {
      score,
      imageIndex: 0,
      layerIndex: 0,
      atEnd: score.images.length === 0,
    };
  }

  function selectImage(state, imageIndex) {
    if (!Number.isInteger(imageIndex) || imageIndex < 0 || imageIndex >= state.score.images.length) {
      throw new RangeError("imageIndex is outside the score");
    }
    return {
      score: state.score,
      imageIndex,
      layerIndex: 0,
      atEnd: false,
    };
  }

  function advancePlayback(state) {
    const { score } = state;
    if (state.atEnd || state.imageIndex >= score.images.length) {
      return { state: createPlaybackState(score), action: { type: "reset" } };
    }
    const image = score.images[state.imageIndex];
    const layer = image.layers[state.layerIndex];
    if (!layer) {
      throw new Error("播放状态指向不存在的谱面层");
    }

    const action = {
      type: "play",
      imageIndex: state.imageIndex,
      layerIndex: state.layerIndex,
      color: layer.color,
      keys: [...layer.keys],
    };
    let nextImageIndex = state.imageIndex;
    let nextLayerIndex = state.layerIndex + 1;
    while (
      nextImageIndex < score.images.length &&
      nextLayerIndex >= score.images[nextImageIndex].layers.length
    ) {
      nextImageIndex += 1;
      nextLayerIndex = 0;
    }
    const nextState = {
      score,
      imageIndex: nextImageIndex,
      layerIndex: nextLayerIndex,
      atEnd: nextImageIndex >= score.images.length,
    };
    return { state: nextState, action };
  }

  function shouldHandleSpace(event) {
    if (!event || event.repeat) return false;
    return event.key === " " || event.key === "Spacebar" || event.code === "Space";
  }

  function currentLayer(state) {
    if (state.atEnd || state.imageIndex >= state.score.images.length) return null;
    return state.score.images[state.imageIndex].layers[state.layerIndex] || null;
  }

  return {
    COLORS,
    KEY_LABELS,
    midiPitchesForKey,
    RAW_KEY_PATTERN,
    rawKeyToLabel,
    advancePlayback,
    buildTimedEvents,
    createPlaybackState,
    currentLayer,
    normalizeColorScore,
    normalizeBlackScore,
    normalizePayload,
    normalizeRawPayload,
    normalizeRawSong,
    selectImage,
    shouldHandleSpace,
  };
});
