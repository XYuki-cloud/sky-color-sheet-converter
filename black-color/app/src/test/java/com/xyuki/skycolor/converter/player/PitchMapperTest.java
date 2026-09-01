package com.xyuki.skycolor.converter.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class PitchMapperTest {
    @Test
    public void mapsCAndDToTheDesktopMajorScaleLayout() {
        assertArrayEquals(
                new int[]{60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83, 84},
                PitchMapper.midiPitchesForKey("C")
        );
        assertEquals(62, PitchMapper.midiPitchesForKey("D")[0]);
        assertEquals(63, PitchMapper.midiPitchesForKey("D#")[0]);
    }

    @Test
    public void transposeChangesPitchBySemitones() {
        assertEquals(
                PitchMapper.frequencyForMidi(60),
                PitchMapper.frequencyForKeyIndex("C", 0, 0),
                0.0001
        );
        assertEquals(
                PitchMapper.frequencyForMidi(64),
                PitchMapper.frequencyForKeyIndex("C", 2, 0),
                0.0001
        );
        assertEquals(
                PitchMapper.frequencyForMidi(61),
                PitchMapper.frequencyForKeyIndex("C", 0, 1),
                0.0001
        );
    }

    @Test
    public void rejectsUnsupportedTonalityOrKeyIndex() {
        assertThrows(() -> PitchMapper.midiPitchesForKey("H"));
        assertThrows(() -> PitchMapper.frequencyForKeyIndex("C", 15, 0));
    }

    private static void assertThrows(Runnable action) {
        try {
            action.run();
            fail("expected illegal argument");
        } catch (IllegalArgumentException expected) {
        }
    }
}
