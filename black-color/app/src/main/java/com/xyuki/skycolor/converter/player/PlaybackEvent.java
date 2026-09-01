package com.xyuki.skycolor.converter.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One playable color layer, retaining its source metadata. */
public final class PlaybackEvent {
    public final String title;
    public final String key;
    public final int imageIndex;
    public final int layerIndex;
    public final String color;
    public final List<String> keys;
    public final Integer sourceFrameIndex;
    public final Integer sourceTime;
    public final Long timelineTimeMs;

    PlaybackEvent(
            String title,
            String key,
            int imageIndex,
            int layerIndex,
            String color,
            List<String> keys,
            Integer sourceFrameIndex,
            Integer sourceTime,
            Long timelineTimeMs
    ) {
        this.title = title;
        this.key = key;
        this.imageIndex = imageIndex;
        this.layerIndex = layerIndex;
        this.color = color;
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.sourceFrameIndex = sourceFrameIndex;
        this.sourceTime = sourceTime;
        this.timelineTimeMs = timelineTimeMs;
    }
}
