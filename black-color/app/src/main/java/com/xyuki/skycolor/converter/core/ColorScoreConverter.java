package com.xyuki.skycolor.converter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Packs sequential non-overlapping black source frames into up to three colour layers. */
public final class ColorScoreConverter {
    public static final int MAX_LAYERS = 3;
    public static final String[] COLOR_NAMES = {"black", "red", "blue"};
    public static final String[] COLOR_HEX = {"#000000", "#FF0000", "#0000FF"};

    private ColorScoreConverter() {
    }

    public static Conversion convert(
            BlackScoreReader.ScoreDocument source,
            String titleOverride
    ) {
        if (source == null) {
            throw new IllegalArgumentException("黑白谱不能为空");
        }
        String title = BlackScoreReader.firstNonBlank(
                titleOverride,
                source.title,
                BlackScoreReader.fileStem(source.sourceFilename),
                BlackScoreReader.fileStem(source.sourceName)
        );
        List<ColorImage> images = new ArrayList<>();
        List<ColorLayer> currentLayers = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        for (BlackScoreReader.SourceFrame frame : source.frames) {
            Set<String> frameKeys = new HashSet<>(frame.keys);
            boolean conflicts = false;
            for (String key : frameKeys) {
                if (usedKeys.contains(key)) {
                    conflicts = true;
                    break;
                }
            }
            if (!currentLayers.isEmpty() && (currentLayers.size() >= MAX_LAYERS || conflicts)) {
                images.add(new ColorImage(images.size() + 1, currentLayers));
                currentLayers = new ArrayList<>();
                usedKeys = new HashSet<>();
            }
            int layerIndex = currentLayers.size();
            currentLayers.add(new ColorLayer(
                    layerIndex,
                    COLOR_NAMES[layerIndex],
                    COLOR_HEX[layerIndex],
                    frame.index,
                    frame.sourceTime,
                    frame.keys
            ));
            usedKeys.addAll(frameKeys);
        }
        if (!currentLayers.isEmpty()) {
            images.add(new ColorImage(images.size() + 1, currentLayers));
        }
        return new Conversion(
                title,
                source.sourceName,
                source.sourceFilename,
                source.songIndex,
                source.author,
                source.transcribedBy,
                source.noteCount,
                source.frames.size(),
                images,
                source.warnings
        );
    }

    public static List<String> keyLabels() {
        return Collections.unmodifiableList(
                Arrays.asList(BlackScoreReader.KEY_LABELS.clone())
        );
    }

    public static final class Conversion {
        public final String title;
        public final String sourceName;
        public final String sourceFilename;
        public final int sourceSongIndex;
        public final String author;
        public final String transcribedBy;
        public final int sourceNoteCount;
        public final int sourceFrameCount;
        public final List<ColorImage> images;
        public final List<String> warnings;

        private Conversion(
                String title,
                String sourceName,
                String sourceFilename,
                int sourceSongIndex,
                String author,
                String transcribedBy,
                int sourceNoteCount,
                int sourceFrameCount,
                List<ColorImage> images,
                List<String> warnings
        ) {
            this.title = title;
            this.sourceName = sourceName;
            this.sourceFilename = sourceFilename;
            this.sourceSongIndex = sourceSongIndex;
            this.author = author;
            this.transcribedBy = transcribedBy;
            this.sourceNoteCount = sourceNoteCount;
            this.sourceFrameCount = sourceFrameCount;
            this.images = immutable(images);
            this.warnings = immutable(warnings);
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

    public static final class ColorLayer {
        public final int index;
        public final String color;
        public final String hex;
        public final int sourceFrameIndex;
        public final Integer sourceTime;
        public final List<String> keys;

        private ColorLayer(
                int index,
                String color,
                String hex,
                int sourceFrameIndex,
                Integer sourceTime,
                List<String> keys
        ) {
            this.index = index;
            this.color = color;
            this.hex = hex;
            this.sourceFrameIndex = sourceFrameIndex;
            this.sourceTime = sourceTime;
            this.keys = immutable(keys);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
