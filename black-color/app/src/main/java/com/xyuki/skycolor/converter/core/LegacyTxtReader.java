package com.xyuki.skycolor.converter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses SkyStudio/画世界 structured JSON TXT payloads. */
public final class LegacyTxtReader {
    private static final Pattern RAW_KEY = Pattern.compile("(?i)^(?:\\d+)?Key(\\d+)$");

    private LegacyTxtReader() {
    }

    public static List<BlackScoreReader.ScoreDocument> read(Object payload, String sourceName) {
        List<Map<?, ?>> songs = songObjects(payload);
        if (songs.isEmpty()) {
            throw new BlackScoreReader.ScoreFormatException("文件中没有歌曲");
        }
        List<BlackScoreReader.ScoreDocument> result = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            result.add(readSong(songs.get(index), sourceName, index));
        }
        return result;
    }

    private static List<Map<?, ?>> songObjects(Object payload) {
        List<Map<?, ?>> songs = new ArrayList<>();
        if (payload instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                songs.add(BlackScoreReader.requireObject(list.get(index), "第 " + (index + 1) + " 首歌曲"));
            }
            return songs;
        }
        Map<?, ?> root = BlackScoreReader.requireObject(payload, "歌曲根节点");
        if (root.containsKey("songs")) {
            Object value = root.get("songs");
            if (!(value instanceof List<?> list)) {
                throw new BlackScoreReader.ScoreFormatException("songs 必须是歌曲数组");
            }
            for (int index = 0; index < list.size(); index++) {
                songs.add(BlackScoreReader.requireObject(list.get(index), "第 " + (index + 1) + " 首歌曲"));
            }
            return songs;
        }
        songs.add(root);
        return songs;
    }

    private static BlackScoreReader.ScoreDocument readSong(
            Map<?, ?> song,
            String sourceName,
            int songIndex
    ) {
        Object notesValue = song.get("songNotes");
        if (!(notesValue instanceof List<?> notes)) {
            throw new BlackScoreReader.ScoreFormatException("歌曲缺少有效的 songNotes 数组");
        }
        TreeMap<Integer, LinkedHashSet<String>> grouped = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (int noteIndex = 0; noteIndex < notes.size(); noteIndex++) {
            Map<?, ?> note = BlackScoreReader.requireObject(
                    notes.get(noteIndex),
                    "第 " + (noteIndex + 1) + " 个音符"
            );
            int time = BlackScoreReader.requireNonNegativeInteger(
                    note.get("time"),
                    "第 " + (noteIndex + 1) + " 个音符的 time"
            );
            Object rawValue = note.get("key");
            if (!(rawValue instanceof String rawString) || rawString.trim().isEmpty()) {
                throw new BlackScoreReader.ScoreFormatException(
                        "第 " + (noteIndex + 1) + " 个音符缺少有效的 key"
                );
            }
            String rawKey = rawString.trim();
            Matcher matcher = RAW_KEY.matcher(rawKey);
            if (!matcher.matches()) {
                throw new BlackScoreReader.ScoreFormatException(
                        "第 " + (noteIndex + 1) + " 个音符使用不支持的按键：" + rawKey
                );
            }
            int keyIndex;
            try {
                keyIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new BlackScoreReader.ScoreFormatException("按键编号超出范围：" + rawKey);
            }
            if (keyIndex < 0 || keyIndex >= BlackScoreReader.KEY_LABELS.length) {
                throw new BlackScoreReader.ScoreFormatException("存在非法按键：" + rawKey);
            }
            String label = BlackScoreReader.KEY_LABELS[keyIndex];
            LinkedHashSet<String> keys = grouped.computeIfAbsent(time, ignored -> new LinkedHashSet<>());
            if (!keys.add(label)) {
                warnings.add(
                        "第 " + (noteIndex + 1) + " 个音符在 time=" + time
                                + " 重复按键 " + rawKey + "，已去重"
                );
            }
        }
        List<BlackScoreReader.SourceFrame> frames = new ArrayList<>();
        int frameIndex = 1;
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : grouped.entrySet()) {
            List<String> keys = new ArrayList<>(entry.getValue());
            keys.sort(Comparator.comparingInt(LegacyTxtReader::keyOrder));
            frames.add(new BlackScoreReader.SourceFrame(frameIndex++, entry.getKey(), keys));
        }
        String sourceFilename = firstSourceFilename(song, sourceName);
        String title = BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(song.get("name")),
                BlackScoreReader.textValue(song.get("title")),
                BlackScoreReader.fileStem(sourceFilename)
        );
        return new BlackScoreReader.ScoreDocument(
                sourceName,
                sourceFilename,
                songIndex,
                title,
                BlackScoreReader.textValue(song.get("author")),
                BlackScoreReader.firstNonBlank(
                        BlackScoreReader.textValue(song.get("transcribedBy")),
                        BlackScoreReader.textValue(song.get("transcribed_by"))
                ),
                notes.size(),
                frames,
                warnings
        );
    }

    private static String firstSourceFilename(Map<?, ?> song, String sourceName) {
        Map<?, ?> source = BlackScoreReader.optionalObject(song.get("source"), "source");
        return BlackScoreReader.firstNonBlank(
                BlackScoreReader.textValue(song.get("filename")),
                BlackScoreReader.textValue(source.get("filename")),
                sourceName
        );
    }

    private static int keyOrder(String key) {
        for (int index = 0; index < BlackScoreReader.KEY_LABELS.length; index++) {
            if (BlackScoreReader.KEY_LABELS[index].equals(key)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }
}
