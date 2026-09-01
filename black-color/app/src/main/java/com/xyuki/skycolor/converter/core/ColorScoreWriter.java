package com.xyuki.skycolor.converter.core;

import java.util.Collections;
import java.util.List;

/** Writes the color-score JSON consumed by the existing Sky player. */
public final class ColorScoreWriter {
    private ColorScoreWriter() {
    }

    public static String toJson(
            ColorScoreConverter.Conversion conversion,
            List<String> mobilePages
    ) {
        return toJson(conversion, mobilePages, null);
    }

    public static String toJson(
            ColorScoreConverter.Conversion conversion,
            List<String> mobilePages,
            String jsonFileName
    ) {
        if (conversion == null) {
            throw new IllegalArgumentException("彩谱转换结果不能为空");
        }
        List<String> pages = mobilePages == null ? Collections.emptyList() : mobilePages;
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "format", "sky-color-v1");
        comma(json);
        field(json, "mode", "color");
        comma(json);
        key(json, "source");
        json.append('{');
        field(json, "filename", conversion.sourceFilename);
        comma(json);
        field(json, "name", conversion.title);
        comma(json);
        field(json, "author", conversion.author);
        comma(json);
        field(json, "transcribed_by", conversion.transcribedBy);
        json.append('}');
        comma(json);
        field(json, "title", conversion.title);
        comma(json);
        field(json, "key", "C");
        comma(json);
        key(json, "key_order");
        stringArray(json, ColorScoreConverter.keyLabels());
        comma(json);
        key(json, "colors");
        json.append('[');
        for (int index = 0; index < ColorScoreConverter.COLOR_NAMES.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('{');
            field(json, "name", ColorScoreConverter.COLOR_NAMES[index]);
            comma(json);
            field(json, "hex", ColorScoreConverter.COLOR_HEX[index]);
            json.append('}');
        }
        json.append(']');
        comma(json);
        numberField(json, "source_note_count", conversion.sourceNoteCount);
        comma(json);
        numberField(json, "source_frame_count", conversion.sourceFrameCount);
        comma(json);
        numberField(json, "image_count", conversion.images.size());
        comma(json);
        key(json, "warnings");
        stringArray(json, conversion.warnings);
        comma(json);
        key(json, "images");
        json.append('[');
        for (int imageIndex = 0; imageIndex < conversion.images.size(); imageIndex++) {
            if (imageIndex > 0) {
                json.append(',');
            }
            ColorScoreConverter.ColorImage image = conversion.images.get(imageIndex);
            json.append('{');
            numberField(json, "index", image.index);
            comma(json);
            key(json, "layers");
            json.append('[');
            for (int layerIndex = 0; layerIndex < image.layers.size(); layerIndex++) {
                if (layerIndex > 0) {
                    json.append(',');
                }
                ColorScoreConverter.ColorLayer layer = image.layers.get(layerIndex);
                json.append('{');
                numberField(json, "index", layer.index);
                comma(json);
                field(json, "color", layer.color);
                comma(json);
                field(json, "hex", layer.hex);
                comma(json);
                numberField(json, "source_frame_index", layer.sourceFrameIndex);
                comma(json);
                nullableNumberField(json, "source_time", layer.sourceTime);
                comma(json);
                key(json, "keys");
                stringArray(json, layer.keys);
                json.append('}');
            }
            json.append(']');
            json.append('}');
        }
        json.append(']');
        comma(json);
        key(json, "artifacts");
        json.append('{');
        field(json, "json", jsonFileName);
        comma(json);
        key(json, "mobile_png_pages");
        stringArray(json, pages);
        comma(json);
        field(json, "cover_png", pages.isEmpty() ? null : pages.get(0));
        json.append('}');
        json.append('}');
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        key(json, name);
        quoted(json, value);
    }

    private static void nullableNumberField(StringBuilder json, String name, Integer value) {
        key(json, name);
        if (value == null) {
            json.append("null");
        } else {
            json.append(value);
        }
    }

    private static void numberField(StringBuilder json, String name, int value) {
        key(json, name);
        json.append(value);
    }

    private static void key(StringBuilder json, String name) {
        quoted(json, name);
        json.append(':');
    }

    private static void comma(StringBuilder json) {
        json.append(',');
    }

    private static void stringArray(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            quoted(json, values.get(index));
        }
        json.append(']');
    }

    private static void quoted(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                    break;
            }
        }
        json.append('"');
    }
}
