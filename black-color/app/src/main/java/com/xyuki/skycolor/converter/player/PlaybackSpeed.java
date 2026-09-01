package com.xyuki.skycolor.converter.player;

import java.util.Locale;

/** Converts the user-facing logarithmic speed slider to an automatic-playback multiplier. */
public final class PlaybackSpeed {
    public static final double MIN_MULTIPLIER = 0.01;
    public static final double MAX_MULTIPLIER = 1.0;
    public static final int SLIDER_MAX = 100;
    public static final double[] PRESETS = {
            1.0, 0.5, 0.25, 0.2, 0.1, 0.05, 0.02, 0.01
    };
    private static final String[] PRESET_LABELS = {
            "1×", "1/2×", "1/4×", "1/5×", "1/10×", "1/20×", "1/50×", "1/100×"
    };

    private PlaybackSpeed() {
    }

    public static double clamp(double multiplier) {
        if (Double.isNaN(multiplier)) {
            return MAX_MULTIPLIER;
        }
        if (multiplier < MIN_MULTIPLIER) {
            return MIN_MULTIPLIER;
        }
        if (multiplier > MAX_MULTIPLIER) {
            return MAX_MULTIPLIER;
        }
        return multiplier;
    }

    public static double fromSlider(int progress) {
        int bounded = Math.max(0, Math.min(SLIDER_MAX, progress));
        double denominator = Math.exp(
                bounded / 100.0 * Math.log(MAX_MULTIPLIER / MIN_MULTIPLIER)
        );
        return clamp(MAX_MULTIPLIER / denominator);
    }

    public static int toSlider(double multiplier) {
        double bounded = clamp(multiplier);
        double denominator = MAX_MULTIPLIER / bounded;
        int progress = (int) Math.round(
                Math.log(denominator) / Math.log(MAX_MULTIPLIER / MIN_MULTIPLIER) * SLIDER_MAX
        );
        return Math.max(0, Math.min(SLIDER_MAX, progress));
    }

    public static String label(double multiplier) {
        double bounded = clamp(multiplier);
        for (int index = 0; index < PRESETS.length; index++) {
            if (Math.abs(bounded - PRESETS[index]) < 0.0005
                    || toSlider(bounded) == toSlider(PRESETS[index])) {
                return PRESET_LABELS[index];
            }
        }
        return String.format(Locale.ROOT, "%.2f×", bounded);
    }
}
