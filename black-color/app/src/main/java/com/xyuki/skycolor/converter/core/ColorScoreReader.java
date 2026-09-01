package com.xyuki.skycolor.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads an existing sky-color-v1 score for preview only. */
public final class ColorScoreReader {
    private static final String[] COLORS = {"black", "red", "blue"};
    private static final String[] HEX = {"#000000", "#FF0000", "#0000FF"};
    private static final Set<String> TONALITIES = Set.of(
            "C", "C#", "DB", "D", "D#", "EB", "E", "F", "F#", "GB", "G",
            "G#", "AB", "A", "A#", "BB", "B"
    );

    private ColorScoreReader() {
    }

    public static ColorDocument read(byte[] data, String sourceName) {
        Object payload = parsePayload(data);
        return readPayload(payload, sourceName);
    }

    /** Returns null for a non-color payload, while preserving malformed JSON errors. */
    public static ColorDocument readIfColor(byte[] data, String sourceName) {
        Object payload = parsePayload(data);
        if (!(payload instanceof Map<?, ?> map)) {
            return null;
        }
        if (!"sky-color-v1".equalsIgnoreCase(BlackScoreReader.textValue(map.get("format")))) {
            return null;
        }
        return readPayload(map, sourceName);
    }

    private static Object parsePayload(byte[] data) {
        if (data == null || data.length == 0) {
            throw new BlackScoreReader.ScoreFormatException("输入文件为空");
        }
        try {
            return JsonValueParser.parse(BlackScoreReader.decodeText(data));
        } catch (IllegalArgumentException exception) {
            if (exception instanceof BlackScoreReader.ScoreFormatException) {
                throw exception;
            }
            throw new BlackScoreReader.ScoreFormatException(
                    "JSON 解析失败：" + exception.getMessage(),
                    exception
            );
        }
    }

    private static ColorDocument readPayload(Object payload, String sourceName) {
        if (!(payload instanceof Map<?, ?> root)) {
            throw new BlackScoreReader.ScoreFormatException(
                    "彩谱根节点必须是 JSON 对象且 format 为 sky-color-v1"
            );
        }
        String format = BlackScoreReader.textValue(root.get("format"));
        if (!"sky-color-v1".equalsIgnoreCase(format)) {
            throw new BlackScoreReader.ScoreFormatException(
                    "需要 format=sky-color-v1 的彩谱"
            );
        }

        Map<?, ?> source = BlackScoreReader.optionalObject(root.get("source"), "source");
        String sourceFilename = BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(source.get("filename")),
                BlackScoreReader.textValue(root.get("source_filename")),
                BlackScoreReader.safeSourceName(sourceName)
        );
        String title = BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(root.get("title")),
                BlackScoreReader.textValue(root.get("name")),
                BlackScoreReader.textValue(source.get("name")),
                BlackScoreReader.textValue(source.get("title")),
                BlackScoreReader.fileStem(sourceFilename),
                BlackScoreReader.fileStem(sourceName)
        );
        String author = BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(root.get("author")),
                BlackScoreReader.textValue(source.get("author"))
        );
        String transcribedBy = BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(root.get("transcribed_by")),
                BlackScoreReader.textValue(root.get("transcribedBy")),
                BlackScoreReader.textValue(source.get("transcribed_by")),
                BlackScoreReader.textValue(source.get("transcribedBy"))
        );
        String key = normalizeTonality(BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(root.get("key")),
                BlackScoreReader.textValue(source.get("key")),
                "C"
        ));

        Object imagesValue = root.get("images");
        if (!(imagesValue instanceof List<?> images)) {
            throw new BlackScoreReader.ScoreFormatException("彩谱缺少有效的 images 数组");
        }
        List<ColorImage> parsedImages = new ArrayList<>();
        int calculatedNoteCount = 0;
        for (int imagePosition = 0; imagePosition < images.size(); imagePosition++) {
            Map<?, ?> image = BlackScoreReader.requireObject(
                    images.get(imagePosition),
                    "第 " + (imagePosition + 1) + " 张图"
            );
            Object layersValue = image.get("layers");
            if (!(layersValue instanceof List<?> layers)) {
                throw new BlackScoreReader.ScoreFormatException(
                        "第 " + (imagePosition + 1) + " 张图缺少 layers 数组"
                );
            }
            if (layers.size() < 1 || layers.size() > 3) {
                throw new BlackScoreReader.ScoreFormatException(
                        "第 " + (imagePosition + 1) + " 张图的层数必须为 1 到 3"
                );
            }
            Integer imageIndex = BlackScoreReader.optionalInteger(image.get("index"), "image.index");
            int resolvedImageIndex = imageIndex == null ? imagePosition + 1 : imageIndex;
            if (resolvedImageIndex < 1) {
                throw new BlackScoreReader.ScoreFormatException("image.index 必须是正整数");
            }
            Integer imageTime = firstInteger(
                    image.get("source_time"),
                    image.get("sourceTime"),
                    "source_time"
            );
            if (imageTime != null && imageTime < 0) {
                throw new BlackScoreReader.ScoreFormatException("source_time 不能为负数");
            }
            List<ColorLayer> parsedLayers = new ArrayList<>();
            Set<String> usedKeys = new HashSet<>();
            for (int layerPosition = 0; layerPosition < layers.size(); layerPosition++) {
                Map<?, ?> layer = BlackScoreReader.requireObject(
                        layers.get(layerPosition),
                        "第 " + (imagePosition + 1) + " 张图的第 " + (layerPosition + 1) + " 层"
                );
                String expectedColor = COLORS[layerPosition];
                String color = BlackScoreReader.textValue(layer.get("color"));
                if (!expectedColor.equalsIgnoreCase(color)) {
                    throw new BlackScoreReader.ScoreFormatException(
                            "第 " + (imagePosition + 1) + " 张图的颜色顺序必须为黑、红、蓝"
                    );
                }
                List<String> keys = BlackScoreReader.readKeys(
                        layer.get("keys"),
                        "第 " + (imagePosition + 1) + " 张图的第 " + (layerPosition + 1) + " 层"
                                + "按键"
                );
                for (String noteKey : keys) {
                    if (!usedKeys.add(noteKey)) {
                        throw new BlackScoreReader.ScoreFormatException(
                                "第 " + (imagePosition + 1) + " 张图存在重叠按键：" + noteKey
                        );
                    }
                }
                Integer sourceFrameIndex = firstInteger(
                        layer.get("source_frame_index"),
                        layer.get("sourceFrameIndex"),
                        "source_frame_index"
                );
                if (sourceFrameIndex == null) {
                    sourceFrameIndex = resolvedImageIndex;
                }
                Integer sourceTime = firstInteger(
                        layer.get("source_time"),
                        layer.get("sourceTime"),
                        "source_time"
                );
                if (sourceTime == null) {
                    sourceTime = imageTime;
                }
                if (sourceTime != null && sourceTime < 0) {
                    throw new BlackScoreReader.ScoreFormatException("source_time 不能为负数");
                }
                String hex = BlackScoreReader.firstNonBlank(
                        BlackScoreReader.textValue(layer.get("hex")),
                        HEX[layerPosition]
                );
                parsedLayers.add(new ColorLayer(
                        layerPosition,
                        expectedColor,
                        hex,
                        sourceFrameIndex,
                        sourceTime,
                        keys
                ));
                calculatedNoteCount += keys.size();
            }
            parsedImages.add(new ColorImage(resolvedImageIndex, parsedLayers));
        }
        Integer declaredNoteCount = firstInteger(
                root.get("source_note_count"),
                root.get("sourceNoteCount"),
                "source_note_count"
        );
        int noteCount = declaredNoteCount == null ? calculatedNoteCount : declaredNoteCount;
        if (noteCount < 0) {
            throw new BlackScoreReader.ScoreFormatException("source_note_count 不能为负数");
        }
        return new ColorDocument(
                BlackScoreReader.safeSourceName(sourceName),
                sourceFilename,
                title,
                key,
                author,
                transcribedBy,
                noteCount,
                parsedImages,
                BlackScoreReader.optionalStringList(root.get("warnings"), "warnings")
        );
    }

    private static Integer firstInteger(Object first, Object second, String label) {
        Integer value = BlackScoreReader.optionalInteger(first, label);
        return value == null ? BlackScoreReader.optionalInteger(second, label) : value;
    }

    private static String normalizeTonality(String value) {
        String normalized = BlackScoreReader.firstNonBlank(value, "C")
                .replace('♯', '#')
                .replace('♭', 'b')
                .toUpperCase(Locale.ROOT);
        if (!TONALITIES.contains(normalized)) {
            throw new BlackScoreReader.ScoreFormatException("不支持的调性：" + value);
        }
        return normalized;
    }

    public static final class ColorDocument {
        public final String sourceName;
        public final String sourceFilename;
        public final String title;
        public final String key;
        public final String author;
        public final String transcribedBy;
        public final int noteCount;
        public final List<ColorImage> images;
        public final List<String> warnings;

        private ColorDocument(
                String sourceName,
                String sourceFilename,
                String title,
                String key,
                String author,
                String transcribedBy,
                int noteCount,
                List<ColorImage> images,
                List<String> warnings
        ) {
            this.sourceName = sourceName;
            this.sourceFilename = sourceFilename;
            this.title = title;
            this.key = key;
            this.author = author;
            this.transcribedBy = transcribedBy;
            this.noteCount = noteCount;
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
        public final Integer sourceFrameIndex;
        public final Integer sourceTime;
        public final List<String> keys;

        private ColorLayer(
                int index,
                String color,
                String hex,
                Integer sourceFrameIndex,
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
