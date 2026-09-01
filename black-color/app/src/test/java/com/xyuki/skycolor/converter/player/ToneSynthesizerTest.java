package com.xyuki.skycolor.converter.player;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ToneSynthesizerTest {
    @Test
    public void rendersAudibleChordWithoutClipping() {
        short[] pcm = ToneSynthesizer.render(
                List.of(261.625565, 329.627557, 391.995436),
                0.68f,
                80
        );

        boolean hasSignal = false;
        for (short sample : pcm) {
            if (sample != 0) {
                hasSignal = true;
            }
            assertTrue("PCM sample clipped", sample > Short.MIN_VALUE && sample < Short.MAX_VALUE);
        }
        assertTrue("rendered chord should contain audio", hasSignal);
    }

    @Test
    public void silenceAndInvalidVolumeAreSafe() {
        short[] pcm = ToneSynthesizer.render(List.of(), 0.68f, 20);
        for (short sample : pcm) {
            assertTrue(sample == 0);
        }
        short[] muted = ToneSynthesizer.render(List.of(440.0), -1f, 20);
        for (short sample : muted) {
            assertTrue(sample == 0);
        }
    }
}
