package com.xyuki.skycolor.converter.batch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xyuki.skycolor.converter.core.ColorScoreReader;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class BatchProcessorTest {
    @Test
    public void sanitizesFileAndFolderNames() {
        assertEquals("未命名歌曲", BatchProcessor.safeName("  "));
        assertEquals("song-name", BatchProcessor.safeName("song<>:\"/\\|?*name"));
        assertEquals("song", BatchProcessor.outputBaseName("song.json", 0, 1));
        assertEquals("song - 02", BatchProcessor.outputBaseName("song.txt", 1, 2));
    }

    @Test
    public void choosesCaseInsensitiveCollisionFreeFolderName() {
        Set<String> used = new HashSet<>();
        used.add("Song");
        used.add("song (2)");

        assertEquals("Song (3)", BatchProcessor.uniqueFolderName("Song", used));
        assertTrue(used.contains("Song (3)"));
    }

    @Test
    public void colorInputIsPreviewOnlyAndNotGeneratable() {
        ColorScoreReader.ColorDocument color = ColorScoreReader.read(
                ("{\"format\":\"sky-color-v1\",\"title\":\"已有彩谱\","
                        + "\"images\":[{\"layers\":[{\"color\":\"black\","
                        + "\"keys\":[\"A1\"]}]}]}")
                        .getBytes(StandardCharsets.UTF_8),
                "existing.color.json"
        );

        BatchProcessor.InputItem item = BatchProcessor.InputItem.previewOnly(
                null,
                "existing.color.json",
                color
        );

        assertTrue(item.isPreviewOnly());
        assertFalse(item.isGeneratable());
        assertEquals("已有彩谱", item.defaultTitle());
    }
}
