package com.xyuki.skycolor.converter.player;

import java.util.Locale;
import java.util.Set;

/** Matches the desktop player's 15-key natural-major pitch layout. */
public final class PitchMapper {
    private static final int[] SCALE_INTERVALS = {0, 2, 4, 5, 7, 9, 11};
    private static final Set<String> TONALITIES = Set.of(
            "C", "C#", "DB", "D", "D#", "EB", "E", "F", "F#", "GB", "G",
            "G#", "AB", "A", "A#", "BB", "B"
    );

    private PitchMapper() {
    }

    public static int[] midiPitchesForKey(String key) {
        int pitchClass = pitchClass(key);
        int[] result = new int[15];
        for (int index = 0; index < result.length; index++) {
            result[index] = 60 + pitchClass
                    + SCALE_INTERVALS[index % SCALE_INTERVALS.length]
                    + 12 * (index / SCALE_INTERVALS.length);
        }
        return result;
    }

    public static double frequencyForKeyIndex(String key, int keyIndex, int transpose) {
        if (keyIndex < 0 || keyIndex >= 15) {
            throw new IllegalArgumentException("Sky 按键编号必须在 0 到 14 之间");
        }
        if (transpose < -12 || transpose > 12) {
            throw new IllegalArgumentException("移调必须在 -12 到 +12 半音之间");
        }
        return frequencyForMidi(midiPitchesForKey(key)[keyIndex] + transpose);
    }

    public static double frequencyForMidi(int midi) {
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
    }

    private static int pitchClass(String key) {
        String normalized = normalize(key);
        return switch (normalized) {
            case "C" -> 0;
            case "C#", "DB" -> 1;
            case "D" -> 2;
            case "D#", "EB" -> 3;
            case "E" -> 4;
            case "F" -> 5;
            case "F#", "GB" -> 6;
            case "G" -> 7;
            case "G#", "AB" -> 8;
            case "A" -> 9;
            case "A#", "BB" -> 10;
            case "B" -> 11;
            default -> throw new IllegalArgumentException("不支持的调性：" + key);
        };
    }

    private static String normalize(String key) {
        String normalized = key == null ? "C" : key.trim();
        if (normalized.isEmpty()) {
            normalized = "C";
        }
        normalized = normalized.replace('♯', '#').replace('♭', 'b')
                .toUpperCase(Locale.ROOT);
        if (!TONALITIES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的调性：" + key);
        }
        return normalized;
    }
}
