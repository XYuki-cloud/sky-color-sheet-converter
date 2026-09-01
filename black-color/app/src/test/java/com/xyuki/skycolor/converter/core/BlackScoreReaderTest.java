package com.xyuki.skycolor.converter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class BlackScoreReaderTest {
    @Test
    public void readsVersionedBlackScoreAndPreservesFrameMetadata() {
        String json = "{"
                + "\"format\":\"sky-black-v1\","
                + "\"mode\":\"black-white\","
                + "\"source\":{\"filename\":\"source.mid\",\"name\":\"黑白标题\","
                + "\"author\":\"作者\",\"transcribed_by\":\"工具\"},"
                + "\"source_note_count\":3,"
                + "\"images\":["
                + "{\"index\":1,\"layers\":[{\"index\":0,\"color\":\"black\","
                + "\"hex\":\"#000000\",\"source_frame_index\":7,\"source_time\":120,"
                + "\"keys\":[\"A1\",\"C5\"]}]},"
                + "{\"index\":2,\"layers\":[{\"index\":0,\"color\":\"black\","
                + "\"hex\":\"#000000\",\"source_frame_index\":8,\"source_time\":240,"
                + "\"keys\":[\"B2\"]}]}"
                + "]}\n";

        List<BlackScoreReader.ScoreDocument> documents = BlackScoreReader.read(
                json.getBytes(StandardCharsets.UTF_8),
                "fallback.json"
        );

        assertEquals(1, documents.size());
        BlackScoreReader.ScoreDocument document = documents.get(0);
        assertEquals("黑白标题", document.title);
        assertEquals("作者", document.author);
        assertEquals("工具", document.transcribedBy);
        assertEquals(3, document.noteCount);
        assertEquals(2, document.frames.size());
        assertEquals(7, document.frames.get(0).index);
        assertEquals(Integer.valueOf(120), document.frames.get(0).sourceTime);
        assertEquals(List.of("A1", "C5"), document.frames.get(0).keys);
    }

    @Test
    public void readsAllLegacySongsFromUtf16AndDeduplicatesSameTimeKey() {
        String json = "{\"songs\":["
                + "{\"name\":\"第一首\",\"author\":\"A\",\"songNotes\":["
                + "{\"time\":0,\"key\":\"Key0\"},"
                + "{\"time\":0,\"key\":\"0Key0\"},"
                + "{\"time\":120,\"key\":\"14Key14\"}]},"
                + "{\"title\":\"第二首\",\"songNotes\":[{\"time\":50,\"key\":\"Key1\"}]}"
                + "]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);

        List<BlackScoreReader.ScoreDocument> documents = BlackScoreReader.read(bytes, "songs.txt");

        assertEquals(2, documents.size());
        assertEquals("第一首", documents.get(0).title);
        assertEquals("第二首", documents.get(1).title);
        assertEquals(2, documents.get(0).frames.size());
        assertEquals(List.of("A1"), documents.get(0).frames.get(0).keys);
        assertEquals(List.of("C5"), documents.get(0).frames.get(1).keys);
        assertTrue(documents.get(0).warnings.stream().anyMatch(value -> value.contains("重复")));
    }

    @Test
    public void acceptsUtf8BomAndNumericKeyPrefix() {
        String json = "{\"songNotes\":[{\"time\":0,\"key\":\"12Key12\"}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        BlackScoreReader.ScoreDocument document = BlackScoreReader.read(bytes, "untitled.txt").get(0);

        assertEquals("untitled", document.title);
        assertEquals(List.of("C3"), document.frames.get(0).keys);
    }

    @Test
    public void acceptsUtf16WithoutBom() {
        String json = "{\"songNotes\":[{\"time\":10,\"key\":\"Key14\"}]}";
        BlackScoreReader.ScoreDocument document = BlackScoreReader.read(
                json.getBytes(StandardCharsets.UTF_16LE),
                "utf16-no-bom.txt"
        ).get(0);

        assertEquals(List.of("C5"), document.frames.get(0).keys);
    }

    @Test
    public void rejectsColorScoreAndMalformedInput() {
        assertThrows("sky-color-v1", () -> BlackScoreReader.read(
                "{\"format\":\"sky-color-v1\",\"images\":[]}".getBytes(StandardCharsets.UTF_8),
                "color.json"
        ));
        assertThrows("images", () -> BlackScoreReader.read(
                "{\"format\":\"sky-black-v1\"}".getBytes(StandardCharsets.UTF_8),
                "missing.json"
        ));
        assertThrows("非法按键", () -> BlackScoreReader.read(
                "{\"songNotes\":[{\"time\":0,\"key\":\"Key15\"}]}".getBytes(StandardCharsets.UTF_8),
                "bad.txt"
        ));
        assertThrows("JSON", () -> BlackScoreReader.read(
                "not-json".getBytes(StandardCharsets.UTF_8),
                "broken.txt"
        ));
    }

    @Test
    public void rejectsInvalidTimeSongNotesAndNonBlackLayers() {
        assertThrows("time", () -> BlackScoreReader.read(
                "{\"songNotes\":[{\"time\":-1,\"key\":\"Key0\"}]}".getBytes(StandardCharsets.UTF_8),
                "negative.txt"
        ));
        assertThrows("time", () -> BlackScoreReader.read(
                "{\"songNotes\":[{\"time\":1.5,\"key\":\"Key0\"}]}".getBytes(StandardCharsets.UTF_8),
                "decimal.txt"
        ));
        assertThrows("songNotes", () -> BlackScoreReader.read(
                "{\"songNotes\":{}}".getBytes(StandardCharsets.UTF_8),
                "wrong-notes.txt"
        ));
        assertThrows("非黑层", () -> BlackScoreReader.read(
                ("{\"format\":\"sky-black-v1\",\"images\":[{\"layers\":["
                        + "{\"color\":\"red\",\"keys\":[\"A1\"]}]}]}"
                        ).getBytes(StandardCharsets.UTF_8),
                "red.json"
        ));
        assertThrows("多层", () -> BlackScoreReader.read(
                ("{\"format\":\"sky-black-v1\",\"images\":[{\"layers\":["
                        + "{\"color\":\"black\",\"keys\":[\"A1\"]},"
                        + "{\"color\":\"black\",\"keys\":[\"B1\"]}]}]}"
                        ).getBytes(StandardCharsets.UTF_8),
                "layers.json"
        ));
    }

    private static void assertThrows(String expectedMessage, Runnable action) {
        try {
            action.run();
            fail("Expected an exception containing: " + expectedMessage);
        } catch (BlackScoreReader.ScoreFormatException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(expectedMessage));
        }
    }
}
