package com.xyuki.skyconverter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts parsed MIDI notes into the project's black and colour Sky formats. */
public final class SkyConverter {
    public static final int MAX_LAYERS = 3;
    public static final int SKY_KEY_COUNT = 15;
    public static final int DEFAULT_SUBDIVISIONS = 4;
    public static final String[] KEY_LABELS = {
            "A1", "A2", "A3", "A4", "A5",
            "B1", "B2", "B3", "B4", "B5",
            "C1", "C2", "C3", "C4", "C5"
    };
    public static final String[] COLOR_NAMES = {"black", "red", "blue"};
    public static final String[] COLOR_HEX = {"#000000", "#FF0000", "#0000FF"};
    public static final int[] MAJOR_INTERVALS = {0, 2, 4, 5, 7, 9, 11};

    private static final Map<String, Integer> KEY_PITCH_CLASSES;

    static {
        Map<String, Integer> values = new HashMap<>();
        values.put("C", 0);
        values.put("C#", 1);
        values.put("DB", 1);
        values.put("D", 2);
        values.put("D#", 3);
        values.put("EB", 3);
        values.put("E", 4);
        values.put("F", 5);
        values.put("F#", 6);
        values.put("GB", 6);
        values.put("G", 7);
        values.put("G#", 8);
        values.put("AB", 8);
        values.put("A", 9);
        values.put("A#", 10);
        values.put("BB", 10);
        values.put("B", 11);
        KEY_PITCH_CLASSES = Collections.unmodifiableMap(values);
    }

    private SkyConverter() {
    }

    public enum ChromaticPolicy {
        ERROR,
        DROP,
        NEAREST
    }

    public static final class Options {
        public final String key;
        public final int subdivisions;
        public final Integer shift;
        public final ChromaticPolicy chromaticPolicy;
        public final String title;

        public Options(
                String key,
                int subdivisions,
                Integer shift,
                ChromaticPolicy chromaticPolicy,
                String title
        ) {
            this.key = normalizeKey(key);
            if (subdivisions <= 0) {
                throw new IllegalArgumentException("subdivisions 必须是正整数");
            }
            this.subdivisions = subdivisions;
            this.shift = shift;
            this.chromaticPolicy = chromaticPolicy == null ? ChromaticPolicy.DROP : chromaticPolicy;
            this.title = title == null ? "" : title.trim();
            parseKey(this.key);
        }

        public Options withTitle(String newTitle) {
            return new Options(key, subdivisions, shift, chromaticPolicy, newTitle);
        }
    }

    public static final class SourceFrame {
        public final int index;
        public final int timeMs;
        public final List<String> keys;

        private SourceFrame(int index, int timeMs, List<String> keys) {
            this.index = index;
            this.timeMs = timeMs;
            this.keys = immutable(keys);
        }
    }

    public static final class NearestAdjustment {
        public final int fromPitch;
        public final int toPitch;
        public final int sourceTimeMs;

        private NearestAdjustment(int fromPitch, int toPitch, int sourceTimeMs) {
            this.fromPitch = fromPitch;
            this.toPitch = toPitch;
            this.sourceTimeMs = sourceTimeMs;
        }
    }

    public static final class ColorLayer {
        public final int index;
        public final String color;
        public final String hex;
        public final int sourceFrameIndex;
        public final int sourceTimeMs;
        public final List<String> keys;

        private ColorLayer(
                int index,
                String color,
                String hex,
                int sourceFrameIndex,
                int sourceTimeMs,
                List<String> keys
        ) {
            this.index = index;
            this.color = color;
            this.hex = hex;
            this.sourceFrameIndex = sourceFrameIndex;
            this.sourceTimeMs = sourceTimeMs;
            this.keys = immutable(keys);
        }
    }

    public static final class ColorImage {
        public final int index;
        public final List<ColorLayer> layers;

        private ColorImage(int index, List<ColorLayer> layers) {
            this.index = index;
            this.layers = immutable(layers);
        }
    }

    public static final class Conversion {
        public final String title;
        public final String key;
        public final String chromaticPolicy;
        public final int ticksPerBeat;
        public final int subdivisions;
        public final int scaleShift;
        public final int inputNoteCount;
        public final int mappedNoteCount;
        public final int outOfRangeNoteCount;
        public final List<SourceFrame> frames;
        public final List<ColorImage> blackImages;
        public final List<ColorImage> colorImages;
        public final List<String> warnings;
        public final List<Integer> unsupportedPitches;
        public final List<Integer> outOfRangePitches;
        public final List<NearestAdjustment> nearestAdjustments;

        private Conversion(
                String title,
                String key,
                String chromaticPolicy,
                int ticksPerBeat,
                int subdivisions,
                int scaleShift,
                int inputNoteCount,
                int mappedNoteCount,
                int outOfRangeNoteCount,
                List<SourceFrame> frames,
                List<ColorImage> blackImages,
                List<ColorImage> colorImages,
                List<String> warnings,
                List<Integer> unsupportedPitches,
                List<Integer> outOfRangePitches,
                List<NearestAdjustment> nearestAdjustments
        ) {
            this.title = title;
            this.key = key;
            this.chromaticPolicy = chromaticPolicy;
            this.ticksPerBeat = ticksPerBeat;
            this.subdivisions = subdivisions;
            this.scaleShift = scaleShift;
            this.inputNoteCount = inputNoteCount;
            this.mappedNoteCount = mappedNoteCount;
            this.outOfRangeNoteCount = outOfRangeNoteCount;
            this.frames = immutable(frames);
            this.blackImages = immutable(blackImages);
            this.colorImages = immutable(colorImages);
            this.warnings = immutable(warnings);
            this.unsupportedPitches = immutable(unsupportedPitches);
            this.outOfRangePitches = immutable(outOfRangePitches);
            this.nearestAdjustments = immutable(nearestAdjustments);
        }
    }

    private static final class MappedNote {
        final MidiFileReader.Note note;
        final int scaleIndex;

        MappedNote(MidiFileReader.Note note, int scaleIndex) {
            this.note = note;
            this.scaleIndex = scaleIndex;
        }
    }

    private static final class FrameAccumulator {
        final Set<String> keys = new HashSet<>();
        int timeMs = Integer.MAX_VALUE;
    }

    public static Conversion convert(MidiFileReader.Result result, Options options) {
        if (result == null) {
            throw new IllegalArgumentException("MIDI 解析结果不能为空");
        }
        if (options == null) {
            throw new IllegalArgumentException("转换选项不能为空");
        }
        List<String> warnings = new ArrayList<>(result.warnings);
        Set<Integer> unsupported = new HashSet<>();
        Set<Integer> outOfRange = new HashSet<>();
        List<NearestAdjustment> nearestAdjustments = new ArrayList<>();
        List<MappedNote> mapped = new ArrayList<>();
        List<Integer> scaleIndexes = new ArrayList<>();

        for (MidiFileReader.Note note : result.notes) {
            Integer scaleIndex = scaleIndexForPitch(note.pitch, options.key);
            if (scaleIndex == null) {
                unsupported.add(note.pitch);
                if (options.chromaticPolicy == ChromaticPolicy.ERROR) {
                    throw new IllegalArgumentException(
                            "半音音符 " + note.pitch + " 不属于 " + options.key
                                    + " 大调；请改用 drop 或 nearest"
                    );
                }
                if (options.chromaticPolicy == ChromaticPolicy.DROP) {
                    warnings.add(
                            "已丢弃不在 " + options.key + " 大调内的半音音符：pitch="
                                    + note.pitch + "，time=" + note.startMs + "ms"
                    );
                    continue;
                }
                int nearestPitch = nearestScalePitch(note.pitch, options.key);
                scaleIndex = scaleIndexForPitch(nearestPitch, options.key);
                if (scaleIndex == null) {
                    throw new IllegalStateException("无法为半音音符找到就近音阶位置");
                }
                nearestAdjustments.add(
                        new NearestAdjustment(note.pitch, nearestPitch, note.startMs)
                );
                warnings.add(
                        "半音音符 pitch=" + note.pitch + " 已就近映射到 pitch=" + nearestPitch
                );
            }
            mapped.add(new MappedNote(note, scaleIndex));
            scaleIndexes.add(scaleIndex);
        }

        int scaleShift;
        if (options.shift == null) {
            scaleShift = 0;
            if (!scaleIndexes.isEmpty()) {
                int minimum = Collections.min(scaleIndexes);
                scaleShift = -minimum;
                int maximum = Collections.max(scaleIndexes);
                if (maximum - minimum >= SKY_KEY_COUNT) {
                    warnings.add(
                            "映射后的音域跨度超过 15 个 Sky 按键，已优先保留最低音；"
                                    + "超出范围的音符会列入 out_of_range_pitches"
                    );
                }
            }
        } else {
            scaleShift = options.shift;
        }

        TreeMap<Long, FrameAccumulator> frameMap = new TreeMap<>();
        int mappedNoteCount = 0;
        int outOfRangeNoteCount = 0;
        for (MappedNote mappedNote : mapped) {
            int shiftedIndex = mappedNote.scaleIndex + scaleShift;
            if (shiftedIndex < 0 || shiftedIndex >= SKY_KEY_COUNT) {
                outOfRange.add(mappedNote.note.pitch);
                outOfRangeNoteCount++;
                warnings.add(
                        "音符 pitch=" + mappedNote.note.pitch + " 超出 A1-C5：time="
                                + mappedNote.note.startMs + "ms，未写入 Sky 帧"
                );
                continue;
            }
            long slot = quantizeSlot(
                    mappedNote.note.startTick,
                    result.ticksPerBeat,
                    options.subdivisions
            );
            FrameAccumulator accumulator = frameMap.computeIfAbsent(
                    slot,
                    ignored -> new FrameAccumulator()
            );
            accumulator.keys.add(KEY_LABELS[shiftedIndex]);
            accumulator.timeMs = Math.min(accumulator.timeMs, mappedNote.note.startMs);
            mappedNoteCount++;
        }

        List<SourceFrame> frames = new ArrayList<>();
        int frameIndex = 1;
        for (FrameAccumulator accumulator : frameMap.values()) {
            List<String> keys = new ArrayList<>(accumulator.keys);
            keys.sort(Comparator.comparingInt(SkyConverter::keyOrder));
            frames.add(new SourceFrame(frameIndex++, accumulator.timeMs, keys));
        }
        List<ColorImage> blackImages = blackImages(frames);
        List<ColorImage> colorImages = compressFrames(frames);
        String title = options.title.isEmpty() ? result.title : options.title;
        if (title == null || title.trim().isEmpty()) {
            title = "未命名歌曲";
        }
        List<Integer> unsupportedList = new ArrayList<>(unsupported);
        Collections.sort(unsupportedList);
        List<Integer> outOfRangeList = new ArrayList<>(outOfRange);
        Collections.sort(outOfRangeList);
        return new Conversion(
                title,
                options.key,
                options.chromaticPolicy.name().toLowerCase(),
                result.ticksPerBeat,
                options.subdivisions,
                scaleShift,
                result.notes.size(),
                mappedNoteCount,
                outOfRangeNoteCount,
                frames,
                blackImages,
                colorImages,
                warnings,
                unsupportedList,
                outOfRangeList,
                nearestAdjustments
        );
    }

    public static String normalizeKey(String key) {
        String normalized = key == null ? "C" : key.trim();
        normalized = normalized.replace("♯", "#").replace("♭", "b").toUpperCase();
        return normalized.isEmpty() ? "C" : normalized;
    }

    public static int parseKey(String key) {
        Integer value = KEY_PITCH_CLASSES.get(normalizeKey(key));
        if (value == null) {
            throw new IllegalArgumentException("不支持的调性：" + key);
        }
        return value;
    }

    public static Integer scaleIndexForPitch(int pitch, String key) {
        if (pitch < 0 || pitch > 127) {
            throw new IllegalArgumentException("MIDI 音高必须在 0 到 127：" + pitch);
        }
        int rootPitchClass = parseKey(key);
        int[] scalePitchClasses = new int[MAJOR_INTERVALS.length];
        for (int index = 0; index < MAJOR_INTERVALS.length; index++) {
            scalePitchClasses[index] = (rootPitchClass + MAJOR_INTERVALS[index]) % 12;
        }
        int relativePitch = pitch - 60;
        int midiOctave = Math.floorDiv(relativePitch, 12);
        int semitone = Math.floorMod(relativePitch, 12);
        int pitchClass = (60 + semitone) % 12;
        for (int degree = 0; degree < scalePitchClasses.length; degree++) {
            if (scalePitchClasses[degree] == pitchClass) {
                return midiOctave * MAJOR_INTERVALS.length + degree;
            }
        }
        return null;
    }

    public static int skyKeyIndex(String key) {
        for (int index = 0; index < KEY_LABELS.length; index++) {
            if (KEY_LABELS[index].equals(key)) {
                return index;
            }
        }
        throw new IllegalArgumentException("不支持的 Sky 按键：" + key);
    }

    private static int nearestScalePitch(int pitch, String key) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate = 0; candidate <= 127; candidate++) {
            if (scaleIndexForPitch(candidate, key) == null) {
                continue;
            }
            int distance = Math.abs(candidate - pitch);
            if (distance < bestDistance || (distance == bestDistance && candidate < best)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best < 0) {
            throw new IllegalStateException("调性没有可映射音符：" + key);
        }
        return best;
    }

    private static long quantizeSlot(long tick, int ticksPerBeat, int subdivisions) {
        return (long) Math.floor(
                tick * (double) subdivisions / ticksPerBeat + 0.5
        );
    }

    private static List<ColorImage> blackImages(List<SourceFrame> frames) {
        List<ColorImage> result = new ArrayList<>();
        int imageIndex = 1;
        for (SourceFrame frame : frames) {
            List<ColorLayer> layers = new ArrayList<>();
            layers.add(
                    new ColorLayer(
                            0,
                            COLOR_NAMES[0],
                            COLOR_HEX[0],
                            frame.index,
                            frame.timeMs,
                            frame.keys
                    )
            );
            result.add(new ColorImage(imageIndex++, layers));
        }
        return result;
    }

    private static List<ColorImage> compressFrames(List<SourceFrame> frames) {
        List<ColorImage> result = new ArrayList<>();
        List<ColorLayer> current = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        for (SourceFrame frame : frames) {
            boolean conflicts = false;
            for (String key : frame.keys) {
                if (usedKeys.contains(key)) {
                    conflicts = true;
                    break;
                }
            }
            if (!current.isEmpty() && (current.size() >= MAX_LAYERS || conflicts)) {
                result.add(new ColorImage(result.size() + 1, current));
                current = new ArrayList<>();
                usedKeys = new HashSet<>();
            }
            int layerIndex = current.size();
            current.add(
                    new ColorLayer(
                            layerIndex,
                            COLOR_NAMES[layerIndex],
                            COLOR_HEX[layerIndex],
                            frame.index,
                            frame.timeMs,
                            frame.keys
                    )
            );
            usedKeys.addAll(frame.keys);
        }
        if (!current.isEmpty()) {
            result.add(new ColorImage(result.size() + 1, current));
        }
        return result;
    }

    private static int keyOrder(String key) {
        for (int index = 0; index < KEY_LABELS.length; index++) {
            if (KEY_LABELS[index].equals(key)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
