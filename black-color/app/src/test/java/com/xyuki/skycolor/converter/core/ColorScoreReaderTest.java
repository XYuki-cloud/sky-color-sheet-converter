package com.xyuki.skycolor.converter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class ColorScoreReaderTest {
    @Test
    public void readsColorScoreAndPreservesPlaybackMetadata() {
        String json = "{"
                + "\"format\":\"sky-color-v1\","
                + "\"title\":\"彩色标题\",\"key\":\"D\","
                + "\"source\":{\"filename\":\"song.color.json\",\"author\":\"作者\"},"
                + "\"images\":["
                + "{\"index\":4,\"layers\":["
                + "{\"index\":0,\"color\":\"black\",\"source_frame_index\":9,"
                + "\"source_time\":120,\"keys\":[\"A1\",\"C5\"]},"
                + "{\"index\":1,\"color\":\"red\",\"source_frame_index\":10,"
                + "\"source_time\":240,\"keys\":[\"B2\"]}"
                + "]}]}";

        ColorScoreReader.ColorDocument document = ColorScoreReader.read(
                json.getBytes(StandardCharsets.UTF_8),
                "fallback.color.json"
        );

        assertEquals("彩色标题", document.title);
        assertEquals("D", document.key);
        assertEquals("作者", document.author);
        assertEquals(1, document.images.size());
        assertEquals(2, document.images.get(0).layers.size());
        ColorScoreReader.ColorLayer layer = document.images.get(0).layers.get(1);
        assertEquals("red", layer.color);
        assertEquals(Integer.valueOf(10), layer.sourceFrameIndex);
        assertEquals(Integer.valueOf(240), layer.sourceTime);
        assertEquals(List.of("B2"), layer.keys);
    }

    @Test
    public void readIfColorReturnsNullForBlackInput() {
        String json = "{\"format\":\"sky-black-v1\",\"images\":[]}";

        assertNull(ColorScoreReader.readIfColor(
                json.getBytes(StandardCharsets.UTF_8),
                "black.json"
        ));
    }

    @Test
    public void acceptsUtf16ColorScoreAndImageTimeFallback() {
        String json = "{\"format\":\"sky-color-v1\",\"images\":["
                + "{\"source_time\":77,\"layers\":["
                + "{\"color\":\"black\",\"keys\":[\"C3\"]}]}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);

        ColorScoreReader.ColorDocument document = ColorScoreReader.read(bytes, "color.txt");

        assertEquals(Integer.valueOf(77), document.images.get(0).layers.get(0).sourceTime);
    }

    @Test
    public void acceptsUtf8BomAndCamelCaseSourceFields() {
        String json = "{\"format\":\"sky-color-v1\",\"key\":\"F#\",\"images\":["
                + "{\"layers\":[{\"color\":\"black\",\"sourceFrameIndex\":12,"
                + "\"sourceTime\":345,\"keys\":[\"A2\"]}]}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        ColorScoreReader.ColorDocument document = ColorScoreReader.read(bytes, "color.json");

        ColorScoreReader.ColorLayer layer = document.images.get(0).layers.get(0);
        assertEquals("F#", document.key);
        assertEquals(Integer.valueOf(12), layer.sourceFrameIndex);
        assertEquals(Integer.valueOf(345), layer.sourceTime);
    }

    @Test
    public void rejectsInvalidColorOrderOverlapAndKey() {
        assertThrows("颜色顺序", () -> ColorScoreReader.read(
                ("{\"format\":\"sky-color-v1\",\"images\":[{\"layers\":["
                        + "{\"color\":\"red\",\"keys\":[\"A1\"]}]}]}")
                        .getBytes(StandardCharsets.UTF_8),
                "bad-order.json"
        ));
        assertThrows("重叠", () -> ColorScoreReader.read(
                ("{\"format\":\"sky-color-v1\",\"images\":[{\"layers\":["
                        + "{\"color\":\"black\",\"keys\":[\"A1\"]},"
                        + "{\"color\":\"red\",\"keys\":[\"A1\"]}]}]}")
                        .getBytes(StandardCharsets.UTF_8),
                "overlap.json"
        ));
        assertThrows("非法按键", () -> ColorScoreReader.read(
                ("{\"format\":\"sky-color-v1\",\"images\":[{\"layers\":["
                        + "{\"color\":\"black\",\"keys\":[\"Key15\"]}]}]}")
                        .getBytes(StandardCharsets.UTF_8),
                "bad-key.json"
        ));
    }

    @Test
    public void rejectsNonColorPayloadWhenReadRequiresColor() {
        try {
            ColorScoreReader.read(
                    "{\"songNotes\":[]}".getBytes(StandardCharsets.UTF_8),
                    "raw.txt"
            );
            fail("expected a color format error");
        } catch (BlackScoreReader.ScoreFormatException exception) {
            assertTrue(exception.getMessage().contains("sky-color-v1"));
        }
    }

    private static void assertThrows(String messagePart, Runnable action) {
        try {
            action.run();
            fail("expected exception containing: " + messagePart);
        } catch (BlackScoreReader.ScoreFormatException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(messagePart));
        }
    }
}
