(function () {
  "use strict";

  const core = window.SkyPlayerCore;
  const COLOR_LABELS = { black: "黑层", red: "红层", blue: "蓝层" };
  const COLOR_HEX = { black: "#000000", red: "#FF0000", blue: "#0000FF" };

  let score = null;
  let state = null;
  let pendingRawPayload = null;
  let audioEngine = null;
  let autoPlayback = null;

  const elements = {};

  function get(id) {
    return document.getElementById(id);
  }

  function setStatus(message, tone = "") {
    elements.status.textContent = message;
    elements.status.className = `status${tone ? ` ${tone}` : ""}`;
  }

  function sourceName() {
    return score?.source?.name || score?.source?.title || "未命名歌曲";
  }

  function buildCell(key, color) {
    const cell = document.createElement("div");
    cell.className = "note-cell";
    cell.textContent = key;
    if (color) {
      cell.classList.add("is-filled");
      cell.style.setProperty("--cell-color", color.hex || COLOR_HEX[color.name] || color);
    }
    return cell;
  }

  function renderGrid(container, colorByKey, extraClass = "") {
    container.textContent = "";
    container.className = `note-grid ${extraClass}`.trim();
    for (const key of core.KEY_LABELS) {
      container.appendChild(buildCell(key, colorByKey.get(key) || null));
    }
  }

  function colorMapForLayer(layer) {
    const map = new Map();
    if (!layer) return map;
    for (const key of layer.keys) {
      map.set(key, { name: layer.color, hex: layer.hex || COLOR_HEX[layer.color] });
    }
    return map;
  }

  function colorMapForImage(image) {
    const map = new Map();
    for (const layer of image.layers) {
      for (const key of layer.keys) {
        map.set(key, { name: layer.color, hex: layer.hex || COLOR_HEX[layer.color] });
      }
    }
    return map;
  }

  function imageForDisplay() {
    if (!score || !score.images.length) return null;
    const index = state.atEnd
      ? score.images.length - 1
      : Math.min(state.imageIndex, score.images.length - 1);
    return score.images[index];
  }

  function renderGallery() {
    elements.gallery.textContent = "";
    if (!score || !score.images.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      const icon = document.createElement("span");
      icon.className = "empty-icon";
      icon.textContent = "＋";
      const heading = document.createElement("strong");
      heading.textContent = score
        ? "这份谱面没有可播放的音符"
        : "载入一份谱面开始调试";
      const detail = document.createElement("span");
      detail.textContent = score
        ? "请载入另一份 TXT 或彩色 JSON"
        : "支持原始黑白 TXT 和转换器生成的彩色 JSON";
      empty.append(icon, heading, detail);
      elements.gallery.appendChild(empty);
      return;
    }

    const selectedIndex = imageForDisplay()?.index - 1;
    for (const image of score.images) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "thumbnail";
      button.dataset.imageIndex = String(image.index - 1);
      button.setAttribute("aria-label", `选择第 ${image.index} 张逻辑图`);
      if (image.index - 1 === selectedIndex) button.classList.add("is-active");

      const header = document.createElement("div");
      header.className = "thumbnail-header";
      const number = document.createElement("strong");
      number.textContent = `#${String(image.index).padStart(3, "0")}`;
      const layerCount = document.createElement("span");
      layerCount.textContent = `${image.layers.length} 层`;
      header.append(number, layerCount);
      button.appendChild(header);

      const grid = document.createElement("div");
      renderGrid(grid, colorMapForImage(image));
      button.appendChild(grid);
      elements.gallery.appendChild(button);
    }
  }

  function renderPreview() {
    if (!score || !state || !score.images.length) {
      elements.previewTitle.textContent = "尚未载入";
      elements.layerBadge.textContent = "—";
      elements.layerBadge.style.borderColor = "rgba(148, 163, 184, 0.2)";
      elements.layerBadge.style.backgroundColor = "transparent";
      elements.layerBadge.style.color = "#94A3B8";
      elements.positionLabel.textContent = "—";
      elements.sourceTimeLabel.textContent =
        "按空格手动推进；按时间自动播放；鼠标跳转不会发声";
      renderGrid(elements.previewGrid, new Map(), "large-grid");
      return;
    }

    const layer = core.currentLayer(state);
    const image = imageForDisplay();
    elements.previewTitle.textContent = sourceName();
    if (layer) {
      elements.layerBadge.textContent = COLOR_LABELS[layer.color] || layer.color;
      elements.layerBadge.style.borderColor = layer.hex || COLOR_HEX[layer.color];
      elements.layerBadge.style.backgroundColor = layer.hex || COLOR_HEX[layer.color];
      elements.layerBadge.style.color = "#FFFFFF";
      elements.positionLabel.textContent = `第 ${state.imageIndex + 1}/${score.images.length} 图`;
      elements.sourceTimeLabel.textContent =
        layer.sourceTime === null || layer.sourceTime === undefined
          ? "当前层 · 自动模式需要源 time"
          : `源 time=${layer.sourceTime} ms · 自动模式按此计时`;
      renderGrid(elements.previewGrid, colorMapForLayer(layer), "large-grid");
    } else {
      elements.layerBadge.textContent = "END";
      elements.layerBadge.style.borderColor = "rgba(148, 163, 184, 0.35)";
      elements.layerBadge.style.backgroundColor = "transparent";
      elements.layerBadge.style.color = "#94A3B8";
      elements.positionLabel.textContent = `已完成 ${score.images.length} 图`;
      elements.sourceTimeLabel.textContent = "再按一次空格将静默回到第一图";
      renderGrid(elements.previewGrid, new Map(), "large-grid");
    }
    elements.jumpInput.value = image ? image.index : "";
  }

  function renderAll() {
    renderPreview();
    renderGallery();
  }

  function updateGallerySelection() {
    const selectedIndex = imageForDisplay()?.index - 1;
    for (const button of elements.gallery.querySelectorAll("button[data-image-index]")) {
      button.classList.toggle(
        "is-active",
        Number(button.dataset.imageIndex) === selectedIndex,
      );
    }
  }

  function updateAutoButton() {
    if (!elements.autoPlayButton) return;
    const isPlaying = Boolean(autoPlayback);
    elements.autoPlayButton.textContent = isPlaying
      ? "停止自动播放"
      : "按时间自动播放";
    elements.autoPlayButton.classList.toggle("is-playing", isPlaying);
    elements.autoPlayButton.setAttribute("aria-pressed", String(isPlaying));
  }

  function stopAutoPlayback() {
    if (autoPlayback?.timer !== null && autoPlayback?.timer !== undefined) {
      window.clearTimeout(autoPlayback.timer);
    }
    autoPlayback = null;
    updateAutoButton();
  }

  function finishAutoPlayback(playback) {
    if (autoPlayback !== playback) return;
    autoPlayback = null;
    updateAutoButton();
    state = {
      score,
      imageIndex: score.images.length,
      layerIndex: 0,
      atEnd: true,
    };
    renderPreview();
    updateGallerySelection();
    setStatus("按时间自动播放完成；再次点击按钮将从第一图开始", "success");
  }

  function runAutoTick() {
    const playback = autoPlayback;
    if (!playback || !score || !state) return;

    const event = playback.events[playback.eventIndex];
    if (!event) {
      finishAutoPlayback(playback);
      return;
    }

    const targetTime =
      playback.startedAt + (event.sourceTime - playback.firstSourceTime);
    const delay = targetTime - performance.now();
    if (delay > 2) {
      playback.timer = window.setTimeout(runAutoTick, Math.min(delay, 250));
      return;
    }

    playback.timer = null;
    try {
      const result = core.advancePlayback({
        score,
        imageIndex: event.imageIndex,
        layerIndex: event.layerIndex,
        atEnd: false,
      });
      audioEngine.play(result.action.keys, score.key);
      state = result.state;
    } catch (error) {
      stopAutoPlayback();
      setStatus(error.message || String(error), "error");
      return;
    }

    playback.eventIndex += 1;
    renderPreview();
    updateGallerySelection();
    setStatus(
      `自动播放 ${COLOR_LABELS[event.color] || event.color} · ${event.keys.join(" ")}`,
      "success",
    );
    if (playback.eventIndex >= playback.events.length) {
      finishAutoPlayback(playback);
    } else {
      playback.timer = window.setTimeout(runAutoTick, 0);
    }
  }

  function startAutoPlayback() {
    if (!score || !state || !score.images.length) {
      setStatus("请先载入有音符的谱面", "error");
      return;
    }

    let events;
    try {
      events = core.buildTimedEvents(score);
    } catch (error) {
      setStatus(error.message || String(error), "error");
      return;
    }
    if (!events.length) {
      setStatus("谱面没有可自动播放的层", "error");
      return;
    }

    let eventIndex = state.atEnd
      ? 0
      : events.findIndex(
          (event) =>
            event.imageIndex === state.imageIndex &&
            event.layerIndex === state.layerIndex,
        );
    if (eventIndex < 0) {
      setStatus("当前播放位置无法匹配 source time", "error");
      return;
    }
    if (state.atEnd) {
      state = core.createPlaybackState(score);
      renderAll();
    }

    autoPlayback = {
      events,
      eventIndex,
      firstSourceTime: events[eventIndex].sourceTime,
      startedAt: performance.now(),
      timer: null,
    };
    updateAutoButton();
    setStatus("按源 time（毫秒）自动播放中…", "success");
    runAutoTick();
  }

  function createAudioEngine() {
    let context = null;
    let volume = 0.68;
    let transpose = 0;

    function ensureContext() {
      if (!context) {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) throw new Error("当前浏览器不支持 Web Audio");
        context = new AudioContextClass();
      }
      if (context.state === "suspended") void context.resume().catch(() => {});
      return context;
    }

    function setVolume(value) {
      volume = Math.max(0, Math.min(1, Number(value)));
    }

    function setTranspose(value) {
      transpose = Number(value) || 0;
    }

    function play(keys, key = "C") {
      if (!keys.length || volume <= 0) return;
      const audioContext = ensureContext();
      const start = audioContext.currentTime + 0.012;
      const midiPitches = core.midiPitchesForKey(key);
      for (const key of keys) {
        const index = core.KEY_LABELS.indexOf(key);
        if (index < 0) continue;
        const frequency = 440 * 2 ** ((midiPitches[index] + transpose - 69) / 12);
        const oscillator = audioContext.createOscillator();
        const overtone = audioContext.createOscillator();
        const filter = audioContext.createBiquadFilter();
        const gain = audioContext.createGain();
        const overtoneGain = audioContext.createGain();
        oscillator.type = "triangle";
        oscillator.frequency.setValueAtTime(frequency, start);
        overtone.type = "sine";
        overtone.frequency.setValueAtTime(frequency * 2, start);
        filter.type = "lowpass";
        filter.frequency.setValueAtTime(Math.min(5200, frequency * 8), start);
        filter.Q.setValueAtTime(0.6, start);
        gain.gain.setValueAtTime(0.0001, start);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, volume * 0.34), start + 0.012);
        gain.gain.exponentialRampToValueAtTime(0.0001, start + 1.05);
        overtoneGain.gain.setValueAtTime(0.0001, start);
        overtoneGain.gain.exponentialRampToValueAtTime(Math.max(0.0001, volume * 0.08), start + 0.008);
        overtoneGain.gain.exponentialRampToValueAtTime(0.0001, start + 0.46);
        oscillator.connect(filter).connect(gain).connect(audioContext.destination);
        overtone.connect(overtoneGain).connect(audioContext.destination);
        oscillator.start(start);
        overtone.start(start);
        oscillator.stop(start + 1.1);
        overtone.stop(start + 0.5);
      }
    }

    return { play, setTranspose, setVolume };
  }

  function fileSongs(payload) {
    if (Array.isArray(payload)) return payload;
    if (payload && typeof payload === "object" && Array.isArray(payload.songs)) {
      return payload.songs;
    }
    return [payload];
  }

  function decodeFile(buffer) {
    const bytes = new Uint8Array(buffer);
    let text;
    if (bytes[0] === 0xff && bytes[1] === 0xfe) {
      text = new TextDecoder("utf-16le").decode(bytes.subarray(2));
    } else if (bytes[0] === 0xfe && bytes[1] === 0xff) {
      text = new TextDecoder("utf-16be").decode(bytes.subarray(2));
    } else {
      try {
        text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      } catch {
        text = new TextDecoder("utf-16le").decode(bytes);
      }
    }
    return text.replace(/^\uFEFF/, "");
  }

  async function readJsonFile(file) {
    const text = decodeFile(await file.arrayBuffer());
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new Error(`JSON 格式错误：${error.message}`);
    }
  }

  function showScore(nextScore, filename) {
    stopAutoPlayback();
    score = {
      ...nextScore,
      source: { ...(nextScore.source || {}), filename },
    };
    state = core.createPlaybackState(score);
    pendingRawPayload = null;
    elements.songPicker.hidden = true;
    elements.sourceInfo.textContent = `${sourceName()} · ${filename} · ${score.images.length} 张逻辑图`;
    document.title = `${sourceName()} · Sky 彩谱试听器`;
    renderAll();
    if (score.warnings?.length) {
      setStatus(`已载入；有 ${score.warnings.length} 条去重警告`, "success");
    } else {
      setStatus(`已载入 ${score.images.length} 张逻辑图，按空格开始`, "success");
    }
  }

  function preparePayload(payload, filename) {
    if (
      payload &&
      (payload.format === "sky-color-v1" || payload.format === "sky-black-v1")
    ) {
      showScore(core.normalizePayload(payload), filename);
      return;
    }
    const songs = fileSongs(payload);
    if (songs.length > 1) {
      pendingRawPayload = { payload, filename, songs };
      elements.songSelect.textContent = "";
      songs.forEach((song, index) => {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent = `${index + 1}. ${song?.name || song?.title || "未命名歌曲"}`;
        elements.songSelect.appendChild(option);
      });
      elements.songPicker.hidden = false;
      setStatus(`检测到 ${songs.length} 首歌曲，请选择后载入`);
      return;
    }
    showScore(core.normalizeRawSong(songs[0]), filename);
  }

  async function handleFile(file) {
    if (!file) return;
    stopAutoPlayback();
    try {
      setStatus(`正在读取 ${file.name}…`);
      preparePayload(await readJsonFile(file), file.name);
    } catch (error) {
      score = null;
      state = null;
      elements.sourceInfo.textContent = "请载入原始 TXT、.sky.json 或 .color.json";
      document.title = "Sky 彩谱试听器";
      renderAll();
      setStatus(error.message || String(error), "error");
    }
  }

  function advance() {
    if (!score || !state) {
      setStatus("请先载入谱面", "error");
      return;
    }
    if (autoPlayback) stopAutoPlayback();
    try {
      const result = core.advancePlayback(state);
      if (result.action.type === "play") {
        audioEngine.play(result.action.keys, score.key);
        state = result.state;
        renderAll();
        setStatus(
          `播放 ${COLOR_LABELS[result.action.color] || result.action.color} · ${result.action.keys.join(" ")} · 下一次空格继续`,
          "success",
        );
      } else {
        state = result.state;
        renderAll();
        setStatus("已静默回到第一图黑层，再按空格播放", "success");
      }
    } catch (error) {
      setStatus(error.message || String(error), "error");
    }
  }

  function selectImageWithoutSound(imageIndex) {
    if (!score || !score.images.length) return;
    stopAutoPlayback();
    try {
      state = core.selectImage(state, imageIndex);
      renderAll();
      setStatus(`已静默定位到第 ${imageIndex + 1} 图黑层`);
    } catch (error) {
      setStatus(error.message || String(error), "error");
    }
  }

  function navigateImage(delta) {
    if (!score || !score.images.length) return;
    const current = imageForDisplay()?.index - 1 || 0;
    const target = Math.max(0, Math.min(score.images.length - 1, current + delta));
    selectImageWithoutSound(target);
  }

  function jumpToImage() {
    if (!score || !score.images.length) return;
    const number = Number(elements.jumpInput.value);
    if (!Number.isInteger(number) || number < 1 || number > score.images.length) {
      setStatus(`图号必须是 1 到 ${score.images.length} 的整数`, "error");
      return;
    }
    selectImageWithoutSound(number - 1);
  }

  function reset() {
    if (!score) return;
    stopAutoPlayback();
    state = core.createPlaybackState(score);
    renderAll();
    setStatus("已静默重置到第一图黑层");
  }

  function init() {
    elements.fileInput = get("file-input");
    elements.sourceInfo = get("source-info");
    elements.status = get("status");
    elements.songPicker = get("song-picker");
    elements.songSelect = get("song-select");
    elements.loadSongButton = get("load-song-button");
    elements.previewTitle = get("preview-title");
    elements.layerBadge = get("layer-badge");
    elements.previewGrid = get("preview-grid");
    elements.positionLabel = get("position-label");
    elements.sourceTimeLabel = get("source-time-label");
    elements.gallery = get("gallery");
    elements.jumpInput = get("jump-input");
    elements.volumeInput = get("volume-input");
    elements.volumeValue = get("volume-value");
    elements.transposeInput = get("transpose-input");
    elements.transposeValue = get("transpose-value");
    elements.autoPlayButton = get("auto-play-button");
    audioEngine = createAudioEngine();

    elements.fileInput.addEventListener("change", () => handleFile(elements.fileInput.files[0]));
    elements.gallery.addEventListener("click", (event) => {
      const button = event.target.closest("button[data-image-index]");
      if (button) selectImageWithoutSound(Number(button.dataset.imageIndex));
    });
    elements.loadSongButton.addEventListener("click", () => {
      if (!pendingRawPayload) return;
      const index = Number(elements.songSelect.value);
      try {
        showScore(core.normalizeRawSong(pendingRawPayload.songs[index]), pendingRawPayload.filename);
      } catch (error) {
        setStatus(error.message || String(error), "error");
      }
    });
    elements.jumpButton = get("jump-button");
    elements.jumpButton.addEventListener("click", jumpToImage);
    elements.jumpInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") jumpToImage();
    });
    get("previous-button").addEventListener("click", () => navigateImage(-1));
    get("next-button").addEventListener("click", () => navigateImage(1));
    elements.autoPlayButton.addEventListener("click", () => {
      if (autoPlayback) {
        stopAutoPlayback();
        setStatus("已停止按时间自动播放");
      } else {
        startAutoPlayback();
      }
    });
    get("reset-button").addEventListener("click", reset);
    elements.volumeInput.addEventListener("input", () => {
      audioEngine.setVolume(elements.volumeInput.value);
      elements.volumeValue.textContent = `${Math.round(Number(elements.volumeInput.value) * 100)}%`;
    });
    elements.transposeInput.addEventListener("input", () => {
      const value = Number(elements.transposeInput.value);
      audioEngine.setTranspose(value);
      elements.transposeValue.textContent = `${value > 0 ? "+" : ""}${value} 半音`;
    });
    document.addEventListener("keydown", (event) => {
      if (!core.shouldHandleSpace(event)) return;
      const tagName = event.target?.tagName;
      if (["INPUT", "TEXTAREA", "SELECT", "BUTTON"].includes(tagName)) return;
      event.preventDefault();
      advance();
    });
    renderAll();
  }

  window.addEventListener("DOMContentLoaded", init, { once: true });
})();
