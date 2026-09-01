package com.xyuki.skycolor.converter.player;

import java.util.List;

/** Dependency-free PCM tone renderer shared by tests and the Android AudioTrack sink. */
public final class ToneSynthesizer {
    public static final int SAMPLE_RATE = 44100;
    public static final int NOTE_DURATION_MS = 1100;

    private ToneSynthesizer() {
    }

    public static short[] render(List<Double> frequencies, float volume, int durationMs) {
        if (durationMs <= 0) {
            return new short[0];
        }
        int frameCount = Math.max(0, Math.round(durationMs * SAMPLE_RATE / 1000f));
        short[] result = new short[frameCount];
        if (frequencies == null || frequencies.isEmpty() || volume <= 0f) {
            return result;
        }
        float safeVolume = Math.min(1f, volume);
        for (int frame = 0; frame < frameCount; frame++) {
            double mixed = 0.0;
            for (Double frequency : frequencies) {
                if (frequency != null && Double.isFinite(frequency) && frequency > 0.0) {
                    mixed += sample(frequency, frame, safeVolume);
                }
            }
            result[frame] = toPcm(mixed);
        }
        return result;
    }

    public static double sample(double frequency, long frame, float volume) {
        if (!Double.isFinite(frequency) || frequency <= 0.0 || frame < 0 || volume <= 0f) {
            return 0.0;
        }
        double elapsedSeconds = frame / (double) SAMPLE_RATE;
        if (elapsedSeconds >= NOTE_DURATION_MS / 1000.0) {
            return 0.0;
        }
        double attack = Math.min(1.0, elapsedSeconds / 0.012);
        double decay = Math.exp(-elapsedSeconds / 0.48);
        double phase = (frequency * elapsedSeconds) % 1.0;
        double triangle = 1.0 - 4.0 * Math.abs(Math.round(phase) - phase);
        double overtone = Math.sin(2.0 * Math.PI * frequency * 2.0 * elapsedSeconds);
        double envelope = Math.min(1.0, volume) * attack * decay * 0.20;
        return envelope * (triangle * 0.84 + overtone * 0.16);
    }

    public static short toPcm(double value) {
        double clipped = Math.max(-1.0, Math.min(1.0, value));
        return (short) Math.round(clipped * 32767.0);
    }
}
