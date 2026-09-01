package com.xyuki.skyconverter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Serializes Android conversion results using the desktop project's JSON keys. */
public final class JsonWriter {
    private JsonWriter() {
    }

    public static final class ArtifactNames {
        public final List<String> blackPngPages;
        public final List<String> colorPngPages;
        public final List<String> colorMobilePngPages;
        public final String blackCoverPng;
        public final String colorCoverPng;
        public final String notesJson;
        public final String reportJson;
        public final String blackJson;
        public final String colorJson;

        public ArtifactNames(
                List<String> blackPngPages,
                List<String> colorPngPages,
                List<String> colorMobilePngPages,
                String blackCoverPng,
                String colorCoverPng,
                String notesJson,
                String reportJson,
                String blackJson,
                String colorJson
        ) {
            this.blackPngPages = immutable(blackPngPages);
            this.colorPngPages = immutable(colorPngPages);
            this.colorMobilePngPages = immutable(colorMobilePngPages);
            this.blackCoverPng = blackCoverPng;
            this.colorCoverPng = colorCoverPng;
            this.notesJson = notesJson;
            this.reportJson = reportJson;
            this.blackJson = blackJson;
            this.colorJson = colorJson;
        }

        public static ArtifactNames empty() {
            return new ArtifactNames(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public static String blackPayload(
            SkyConverter.Conversion conversion,
            String sourceFilename,
            String title,
            List<String> pngPages
    ) {
        StringBuilder json = basePayload("sky-black-v1", "black-white", conversion, sourceFilename, title);
        appendKey(json, "colors");
        json.append("[{");
        appendStringField(json, "name", "black");
        json.append(',');
        appendStringField(json, "hex", "#000000");
        json.append("}],");
        appendCommonCounts(json, conversion, false);
        appendImages(json, conversion.blackImages);
        json.append(',');
        appendArtifacts(json, "png_pages", pngPages, null, null);
        json.append('}');
        return json.toString();
    }

    public static String colorPayload(
            SkyConverter.Conversion conversion,
            String sourceFilename,
            String title,
            List<String> mobilePngPages
    ) {
        StringBuilder json = basePayload("sky-color-v1", "color", conversion, sourceFilename, title);
        appendKey(json, "colors");
        json.append('[');
        for (int index = 0; index < SkyConverter.COLOR_NAMES.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('{');
            appendStringField(json, "name", SkyConverter.COLOR_NAMES[index]);
            json.append(',');
            appendStringField(json, "hex", SkyConverter.COLOR_HEX[index]);
            json.append('}');
        }
        json.append("],");
        appendCommonCounts(json, conversion, true);
        appendImages(json, conversion.colorImages);
        json.append(',');
        appendArtifacts(json, "png_pages", Collections.emptyList(), "mobile_png_pages", mobilePngPages);
        json.append('}');
        return json.toString();
    }

    public static String notesPayload(
            MidiFileReader.Result result,
            String sourceFilename,
            String title
    ) {
        StringBuilder json = new StringBuilder("{");
        appendStringField(json, "format", "sky-note-events-v1");
        json.append(',');
        appendKey(json, "source");
        json.append('{');
        appendStringField(json, "filename", sourceFilename);
        json.append(',');
        appendStringField(json, "name", title);
        json.append("},");
        appendKey(json, "midi");
        appendQuoted(json, sourceFilename);
        json.append(',');
        appendKey(json, "events");
        json.append('[');
        for (int index = 0; index < result.notes.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            MidiFileReader.Note note = result.notes.get(index);
            json.append('{');
            appendNumberField(json, "start_ms", note.startMs);
            json.append(',');
            appendNumberField(json, "duration_ms", note.durationMs);
            json.append(',');
            appendNumberField(json, "pitch", note.pitch);
            json.append(',');
            appendNumberField(json, "velocity", note.velocity);
            json.append(',');
            appendKey(json, "source");
            appendQuoted(json, "midi");
            json.append('}');
        }
        json.append("],");
        appendStringArray(json, "warnings", result.warnings);
        json.append('}');
        return json.toString();
    }

    public static String reportPayload(
            SkyConverter.Conversion conversion,
            String sourceFilename,
            ArtifactNames artifacts
    ) {
        StringBuilder json = new StringBuilder("{");
        appendNumberField(json, "ticks_per_beat", conversion.ticksPerBeat);
        json.append(',');
        appendNumberField(json, "subdivisions_per_beat", conversion.subdivisions);
        json.append(',');
        appendStringField(json, "key", conversion.key);
        json.append(',');
        appendNumberField(json, "scale_shift", conversion.scaleShift);
        json.append(',');
        appendStringField(json, "chromatic_policy", conversion.chromaticPolicy);
        json.append(',');
        appendNumberField(json, "input_note_count", conversion.inputNoteCount);
        json.append(',');
        appendNumberField(json, "mapped_note_count", conversion.mappedNoteCount);
        json.append(',');
        appendIntegerArray(json, "unsupported_pitches", conversion.unsupportedPitches);
        json.append(',');
        appendKey(json, "nearest_adjustments");
        json.append('[');
        for (int index = 0; index < conversion.nearestAdjustments.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            SkyConverter.NearestAdjustment adjustment = conversion.nearestAdjustments.get(index);
            json.append('{');
            appendNumberField(json, "from_pitch", adjustment.fromPitch);
            json.append(',');
            appendNumberField(json, "to_pitch", adjustment.toPitch);
            json.append(',');
            appendNumberField(json, "source_time", adjustment.sourceTimeMs);
            json.append('}');
        }
        json.append("],");
        appendIntegerArray(json, "out_of_range_pitches", conversion.outOfRangePitches);
        json.append(',');
        appendNumberField(json, "out_of_range_note_count", conversion.outOfRangeNoteCount);
        json.append(',');
        appendNumberField(json, "source_frame_count", conversion.frames.size());
        json.append(',');
        appendStringArray(json, "warnings", conversion.warnings);
        json.append(',');
        appendStringField(json, "source", sourceFilename);
        json.append(',');
        appendStringField(json, "title", conversion.title);
        json.append(',');
        appendNumberField(json, "black_image_count", conversion.blackImages.size());
        json.append(',');
        appendNumberField(json, "color_image_count", conversion.colorImages.size());
        json.append(',');
        appendKey(json, "artifacts");
        json.append('{');
        appendStringField(json, "midi", sourceFilename);
        json.append(',');
        appendStringField(json, "notes", artifacts.notesJson);
        json.append(',');
        appendStringField(json, "black_json", artifacts.blackJson);
        json.append(',');
        appendStringArray(json, "black_png_pages", artifacts.blackPngPages);
        json.append(',');
        appendStringField(json, "black_cover_png", artifacts.blackCoverPng);
        json.append(',');
        appendStringField(json, "color_json", artifacts.colorJson);
        json.append(',');
        appendStringArray(json, "color_png_pages", artifacts.colorPngPages);
        json.append(',');
        appendStringArray(json, "color_mobile_png_pages", artifacts.colorMobilePngPages);
        json.append(',');
        appendStringField(json, "color_cover_png", artifacts.colorCoverPng);
        json.append(',');
        appendStringField(json, "color_mobile_cover_png", artifacts.colorCoverPng);
        json.append('}');
        json.append('}');
        return json.toString();
    }

    private static StringBuilder basePayload(
            String format,
            String mode,
            SkyConverter.Conversion conversion,
            String sourceFilename,
            String title
    ) {
        StringBuilder json = new StringBuilder("{");
        appendStringField(json, "format", format);
        json.append(',');
        appendStringField(json, "mode", mode);
        json.append(',');
        appendKey(json, "source");
        json.append('{');
        appendStringField(json, "filename", sourceFilename);
        json.append(',');
        appendStringField(json, "name", title);
        json.append(',');
        appendStringField(json, "author", "");
        json.append(',');
        appendStringField(json, "transcribed_by", "android-midi-to-sky");
        json.append("},");
        appendStringArray(json, "key_order", List.of(SkyConverter.KEY_LABELS));
        json.append(',');
        appendStringField(json, "key", conversion.key);
        json.append(',');
        appendStringArray(json, "warnings", conversion.warnings);
        json.append(',');
        return json;
    }

    private static void appendCommonCounts(
            StringBuilder json,
            SkyConverter.Conversion conversion,
            boolean color
    ) {
        appendNumberField(json, "source_note_count", conversion.inputNoteCount);
        json.append(',');
        appendNumberField(json, "source_frame_count", conversion.frames.size());
        json.append(',');
        appendNumberField(json, "image_count", color ? conversion.colorImages.size() : conversion.blackImages.size());
    }

    private static void appendImages(StringBuilder json, List<SkyConverter.ColorImage> images) {
        json.append(',');
        appendKey(json, "images");
        json.append('[');
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
            if (imageIndex > 0) {
                json.append(',');
            }
            SkyConverter.ColorImage image = images.get(imageIndex);
            json.append('{');
            appendNumberField(json, "index", image.index);
            json.append(',');
            appendKey(json, "layers");
            json.append('[');
            for (int layerIndex = 0; layerIndex < image.layers.size(); layerIndex++) {
                if (layerIndex > 0) {
                    json.append(',');
                }
                SkyConverter.ColorLayer layer = image.layers.get(layerIndex);
                json.append('{');
                appendNumberField(json, "index", layer.index);
                json.append(',');
                appendStringField(json, "color", layer.color);
                json.append(',');
                appendStringField(json, "hex", layer.hex);
                json.append(',');
                appendNumberField(json, "source_frame_index", layer.sourceFrameIndex);
                json.append(',');
                appendNumberField(json, "source_time", layer.sourceTimeMs);
                json.append(',');
                appendStringArray(json, "keys", layer.keys);
                json.append('}');
            }
            json.append("]}");
        }
        json.append(']');
    }

    private static void appendArtifacts(
            StringBuilder json,
            String firstKey,
            List<String> firstValues,
            String secondKey,
            List<String> secondValues
    ) {
        appendKey(json, "artifacts");
        json.append('{');
        appendStringArray(json, firstKey, firstValues);
        if (secondKey != null) {
            json.append(',');
            appendStringArray(json, secondKey, secondValues);
            json.append(',');
            appendStringField(json, "cover_png", secondValues.isEmpty() ? null : secondValues.get(0));
        }
        json.append('}');
    }

    private static void appendStringField(StringBuilder json, String key, String value) {
        appendKey(json, key);
        appendQuoted(json, value);
    }

    private static void appendNumberField(StringBuilder json, String key, long value) {
        appendKey(json, key);
        json.append(value);
    }

    private static void appendKey(StringBuilder json, String key) {
        appendQuoted(json, key);
        json.append(':');
    }

    private static void appendStringArray(StringBuilder json, String key, List<String> values) {
        appendKey(json, key);
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendQuoted(json, values.get(index));
        }
        json.append(']');
    }

    private static void appendIntegerArray(StringBuilder json, String key, List<Integer> values) {
        appendKey(json, key);
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(values.get(index));
        }
        json.append(']');
    }

    private static void appendQuoted(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
