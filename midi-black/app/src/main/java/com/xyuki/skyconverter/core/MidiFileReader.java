package com.xyuki.skyconverter.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small Standard MIDI File reader used by the Android converter.
 *
 * <p>The reader intentionally handles the pieces needed for a note sheet:
 * tempo meta events, track names, channel note on/off events, and the MIDI
 * running-status form. It does not depend on a native MIDI playback stack or
 * a third-party parser, which keeps the APK usable offline.</p>
 */
public final class MidiFileReader {
    private static final int DEFAULT_TEMPO = 500_000;

    private MidiFileReader() {
    }

    public static final class MidiParseException extends IllegalArgumentException {
        public MidiParseException(String message) {
            super(message);
        }
    }

    public static final class Note {
        public final long startTick;
        public final long endTick;
        public final int track;
        public final int channel;
        public final int pitch;
        public final int velocity;
        public final int startMs;
        public final int durationMs;

        private Note(
                long startTick,
                long endTick,
                int track,
                int channel,
                int pitch,
                int velocity,
                int startMs,
                int durationMs
        ) {
            this.startTick = startTick;
            this.endTick = endTick;
            this.track = track;
            this.channel = channel;
            this.pitch = pitch;
            this.velocity = velocity;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    public static final class TempoChange {
        public final long tick;
        public final int microsecondsPerBeat;
        public final int track;
        public final int messageIndex;

        private TempoChange(long tick, int microsecondsPerBeat, int track, int messageIndex) {
            this.tick = tick;
            this.microsecondsPerBeat = microsecondsPerBeat;
            this.track = track;
            this.messageIndex = messageIndex;
        }
    }

    public static final class Result {
        public final List<Note> notes;
        public final int ticksPerBeat;
        public final String title;
        public final List<String> warnings;
        public final List<TempoChange> tempoChanges;

        private Result(
                List<Note> notes,
                int ticksPerBeat,
                String title,
                List<String> warnings,
                List<TempoChange> tempoChanges
        ) {
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
            this.ticksPerBeat = ticksPerBeat;
            this.title = title;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.tempoChanges = Collections.unmodifiableList(new ArrayList<>(tempoChanges));
        }
    }

    private static final class PendingNote {
        final long startTick;
        final int pitch;
        final int velocity;
        final int track;
        final int channel;

        PendingNote(long startTick, int pitch, int velocity, int track, int channel) {
            this.startTick = startTick;
            this.pitch = pitch;
            this.velocity = velocity;
            this.track = track;
            this.channel = channel;
        }
    }

    private static final class RawNote {
        final long startTick;
        final long endTick;
        final int pitch;
        final int velocity;
        final int track;
        final int channel;

        RawNote(long startTick, long endTick, int pitch, int velocity, int track, int channel) {
            this.startTick = startTick;
            this.endTick = endTick;
            this.pitch = pitch;
            this.velocity = velocity;
            this.track = track;
            this.channel = channel;
        }
    }

    private static final class TempoSegment {
        final long startTick;
        final double startMs;
        final int tempo;

        TempoSegment(long startTick, double startMs, int tempo) {
            this.startTick = startTick;
            this.startMs = startMs;
            this.tempo = tempo;
        }
    }

    private static final class Cursor {
        final byte[] data;
        final int limit;
        int position;

        Cursor(byte[] data, int position, int limit) {
            this.data = data;
            this.position = position;
            this.limit = limit;
        }

        int remaining() {
            return limit - position;
        }

        int readUnsignedByte(String what) {
            require(1, what);
            return data[position++] & 0xFF;
        }

        int readUnsignedShort(String what) {
            return (readUnsignedByte(what) << 8) | readUnsignedByte(what);
        }

        long readUnsignedInt(String what) {
            return ((long) readUnsignedByte(what) << 24)
                    | ((long) readUnsignedByte(what) << 16)
                    | ((long) readUnsignedByte(what) << 8)
                    | readUnsignedByte(what);
        }

        byte[] readBytes(int length, String what) {
            if (length < 0) {
                throw new MidiParseException("负的 " + what + " 长度");
            }
            require(length, what);
            byte[] result = new byte[length];
            System.arraycopy(data, position, result, 0, length);
            position += length;
            return result;
        }

        void skip(int length, String what) {
            if (length < 0) {
                throw new MidiParseException("负的 " + what + " 长度");
            }
            require(length, what);
            position += length;
        }

        private void require(int length, String what) {
            if (length > remaining()) {
                throw new MidiParseException("MIDI 数据在读取 " + what + " 时提前结束");
            }
        }
    }

    private static final class TrackData {
        final List<RawNote> notes = new ArrayList<>();
        final List<TempoChange> tempoChanges = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        String title = "";
    }

    public static Result read(byte[] data, String sourceName) {
        if (data == null || data.length == 0) {
            throw new MidiParseException("MIDI 文件为空");
        }
        Cursor cursor = new Cursor(data, 0, data.length);
        requireChunk(cursor, "MThd", "MIDI 头");
        long headerLengthLong = cursor.readUnsignedInt("MIDI 头长度");
        if (headerLengthLong < 6 || headerLengthLong > Integer.MAX_VALUE) {
            throw new MidiParseException("MIDI 头长度无效：" + headerLengthLong);
        }
        int headerLength = (int) headerLengthLong;
        int format = cursor.readUnsignedShort("MIDI 格式");
        int trackCount = cursor.readUnsignedShort("轨道数量");
        int division = cursor.readUnsignedShort("时间分辨率");
        if ((division & 0x8000) != 0) {
            throw new MidiParseException("暂不支持 SMPTE 时间分辨率，只支持 PPQ MIDI");
        }
        if (division == 0) {
            throw new MidiParseException("MIDI 的 ticks_per_beat 必须为正数");
        }
        if (format > 2) {
            throw new MidiParseException("不支持的 MIDI 格式：" + format);
        }
        if (trackCount <= 0) {
            throw new MidiParseException("MIDI 没有轨道");
        }
        cursor.skip(headerLength - 6, "MIDI 头扩展数据");

        List<RawNote> rawNotes = new ArrayList<>();
        List<TempoChange> tempoChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String title = "";
        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            requireChunk(cursor, "MTrk", "轨道 " + trackIndex + " 标记");
            long trackLengthLong = cursor.readUnsignedInt("轨道 " + trackIndex + " 长度");
            if (trackLengthLong > Integer.MAX_VALUE || trackLengthLong > cursor.remaining()) {
                throw new MidiParseException("轨道 " + trackIndex + " 长度超出文件范围");
            }
            int trackEnd = cursor.position + (int) trackLengthLong;
            TrackData track = readTrack(data, cursor.position, trackEnd, trackIndex, division);
            cursor.position = trackEnd;
            rawNotes.addAll(track.notes);
            tempoChanges.addAll(track.tempoChanges);
            warnings.addAll(track.warnings);
            if (title.isEmpty() && !track.title.isEmpty()) {
                title = track.title;
            }
        }

        Collections.sort(
                rawNotes,
                Comparator.comparingLong((RawNote note) -> note.startTick)
                        .thenComparingInt(note -> note.track)
                        .thenComparingInt(note -> note.channel)
                        .thenComparingInt(note -> note.pitch)
                        .thenComparingLong(note -> note.endTick)
        );
        Collections.sort(
                tempoChanges,
                Comparator.comparingLong((TempoChange change) -> change.tick)
                        .thenComparingInt(change -> change.track)
                        .thenComparingInt(change -> change.messageIndex)
        );
        List<TempoSegment> segments = buildTempoSegments(tempoChanges, division);
        List<Note> notes = new ArrayList<>();
        for (RawNote raw : rawNotes) {
            int startMs = tickToMs(raw.startTick, division, segments);
            int endMs = tickToMs(raw.endTick, division, segments);
            notes.add(
                    new Note(
                            raw.startTick,
                            raw.endTick,
                            raw.track,
                            raw.channel,
                            raw.pitch,
                            raw.velocity,
                            startMs,
                            Math.max(1, endMs - startMs)
                    )
            );
        }
        String finalTitle = title.isEmpty() ? stemOf(sourceName) : title;
        return new Result(notes, division, finalTitle, warnings, tempoChanges);
    }

    private static TrackData readTrack(
            byte[] data,
            int start,
            int end,
            int trackIndex,
            int ticksPerBeat
    ) {
        Cursor cursor = new Cursor(data, start, end);
        TrackData result = new TrackData();
        Map<Integer, ArrayDeque<PendingNote>> active = new HashMap<>();
        long absoluteTick = 0;
        long trackEndTick = 0;
        int runningStatus = 0;
        int messageIndex = 0;
        while (cursor.position < end) {
            long delta = readVariableLength(cursor, "轨道 " + trackIndex + " 的 delta-time");
            absoluteTick += delta;
            trackEndTick = absoluteTick;
            messageIndex++;
            int first = cursor.readUnsignedByte("轨道消息");
            int status;
            int firstData = -1;
            if (first < 0x80) {
                if (runningStatus == 0) {
                    throw new MidiParseException(
                            "轨道 " + trackIndex + " 第 " + messageIndex + " 条消息缺少 running status"
                    );
                }
                status = runningStatus;
                firstData = first;
            } else {
                status = first;
                if (status >= 0x80 && status <= 0xEF) {
                    runningStatus = status;
                }
            }

            if (status == 0xFF) {
                int metaType = cursor.readUnsignedByte("meta 事件类型");
                int length = readLength(cursor, "meta 事件长度");
                byte[] payload = cursor.readBytes(length, "meta 事件数据");
                runningStatus = 0;
                if (metaType == 0x51 && length == 3) {
                    int tempo = ((payload[0] & 0xFF) << 16)
                            | ((payload[1] & 0xFF) << 8)
                            | (payload[2] & 0xFF);
                    if (tempo <= 0) {
                        result.warnings.add("轨道 " + trackIndex + " 出现无效 tempo，已忽略");
                    } else {
                        result.tempoChanges.add(
                                new TempoChange(absoluteTick, tempo, trackIndex, messageIndex)
                        );
                    }
                } else if (metaType == 0x03 && result.title.isEmpty() && length > 0) {
                    result.title = decodeText(payload);
                }
                if (metaType == 0x2F) {
                    break;
                }
                continue;
            }
            if (status == 0xF0 || status == 0xF7) {
                int length = readLength(cursor, "SysEx 事件长度");
                cursor.skip(length, "SysEx 事件数据");
                runningStatus = 0;
                continue;
            }
            if (status < 0x80 || status > 0xEF) {
                throw new MidiParseException(
                        "轨道 " + trackIndex + " 第 " + messageIndex
                                + " 条消息使用不支持的状态字节：0x" + Integer.toHexString(status)
                );
            }

            int eventType = status & 0xF0;
            int channel = status & 0x0F;
            int dataBytes = (eventType == 0xC0 || eventType == 0xD0) ? 1 : 2;
            int data1 = firstData >= 0 ? firstData : cursor.readUnsignedByte("MIDI 数据字节");
            int data2 = dataBytes == 2 ? cursor.readUnsignedByte("MIDI 数据字节") : 0;
            if (eventType == 0x90 && data2 > 0) {
                int key = (channel << 8) | data1;
                active.computeIfAbsent(key, ignored -> new ArrayDeque<>())
                        .addLast(new PendingNote(absoluteTick, data1, data2, trackIndex, channel));
            } else if (eventType == 0x80 || (eventType == 0x90 && data2 == 0)) {
                int key = (channel << 8) | data1;
                ArrayDeque<PendingNote> starts = active.get(key);
                if (starts == null || starts.isEmpty()) {
                    result.warnings.add(
                            "轨道 " + trackIndex + " 第 " + messageIndex
                                    + " 条消息出现未匹配的 note_off：音高 " + data1
                    );
                } else {
                    PendingNote startNote = starts.removeFirst();
                    result.notes.add(
                            new RawNote(
                                    startNote.startTick,
                                    Math.max(startNote.startTick + 1, absoluteTick),
                                    startNote.pitch,
                                    startNote.velocity,
                                    startNote.track,
                                    startNote.channel
                            )
                    );
                }
            }
        }

        for (ArrayDeque<PendingNote> starts : active.values()) {
            while (!starts.isEmpty()) {
                PendingNote startNote = starts.removeFirst();
                long endTick = Math.max(trackEndTick, startNote.startTick + ticksPerBeat);
                result.warnings.add(
                        "轨道 " + trackIndex + " 的音符 " + startNote.pitch
                                + " 缺少 note_off，已按一拍补齐"
                );
                result.notes.add(
                        new RawNote(
                                startNote.startTick,
                                endTick,
                                startNote.pitch,
                                startNote.velocity,
                                startNote.track,
                                startNote.channel
                        )
                );
            }
        }
        return result;
    }

    private static List<TempoSegment> buildTempoSegments(
            List<TempoChange> changes,
            int ticksPerBeat
    ) {
        List<TempoSegment> segments = new ArrayList<>();
        segments.add(new TempoSegment(0, 0.0, DEFAULT_TEMPO));
        long currentTick = 0;
        double currentMs = 0.0;
        int currentTempo = DEFAULT_TEMPO;
        for (TempoChange change : changes) {
            if (change.tick == currentTick) {
                currentTempo = change.microsecondsPerBeat;
                segments.set(segments.size() - 1, new TempoSegment(currentTick, currentMs, currentTempo));
                continue;
            }
            if (change.tick < currentTick) {
                continue;
            }
            currentMs += (change.tick - currentTick) * (double) currentTempo
                    / ticksPerBeat / 1000.0;
            currentTick = change.tick;
            currentTempo = change.microsecondsPerBeat;
            segments.add(new TempoSegment(currentTick, currentMs, currentTempo));
        }
        return segments;
    }

    private static int tickToMs(long tick, int ticksPerBeat, List<TempoSegment> segments) {
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (segments.get(middle).startTick <= tick) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        TempoSegment segment = segments.get(low);
        double milliseconds = segment.startMs
                + (tick - segment.startTick) * (double) segment.tempo
                / ticksPerBeat / 1000.0;
        if (milliseconds >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(milliseconds + 0.5);
    }

    private static int readLength(Cursor cursor, String what) {
        long value = readVariableLength(cursor, what);
        if (value > Integer.MAX_VALUE) {
            throw new MidiParseException(what + "过大：" + value);
        }
        return (int) value;
    }

    private static long readVariableLength(Cursor cursor, String what) {
        long value = 0;
        for (int index = 0; index < 4; index++) {
            int current = cursor.readUnsignedByte(what);
            value = (value << 7) | (current & 0x7F);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new MidiParseException(what + "超过 MIDI 允许的 4 字节长度");
    }

    private static void requireChunk(Cursor cursor, String expected, String what) {
        byte[] actual = cursor.readBytes(4, what + "标记");
        String text = new String(actual, StandardCharsets.US_ASCII);
        if (!expected.equals(text)) {
            throw new MidiParseException(what + "无效：期望 " + expected + "，实际 " + text);
        }
    }

    private static String decodeText(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    private static String stemOf(String sourceName) {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return "未命名歌曲";
        }
        String name = sourceName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isEmpty() ? "未命名歌曲" : name;
    }
}
