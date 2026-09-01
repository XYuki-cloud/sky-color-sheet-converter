package com.xyuki.skycolor.converter.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the two supported black-score input families and normalizes them to source frames.
 */
public final class BlackScoreReader {
    public static final String[] KEY_LABELS = {
            "A1", "A2", "A3", "A4", "A5",
            "B1", "B2", "B3", "B4", "B5",
            "C1", "C2", "C3", "C4", "C5"
    };

    private BlackScoreReader() {
    }

    public static List<ScoreDocument> read(byte[] data, String sourceName) {
        if (data == null || data.length == 0) {
            throw new ScoreFormatException("输入文件为空");
        }
        String displayName = safeSourceName(sourceName);
        String text = decodeText(data);
        Object payload;
        try {
            payload = JsonValueParser.parse(text);
        } catch (IllegalArgumentException exception) {
            throw new ScoreFormatException("JSON 解析失败：" + exception.getMessage(), exception);
        }

        if (payload instanceof Map<?, ?> map) {
            String format = textValue(map.get("format"));
            if ("sky-color-v1".equalsIgnoreCase(format)) {
                throw new ScoreFormatException("不支持已经是 sky-color-v1 的彩谱");
            }
            if ("sky-black-v1".equalsIgnoreCase(format)) {
                return Collections.singletonList(readVersionedBlack(map, displayName));
            }
            if (map.containsKey("songs") || map.containsKey("songNotes")) {
                return LegacyTxtReader.read(payload, displayName);
            }
            throw new ScoreFormatException(
                    "不支持的谱面格式：需要 sky-black-v1 或包含 songNotes 的结构化 TXT"
            );
        }
        if (payload instanceof List<?>) {
            return LegacyTxtReader.read(payload, displayName);
        }
        throw new ScoreFormatException("JSON 根节点必须是对象或歌曲数组");
    }

    static ScoreDocument readVersionedBlack(Map<?, ?> root, String sourceName) {
        Map<?, ?> source = optionalObject(root.get("source"), "source");
        String sourceFilename = firstNonBlank(
                textValue(source.get("filename")),
                textValue(root.get("source_filename")),
                sourceName
        );
        String title = firstNonBlank(
                textValue(root.get("title")),
                textValue(root.get("name")),
                textValue(source.get("name")),
                textValue(source.get("title")),
                fileStem(sourceFilename),
                fileStem(sourceName)
        );
        String author = firstNonBlank(
                textValue(root.get("author")),
                textValue(source.get("author"))
        );
        String transcribedBy = firstNonBlank(
                textValue(root.get("transcribed_by")),
                textValue(source.get("transcribed_by")),
                textValue(source.get("transcribedBy"))
        );
        List<String> warnings = optionalStringList(root.get("warnings"), "warnings");
        Object imagesValue = root.get("images");
        if (!(imagesValue instanceof List<?> images)) {
            throw new ScoreFormatException("黑白谱缺少有效的 images 数组");
        }
        List<SourceFrame> frames = new ArrayList<>();
        int calculatedNoteCount = 0;
        for (int imagePosition = 0; imagePosition < images.size(); imagePosition++) {
            Map<?, ?> image = requireObject(
                    images.get(imagePosition),
                    "第 " + (imagePosition + 1) + " 张图"
            );
            Object layersValue = image.get("layers");
            if (!(layersValue instanceof List<?> layers)) {
                throw new ScoreFormatException("第 " + (imagePosition + 1) + " 张图缺少 layers 数组");
            }
            if (layers.size() != 1) {
                throw new ScoreFormatException(
                        "第 " + (imagePosition + 1) + " 张图必须只有一个黑色层，不能直接读取多层彩谱"
                );
            }
            Map<?, ?> layer = requireObject(
                    layers.get(0),
                    "第 " + (imagePosition + 1) + " 张图的层"
            );
            String color = textValue(layer.get("color"));
            if (!"black".equalsIgnoreCase(color)) {
                throw new ScoreFormatException(
                        "第 " + (imagePosition + 1) + " 张图包含非黑层，输入不是有效黑白谱"
                );
            }
            List<String> keys = readKeys(
                    layer.get("keys"),
                    "第 " + (imagePosition + 1) + " 张图的按键"
            );
            calculatedNoteCount += keys.size();
            Integer imageIndex = optionalInteger(image.get("index"), "image.index");
            Integer frameIndex = optionalInteger(layer.get("source_frame_index"), "source_frame_index");
            if (frameIndex == null) {
                frameIndex = imageIndex == null ? imagePosition + 1 : imageIndex;
            }
            Integer sourceTime = optionalInteger(layer.get("source_time"), "source_time");
            if (sourceTime == null) {
                sourceTime = optionalInteger(image.get("source_time"), "source_time");
            }
            if (sourceTime != null && sourceTime < 0) {
                throw new ScoreFormatException("source_time 不能为负数");
            }
            frames.add(new SourceFrame(frameIndex, sourceTime, keys));
        }
        Integer declaredNoteCount = optionalInteger(root.get("source_note_count"), "source_note_count");
        int noteCount = declaredNoteCount == null ? calculatedNoteCount : declaredNoteCount;
        if (noteCount < 0) {
            throw new ScoreFormatException("source_note_count 不能为负数");
        }
        return new ScoreDocument(
                sourceName,
                sourceFilename,
                0,
                title,
                author,
                transcribedBy,
                noteCount,
                frames,
                warnings
        );
    }

    static Map<?, ?> optionalObject(Object value, String label) {
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new ScoreFormatException(label + " 必须是 JSON 对象");
        }
        return map;
    }

    static Map<?, ?> requireObject(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ScoreFormatException(label + " 必须是 JSON 对象");
        }
        return map;
    }

    static List<String> optionalStringList(Object value, String label) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> list)) {
            throw new ScoreFormatException(label + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String string)) {
                throw new ScoreFormatException(label + " 中存在非字符串项");
            }
            result.add(string);
        }
        return result;
    }

    static List<String> readKeys(Object value, String label) {
        if (!(value instanceof List<?> list)) {
            throw new ScoreFormatException(label + " 必须是按键数组");
        }
        if (list.isEmpty()) {
            throw new ScoreFormatException(label + " 不能为空");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String string)) {
                throw new ScoreFormatException(label + " 中存在非字符串按键");
            }
            String normalized = normalizeKey(string);
            if (normalized == null) {
                throw new ScoreFormatException("存在非法按键：" + string);
            }
            unique.add(normalized);
        }
        List<String> result = new ArrayList<>(unique);
        result.sort(Comparator.comparingInt(BlackScoreReader::keyOrder));
        return result;
    }

    static String textValue(Object value) {
        if (value instanceof String string) {
            return string.trim();
        }
        return "";
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    static String safeSourceName(String sourceName) {
        return firstNonBlank(sourceName, "输入谱面.json");
    }

    static String fileStem(String filename) {
        String value = firstNonBlank(filename, "");
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        return firstNonBlank(value, "未命名歌曲");
    }

    static Integer optionalInteger(Object value, String label) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new ScoreFormatException(label + " 必须是整数");
        }
        double doubleValue = number.doubleValue();
        long longValue = number.longValue();
        if (!Double.isFinite(doubleValue) || doubleValue != longValue
                || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
            throw new ScoreFormatException(label + " 必须是整数");
        }
        return (int) longValue;
    }

    static int requireNonNegativeInteger(Object value, String label) {
        Integer result = optionalInteger(value, label);
        if (result == null || result < 0) {
            throw new ScoreFormatException(label + " 必须是非负整数");
        }
        return result;
    }

    static String normalizeKey(String key) {
        String normalized = firstNonBlank(key, "").toUpperCase(Locale.ROOT);
        for (String label : KEY_LABELS) {
            if (label.equals(normalized)) {
                return label;
            }
        }
        return null;
    }

    static int keyOrder(String key) {
        for (int index = 0; index < KEY_LABELS.length; index++) {
            if (KEY_LABELS[index].equals(key)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    static String decodeText(byte[] data) {
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE
                || (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF)) {
            return new String(data, Charset.forName("UTF-16")).replaceFirst("^\\uFEFF", "");
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && data[0] == 0 && data[1] != 0) {
            return new String(data, StandardCharsets.UTF_16BE);
        }
        if (data.length >= 2 && data[0] != 0 && data[1] == 0) {
            return new String(data, StandardCharsets.UTF_16LE);
        }
        String utf8 = new String(data, StandardCharsets.UTF_8);
        String trimmed = utf8.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return utf8;
        }
        return utf8;
    }

    public static final class ScoreFormatException extends IllegalArgumentException {
        public ScoreFormatException(String message) {
            super(message);
        }

        public ScoreFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class SourceFrame {
        public final int index;
        public final Integer sourceTime;
        public final List<String> keys;

        public SourceFrame(int index, Integer sourceTime, List<String> keys) {
            this.index = index;
            this.sourceTime = sourceTime;
            this.keys = immutable(keys);
        }
    }

    public static final class ScoreDocument {
        public final String sourceName;
        public final String sourceFilename;
        public final int songIndex;
        public final String title;
        public final String author;
        public final String transcribedBy;
        public final int noteCount;
        public final List<SourceFrame> frames;
        public final List<String> warnings;

        public ScoreDocument(
                String sourceName,
                String sourceFilename,
                int songIndex,
                String title,
                String author,
                String transcribedBy,
                int noteCount,
                List<SourceFrame> frames,
                List<String> warnings
        ) {
            this.sourceName = safeSourceName(sourceName);
            this.sourceFilename = firstNonBlank(sourceFilename, this.sourceName);
            this.songIndex = songIndex;
            this.title = firstNonBlank(title, fileStem(this.sourceFilename));
            this.author = firstNonBlank(author, "");
            this.transcribedBy = firstNonBlank(transcribedBy, "");
            this.noteCount = noteCount;
            this.frames = immutable(frames);
            this.warnings = immutable(warnings);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
