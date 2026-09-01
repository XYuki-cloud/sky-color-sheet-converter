package com.xyuki.skycolor.converter.player;

import java.util.List;

/** Coordinates timed/manual playback without binding the playback rules to Android views. */
public final class PlaybackController {
    private final PlaybackSequence sequence;
    private final AudioSink audioSink;
    private final Listener listener;
    private State state = State.IDLE;
    private int nextTimedIndex;
    private int manualIndex;
    private int currentEventIndex = -1;
    private long positionMs;
    private long startedAtMs;
    private float volume = 0.68f;
    private int transpose;
    private double speedMultiplier = PlaybackSpeed.MAX_MULTIPLIER;

    public PlaybackController(
            PlaybackSequence sequence,
            AudioSink audioSink,
            Listener listener
    ) {
        if (sequence == null) {
            throw new IllegalArgumentException("播放序列不能为空");
        }
        if (audioSink == null) {
            throw new IllegalArgumentException("音频输出不能为空");
        }
        this.sequence = sequence;
        this.audioSink = audioSink;
        this.listener = listener == null ? new Listener() { } : listener;
    }

    public boolean start(long nowMs) {
        if (sequence.events.isEmpty()) {
            reportError("谱面没有可播放的音符");
            return false;
        }
        if (!sequence.hasTimedPlayback()) {
            reportError(sequence.timingError.isEmpty()
                    ? "谱面缺少有效 source_time，只能手动试听"
                    : sequence.timingError);
            return false;
        }
        if (state == State.PLAYING) {
            return true;
        }
        if (state == State.COMPLETED) {
            resetPosition();
        }
        startedAtMs = nowMs - Math.round(positionMs / speedMultiplier);
        state = State.PLAYING;
        listener.onStateChanged(state);
        tick(nowMs);
        return true;
    }

    public void pause(long nowMs) {
        if (state != State.PLAYING) {
            return;
        }
        tick(nowMs);
        if (state == State.PLAYING) {
            audioSink.stop();
            state = State.PAUSED;
            listener.onStateChanged(state);
        }
    }

    /** Advances timed playback and is intended to be called from a UI ticker. */
    public void tick(long nowMs) {
        if (state != State.PLAYING) {
            return;
        }
        long wallElapsed = Math.max(0L, nowMs - startedAtMs);
        long elapsed = Math.round(wallElapsed * speedMultiplier);
        elapsed = Math.min(sequence.durationMs, elapsed);
        while (nextTimedIndex < sequence.events.size()) {
            PlaybackEvent event = sequence.events.get(nextTimedIndex);
            long eventTime = event.timelineTimeMs == null ? Long.MAX_VALUE : event.timelineTimeMs;
            if (eventTime > elapsed) {
                break;
            }
            if (!playEvent(event)) {
                return;
            }
            currentEventIndex = nextTimedIndex;
            manualIndex = nextTimedIndex + 1;
            listener.onEvent(event);
            nextTimedIndex++;
        }
        positionMs = elapsed;
        listener.onPositionChanged(positionMs, currentEventIndex);
        if (nextTimedIndex >= sequence.events.size() && positionMs >= sequence.durationMs) {
            audioSink.stop();
            state = State.COMPLETED;
            listener.onStateChanged(state);
        }
    }

    public void stop() {
        audioSink.stop();
        resetPosition();
        state = State.STOPPED;
        listener.onPositionChanged(positionMs, currentEventIndex);
        listener.onStateChanged(state);
    }

    public boolean seekTo(long requestedPositionMs) {
        if (!sequence.hasTimedPlayback()) {
            reportError(sequence.timingError.isEmpty()
                    ? "谱面缺少有效 source_time，无法定位"
                    : sequence.timingError);
            return false;
        }
        long target = Math.max(0L, Math.min(sequence.durationMs, requestedPositionMs));
        audioSink.stop();
        positionMs = target;
        nextTimedIndex = firstEventAtOrAfter(target);
        manualIndex = nextTimedIndex;
        currentEventIndex = nextTimedIndex - 1;
        state = State.PAUSED;
        listener.onPositionChanged(positionMs, currentEventIndex);
        listener.onStateChanged(state);
        return true;
    }

    public boolean stepNext() {
        if (sequence.events.isEmpty()) {
            reportError("谱面没有可播放的音符");
            return false;
        }
        audioSink.stop();
        if (manualIndex >= sequence.events.size()) {
            manualIndex = 0;
        }
        PlaybackEvent event = sequence.events.get(manualIndex);
        currentEventIndex = manualIndex;
        manualIndex++;
        nextTimedIndex = manualIndex;
        if (event.timelineTimeMs != null) {
            positionMs = event.timelineTimeMs;
        }
        if (!playEvent(event)) {
            return false;
        }
        listener.onEvent(event);
        listener.onPositionChanged(positionMs, currentEventIndex);
        state = State.PAUSED;
        listener.onStateChanged(state);
        return true;
    }

    public boolean stepPrevious() {
        if (sequence.events.isEmpty()) {
            reportError("谱面没有可播放的音符");
            return false;
        }
        audioSink.stop();
        currentEventIndex = currentEventIndex < 0
                ? 0 : Math.max(0, currentEventIndex - 1);
        manualIndex = currentEventIndex + 1;
        nextTimedIndex = manualIndex;
        PlaybackEvent event = sequence.events.get(currentEventIndex);
        if (event.timelineTimeMs != null) {
            positionMs = event.timelineTimeMs;
        }
        if (!playEvent(event)) {
            return false;
        }
        listener.onEvent(event);
        state = State.PAUSED;
        listener.onPositionChanged(positionMs, currentEventIndex);
        listener.onStateChanged(state);
        return true;
    }

    public void setVolume(float value) {
        volume = Math.max(0f, Math.min(1f, value));
    }

    public void setTranspose(int value) {
        transpose = Math.max(-12, Math.min(12, value));
    }

    /** Changes automatic-playback speed while keeping the current logical score position. */
    public void setSpeed(double multiplier, long nowMs) {
        double bounded = PlaybackSpeed.clamp(multiplier);
        if (state == State.PLAYING) {
            tick(nowMs);
            if (state == State.PLAYING) {
                speedMultiplier = bounded;
                startedAtMs = nowMs - Math.round(positionMs / speedMultiplier);
            } else {
                speedMultiplier = bounded;
            }
        } else {
            speedMultiplier = bounded;
        }
        listener.onPositionChanged(positionMs, currentEventIndex);
    }

    public State state() {
        return state;
    }

    public long positionMs() {
        return positionMs;
    }

    public int currentEventIndex() {
        return currentEventIndex;
    }

    public float volume() {
        return volume;
    }

    public int transpose() {
        return transpose;
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public PlaybackEvent currentEvent() {
        if (currentEventIndex < 0 || currentEventIndex >= sequence.events.size()) {
            return null;
        }
        return sequence.events.get(currentEventIndex);
    }

    public void release() {
        audioSink.stop();
        audioSink.release();
    }

    private int firstEventAtOrAfter(long target) {
        for (int index = 0; index < sequence.events.size(); index++) {
            Long time = sequence.events.get(index).timelineTimeMs;
            if (time != null && time >= target) {
                return index;
            }
        }
        return sequence.events.size();
    }

    private void resetPosition() {
        nextTimedIndex = 0;
        manualIndex = 0;
        currentEventIndex = -1;
        positionMs = 0L;
        startedAtMs = 0L;
    }

    private void reportError(String message) {
        listener.onError(message);
    }

    private boolean playEvent(PlaybackEvent event) {
        try {
            audioSink.play(event.keys, sequence.key, volume, transpose);
            return true;
        } catch (RuntimeException exception) {
            audioSink.stop();
            state = State.PAUSED;
            String message = exception.getMessage();
            listener.onError(message == null || message.trim().isEmpty()
                    ? "音频播放失败" : "音频播放失败：" + message);
            listener.onStateChanged(state);
            return false;
        }
    }

    public enum State {
        IDLE,
        PLAYING,
        PAUSED,
        STOPPED,
        COMPLETED
    }

    public interface AudioSink {
        void play(List<String> keys, String key, float volume, int transpose);

        void stop();

        default void release() {
        }
    }

    public interface Listener {
        default void onStateChanged(State state) {
        }

        default void onEvent(PlaybackEvent event) {
        }

        default void onPositionChanged(long positionMs, int currentEventIndex) {
        }

        default void onError(String message) {
        }
    }
}
