package com.xyuki.skycolor.converter.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;

import java.util.List;

import org.junit.Test;

public class PlaybackSequenceTest {
    @Test
    public void flattensImagesAndLayersInBlackRedBlueOrder() {
        BlackScoreReader.ScoreDocument source = new BlackScoreReader.ScoreDocument(
                "source.json",
                "source.json",
                0,
                "试听标题",
                "",
                "",
                3,
                List.of(
                        new BlackScoreReader.SourceFrame(8, 120, List.of("A1")),
                        new BlackScoreReader.SourceFrame(9, 240, List.of("B1")),
                        new BlackScoreReader.SourceFrame(10, 360, List.of("C1"))
                ),
                List.of()
        );

        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(source, null);
        PlaybackSequence sequence = PlaybackSequence.fromConversion(conversion);

        assertEquals(3, sequence.events.size());
        assertEquals("black", sequence.events.get(0).color);
        assertEquals("red", sequence.events.get(1).color);
        assertEquals("blue", sequence.events.get(2).color);
        assertEquals(0, sequence.events.get(0).imageIndex);
        assertEquals(0, sequence.events.get(1).imageIndex);
        assertEquals(2, sequence.events.get(2).layerIndex);
        assertEquals(Integer.valueOf(8), sequence.events.get(0).sourceFrameIndex);
        assertEquals(Integer.valueOf(120), sequence.events.get(0).sourceTime);
        assertEquals("试听标题", sequence.events.get(0).title);
        assertEquals("C", sequence.events.get(0).key);
        assertEquals(Long.valueOf(0L), sequence.events.get(0).timelineTimeMs);
        assertEquals(Long.valueOf(240L), sequence.events.get(2).timelineTimeMs);
        assertTrue(sequence.hasTimedPlayback());
        assertEquals(1340L, sequence.durationMs);
    }

    @Test
    public void missingSourceTimeKeepsManualEventsButDisablesTimeline() {
        BlackScoreReader.ScoreDocument source = new BlackScoreReader.ScoreDocument(
                "source.json",
                "source.json",
                0,
                "无时间",
                "",
                "",
                2,
                List.of(
                        new BlackScoreReader.SourceFrame(1, null, List.of("A1")),
                        new BlackScoreReader.SourceFrame(2, null, List.of("B1"))
                ),
                List.of()
        );

        PlaybackSequence sequence = PlaybackSequence.fromConversion(
                ColorScoreConverter.convert(source, null)
        );

        assertEquals(2, sequence.events.size());
        assertFalse(sequence.hasTimedPlayback());
        assertTrue(sequence.timingError.contains("source_time"));
        assertEquals(null, sequence.events.get(0).timelineTimeMs);
    }
}
