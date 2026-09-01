import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const {
  advancePlayback,
  buildTimedEvents,
  createPlaybackState,
  normalizeRawSong,
  normalizePayload,
  midiPitchesForKey,
  selectImage,
  shouldHandleSpace,
} = createRequire(import.meta.url)("../player/player-core.js");

function colorScore() {
  return {
    format: "sky-color-v1",
    images: [
      {
        index: 1,
        layers: [
          { color: "black", sourceTime: 100, keys: ["A1"] },
          { color: "red", sourceTime: 350, keys: ["B1"] },
        ],
      },
      {
        index: 2,
        layers: [{ color: "black", sourceTime: 900, keys: ["C1", "C2"] }],
      },
    ],
  };
}

test("space plays each layer in order and advances across images", () => {
  let state = createPlaybackState(colorScore());

  let result = advancePlayback(state);
  assert.deepEqual(result.action, {
    type: "play",
    imageIndex: 0,
    layerIndex: 0,
    color: "black",
    keys: ["A1"],
  });
  state = result.state;

  result = advancePlayback(state);
  assert.equal(result.action.layerIndex, 1);
  assert.equal(result.action.color, "red");
  state = result.state;

  result = advancePlayback(state);
  assert.deepEqual(result.action.keys, ["C1", "C2"]);
  assert.equal(result.action.imageIndex, 1);
  state = result.state;

  result = advancePlayback(state);
  assert.deepEqual(result.action, { type: "reset" });
  assert.deepEqual(result.state, createPlaybackState(colorScore()));
});

test("clicking an image silently selects its black layer", () => {
  const state = createPlaybackState(colorScore());
  const selected = selectImage(state, 1);

  assert.deepEqual(selected, {
    score: colorScore(),
    imageIndex: 1,
    layerIndex: 0,
    atEnd: false,
  });
});

test("space repeat events are ignored", () => {
  assert.equal(shouldHandleSpace({ key: " ", repeat: false }), true);
  assert.equal(shouldHandleSpace({ key: "Spacebar", repeat: false }), true);
  assert.equal(shouldHandleSpace({ key: " ", repeat: true }), false);
  assert.equal(shouldHandleSpace({ key: "Enter", repeat: false }), false);
});

test("raw song normalization ignores timing gaps but preserves frame order", () => {
  const score = normalizeRawSong({
    name: "黑白曲",
    songNotes: [
      { time: 0, key: "1Key0" },
      { time: 1000, key: "1Key1" },
    ],
  });

  assert.equal(score.mode, "raw");
  assert.deepEqual(
    score.images.map((image) => image.layers[0].keys),
    [["A1"], ["A2"]],
  );
  assert.equal(score.images[1].layers[0].sourceTime, 1000);
});

test("raw song normalization accepts numeric layer prefixes", () => {
  const score = normalizeRawSong({
    name: "分层曲",
    songNotes: [
      { time: 0, key: "2Key0" },
      { time: 0, key: "1Key3" },
      { time: 10, key: "3Key14" },
      { time: 20, key: "2Key7" },
      { time: 20, key: "1Key7" },
    ],
  });

  assert.deepEqual(
    score.images.map((image) => image.layers[0].keys),
    [["A1", "A4"], ["C5"], ["B3"]],
  );
  assert.equal(score.warnings.length, 1);
  assert.match(score.warnings[0], /1Key7/);
});

test("timed events flatten layers with source times and preserve order", () => {
  const events = buildTimedEvents(colorScore());

  assert.deepEqual(
    events.map(({ imageIndex, layerIndex, sourceTime, keys }) => [
      imageIndex,
      layerIndex,
      sourceTime,
      keys,
    ]),
    [
      [0, 0, 100, ["A1"]],
      [0, 1, 350, ["B1"]],
      [1, 0, 900, ["C1", "C2"]],
    ],
  );
});

test("timed event expansion rejects missing source time", () => {
  const score = colorScore();
  delete score.images[0].layers[0].sourceTime;

  assert.throws(() => buildTimedEvents(score), /source time/);
});

test("black payloads load, select silently, advance, and expose source time", () => {
  const score = normalizePayload({
    format: "sky-black-v1",
    mode: "black-white",
    source: { name: "黑白曲" },
    images: [
      {
        index: 1,
        layers: [{ color: "black", source_time: 120, keys: ["A1"] }],
      },
      {
        index: 2,
        layers: [{ color: "black", source_time: 480, keys: ["B2", "C3"] }],
      },
    ],
  });

  assert.equal(score.format, "sky-black-v1");
  assert.equal(score.mode, "black-white");
  assert.deepEqual(selectImage(score && {
    score,
    imageIndex: 0,
    layerIndex: 0,
    atEnd: false,
  }, 1).score.images[1].layers[0].keys, ["B2", "C3"]);

  let state = createPlaybackState(score);
  let result = advancePlayback(state);
  assert.deepEqual(result.action, {
    type: "play",
    imageIndex: 0,
    layerIndex: 0,
    color: "black",
    keys: ["A1"],
  });
  state = result.state;
  result = advancePlayback(state);
  assert.deepEqual(result.action.keys, ["B2", "C3"]);
  assert.deepEqual(buildTimedEvents(score).map((event) => event.sourceTime), [120, 480]);
});

test("key-aware pitch sequence follows the active Sky major key", () => {
  assert.deepEqual(midiPitchesForKey("C"), [60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83, 84]);
  assert.deepEqual(midiPitchesForKey("G"), [67, 69, 71, 72, 74, 76, 78, 79, 81, 83, 84, 86, 88, 90, 91]);
});
