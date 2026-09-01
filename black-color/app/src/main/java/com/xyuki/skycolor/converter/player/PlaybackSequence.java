package com.xyuki.skycolor.converter.player;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;
import com.xyuki.skycolor.converter.core.ColorScoreReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable playback-ready view of a converted or imported color score. */
public final class PlaybackSequence {
    public static final long NOTE_TAIL_MS = 1100L;

    public final String title;
    public final String sourceName;
    public final String sourceFilename;
    public final String key;
    public final List<PlaybackEvent> events;
    public final long durationMs;
    public final boolean hasTimedPlayback;
    public final String timingError;

    private PlaybackSequence(
            String title,
            String sourceName,
            String sourceFilename,
            String key,
            List<PlaybackEvent> events,
            long durationMs,
            boolean hasTimedPlayback,
            String timingError
    ) {
        this.title = title;
        this.sourceName = sourceName;
        this.sourceFilename = sourceFilename;
        this.key = key;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.durationMs = durationMs;
        this.hasTimedPlayback = hasTimedPlayback;
        this.timingError = timingError;
    }

    public static PlaybackSequence fromConversion(ColorScoreConverter.Conversion conversion) {
        if (conversion == null) {
            throw new IllegalArgumentException("彩谱转换结果不能为空");
        }
        List<LayerData> layers = new ArrayList<>();
        for (int imageIndex = 0; imageIndex < conversion.images.size(); imageIndex++) {
            ColorScoreConverter.ColorImage image = conversion.images.get(imageIndex);
            for (int layerIndex = 0; layerIndex < image.layers.size(); layerIndex++) {
                ColorScoreConverter.ColorLayer layer = image.layers.get(layerIndex);
                layers.add(new LayerData(
                        imageIndex,
                        layerIndex,
                        layer.color,
                        layer.keys,
                        layer.sourceFrameIndex,
                        layer.sourceTime
                ));
            }
        }
        return create(
                conversion.title,
                conversion.sourceName,
                conversion.sourceFilename,
                "C",
                layers
        );
    }

    public static PlaybackSequence fromColorDocument(ColorScoreReader.ColorDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("彩谱文档不能为空");
        }
        List<LayerData> layers = new ArrayList<>();
        for (int imageIndex = 0; imageIndex < document.images.size(); imageIndex++) {
            ColorScoreReader.ColorImage image = document.images.get(imageIndex);
            for (int layerIndex = 0; layerIndex < image.layers.size(); layerIndex++) {
                ColorScoreReader.ColorLayer layer = image.layers.get(layerIndex);
                layers.add(new LayerData(
                        imageIndex,
                        layerIndex,
                        layer.color,
                        layer.keys,
                        layer.sourceFrameIndex,
                        layer.sourceTime
                ));
            }
        }
        return create(
                document.title,
                document.sourceName,
                document.sourceFilename,
                document.key,
                layers
        );
    }

    public boolean hasTimedPlayback() {
        return hasTimedPlayback;
    }

    private static PlaybackSequence create(
            String title,
            String sourceName,
            String sourceFilename,
            String key,
            List<LayerData> layers
    ) {
        boolean timed = true;
        String timingError = "";
        long firstTime = 0L;
        long previousTime = Long.MIN_VALUE;
        boolean hasFirstTime = false;
        for (LayerData layer : layers) {
            if (layer.sourceTime == null) {
                timed = false;
                timingError = "谱面缺少有效 source_time，只能手动试听";
                continue;
            }
            long sourceTime = layer.sourceTime;
            if (sourceTime < 0) {
                timed = false;
                timingError = "谱面包含负数 source_time，只能手动试听";
            }
            if (previousTime != Long.MIN_VALUE && sourceTime < previousTime) {
                timed = false;
                timingError = "source_time 未按谱面顺序递增，只能手动试听";
            }
            if (!hasFirstTime) {
                firstTime = sourceTime;
                hasFirstTime = true;
            }
            previousTime = sourceTime;
        }
        List<PlaybackEvent> events = new ArrayList<>();
        long lastTimelineTime = 0L;
        for (LayerData layer : layers) {
            Long timelineTime = timed && layer.sourceTime != null
                    ? Math.max(0L, (long) layer.sourceTime - firstTime)
                    : null;
            if (timelineTime != null) {
                lastTimelineTime = Math.max(lastTimelineTime, timelineTime);
            }
            events.add(new PlaybackEvent(
                    title,
                    key,
                    layer.imageIndex,
                    layer.layerIndex,
                    layer.color,
                    layer.keys,
                    layer.sourceFrameIndex,
                    layer.sourceTime,
                    timelineTime
            ));
        }
        long duration = timed && !events.isEmpty() ? lastTimelineTime + NOTE_TAIL_MS : 0L;
        return new PlaybackSequence(
                title,
                sourceName,
                sourceFilename,
                key,
                events,
                duration,
                timed,
                timingError
        );
    }

    private static final class LayerData {
        final int imageIndex;
        final int layerIndex;
        final String color;
        final List<String> keys;
        final Integer sourceFrameIndex;
        final Integer sourceTime;

        LayerData(
                int imageIndex,
                int layerIndex,
                String color,
                List<String> keys,
                Integer sourceFrameIndex,
                Integer sourceTime
        ) {
            this.imageIndex = imageIndex;
            this.layerIndex = layerIndex;
            this.color = color;
            this.keys = keys;
            this.sourceFrameIndex = sourceFrameIndex;
            this.sourceTime = sourceTime;
        }
    }
}
