package com.xyuki.skycolor.converter.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackSpeedTest {
    @Test
    public void sliderEndpointsAreOneAndOneHundredth() {
        assertEquals(1.0, PlaybackSpeed.fromSlider(0), 0.0000001);
        assertEquals(0.01, PlaybackSpeed.fromSlider(100), 0.0000001);
        assertEquals(0, PlaybackSpeed.toSlider(1.0));
        assertEquals(100, PlaybackSpeed.toSlider(0.01));
    }

    @Test
    public void presetsAndLogarithmicMappingAreStable() {
        assertEquals(8, PlaybackSpeed.PRESETS.length);
        assertEquals(1.0, PlaybackSpeed.PRESETS[0], 0.0);
        assertEquals(0.01, PlaybackSpeed.PRESETS[7], 0.0);
        assertEquals(0.1, PlaybackSpeed.fromSlider(50), 0.0000001);
        assertEquals(50, PlaybackSpeed.toSlider(0.1));
    }

    @Test
    public void labelsUsePresetsAndReadableIntermediateValues() {
        assertEquals("1×", PlaybackSpeed.label(1.0));
        assertEquals("1/20×", PlaybackSpeed.label(0.05));
        assertEquals("1/100×", PlaybackSpeed.label(0.01));
        assertEquals("0.03×", PlaybackSpeed.label(0.034));
    }

    @Test
    public void valuesOutsideTheRangeAreClamped() {
        assertEquals(1.0, PlaybackSpeed.clamp(4.0), 0.0);
        assertEquals(0.01, PlaybackSpeed.clamp(-1.0), 0.0);
    }
}
