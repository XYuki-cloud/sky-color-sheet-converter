package com.xyuki.skycolor.converter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class ColorScoreConverterTest {
    @Test
    public void packsDisjointFramesInBlackRedBlueOrder() {
        BlackScoreReader.ScoreDocument document = read("Title", "A1", "B1", "C1", "A1");

        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(document, "自定义标题");

        assertEquals("自定义标题", conversion.title);
        assertEquals(2, conversion.images.size());
        assertEquals(3, conversion.images.get(0).layers.size());
        assertEquals(List.of("black", "red", "blue"), colors(conversion.images.get(0)));
        assertEquals(List.of("A1"), conversion.images.get(0).layers.get(0).keys);
        assertEquals(List.of("B1"), conversion.images.get(0).layers.get(1).keys);
        assertEquals(List.of("C1"), conversion.images.get(0).layers.get(2).keys);
        assertEquals(1, conversion.images.get(1).layers.size());
        assertEquals("black", conversion.images.get(1).layers.get(0).color);
        assertEquals(4, conversion.images.get(1).layers.get(0).sourceFrameIndex);
    }

    @Test
    public void overlappingFramesStartANewColorImage() {
        BlackScoreReader.ScoreDocument document = read("Title", "A1", "A1");

        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(document, "");

        assertEquals("Title", conversion.title);
        assertEquals(2, conversion.images.size());
        assertEquals(1, conversion.images.get(0).layers.size());
        assertEquals(1, conversion.images.get(1).layers.size());
        assertEquals(Integer.valueOf(100), conversion.images.get(1).layers.get(0).sourceTime);
    }

    @Test
    public void emptyTitleFallsBackToSourceNameStem() {
        BlackScoreReader.ScoreDocument document = readWithoutTitle("melody.mid", "A1");

        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(document, "  ");

        assertEquals("melody", conversion.title);
    }

    @Test
    public void generatedJsonRetainsPlayableImagesAndArtifactNames() {
        BlackScoreReader.ScoreDocument document = read("Title", "A1", "B1");
        ColorScoreConverter.Conversion conversion = ColorScoreConverter.convert(document, null);
        String json = ColorScoreWriter.toJson(
                conversion,
                List.of("Title.color-mobile-000.png", "Title.color-mobile-001.png")
        );

        Object parsed = JsonValueParser.parse(json);
        assertTrue(parsed instanceof java.util.Map);
        assertTrue(json.contains("\"format\":\"sky-color-v1\""));
        assertTrue(json.contains("\"title\":\"Title\""));
        assertTrue(json.contains("\"cover_png\":\"Title.color-mobile-000.png\""));
        assertTrue(json.contains("\"source_frame_index\":1"));
        assertTrue(json.contains("\"image_count\":1"));
    }

    private static List<String> colors(ColorScoreConverter.ColorImage image) {
        return image.layers.stream().map(layer -> layer.color).toList();
    }

    private static BlackScoreReader.ScoreDocument read(String title, String... keys) {
        StringBuilder images = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                images.append(',');
            }
            images.append("{\"index\":").append(i + 1)
                    .append(",\"layers\":[{\"index\":0,\"color\":\"black\",\"source_frame_index\":")
                    .append(i + 1).append(",\"source_time\":").append(i * 100)
                    .append(",\"keys\":[\"").append(keys[i]).append("\"]}]}");
        }
        String json = "{\"format\":\"sky-black-v1\",\"source\":{\"filename\":\"title.json\","
                + "\"name\":\"" + title + "\"},\"source_note_count\":" + keys.length
                + ",\"images\":[" + images + "]}";
        return BlackScoreReader.read(json.getBytes(StandardCharsets.UTF_8), "title.json").get(0);
    }

    private static BlackScoreReader.ScoreDocument readWithoutTitle(String filename, String key) {
        String json = "{\"format\":\"sky-black-v1\",\"source\":{\"filename\":\""
                + filename + "\"},\"images\":[{\"layers\":[{\"color\":\"black\",\"keys\":[\""
                + key + "\"]}]}]}";
        return BlackScoreReader.read(json.getBytes(StandardCharsets.UTF_8), "fallback.json").get(0);
    }
}
