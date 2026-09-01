package com.xyuki.skycolor.converter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

public class ScoreImportReaderTest {
    @Test
    public void expandsLegacySongsWithStableIndexesAndKinds() {
        String json = "{\"songs\":["
                + "{\"name\":\"第一首\",\"songNotes\":[{\"time\":0,\"key\":\"Key0\"}]},"
                + "{\"title\":\"第二首\",\"songNotes\":[{\"time\":80,\"key\":\"Key1\"}]}]}";

        List<ScoreImportReader.ImportedScore> imported = ScoreImportReader.read(
                json.getBytes(StandardCharsets.UTF_8),
                "songs.txt"
        );

        assertEquals(2, imported.size());
        assertEquals(ScoreImportReader.Kind.BLACK, imported.get(0).kind);
        assertEquals(0, imported.get(0).songIndex);
        assertEquals(2, imported.get(0).songCount);
        assertEquals("第一首", imported.get(0).blackDocument.title);
        assertEquals(ScoreImportReader.Kind.BLACK, imported.get(1).kind);
        assertEquals(1, imported.get(1).songIndex);
        assertEquals("第二首", imported.get(1).blackDocument.title);
    }

    @Test
    public void classifiesColorScoreAsPreviewOnly() {
        String json = "{\"format\":\"sky-color-v1\",\"title\":\"已有彩谱\","
                + "\"images\":[{\"layers\":[{\"color\":\"black\","
                + "\"keys\":[\"A1\"]}]}]}";

        ScoreImportReader.ImportedScore imported = ScoreImportReader.read(
                json.getBytes(StandardCharsets.UTF_8),
                "existing.color.json"
        ).get(0);

        assertEquals(ScoreImportReader.Kind.COLOR_PREVIEW, imported.kind);
        assertNotNull(imported.colorDocument);
        assertEquals("已有彩谱", imported.colorDocument.title);
        assertTrue(imported.error.trim().isEmpty());
    }

    @Test
    public void isolatesMalformedPayloadAsOneInvalidItem() {
        ScoreImportReader.ImportedScore imported = ScoreImportReader.read(
                "not-json".getBytes(StandardCharsets.UTF_8),
                "broken.txt"
        ).get(0);

        assertEquals(ScoreImportReader.Kind.INVALID, imported.kind);
        assertTrue(imported.error.contains("JSON"));
        assertEquals(null, imported.blackDocument);
        assertEquals(null, imported.colorDocument);
    }

    @Test
    public void keepsLegacyEncodingSupportWhenUsingUnifiedReader() {
        String json = "{\"songNotes\":[{\"time\":10,\"key\":\"Key14\"}]}";
        byte[] utf8Body = json.getBytes(StandardCharsets.UTF_8);
        byte[] utf8Bom = new byte[utf8Body.length + 3];
        utf8Bom[0] = (byte) 0xEF;
        utf8Bom[1] = (byte) 0xBB;
        utf8Bom[2] = (byte) 0xBF;
        System.arraycopy(utf8Body, 0, utf8Bom, 3, utf8Body.length);

        byte[] utf16Body = json.getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16 = new byte[utf16Body.length + 2];
        utf16[0] = (byte) 0xFF;
        utf16[1] = (byte) 0xFE;
        System.arraycopy(utf16Body, 0, utf16, 2, utf16Body.length);

        assertEquals("C5", ScoreImportReader.read(utf8Bom, "utf8.txt")
                .get(0).blackDocument.frames.get(0).keys.get(0));
        assertEquals("C5", ScoreImportReader.read(utf16, "utf16.txt")
                .get(0).blackDocument.frames.get(0).keys.get(0));
    }

    @Test
    public void colorPreviewIsNotAConvertibleBlackItem() {
        String json = "{\"format\":\"sky-color-v1\",\"images\":["
                + "{\"layers\":[{\"color\":\"black\",\"keys\":[\"A1\"]}]}]}";

        ScoreImportReader.ImportedScore imported = ScoreImportReader.read(
                json.getBytes(StandardCharsets.UTF_8),
                "already-color.json"
        ).get(0);

        assertEquals(ScoreImportReader.Kind.COLOR_PREVIEW, imported.kind);
        assertEquals(null, imported.blackDocument);
        assertNotNull(imported.colorDocument);
    }
}
