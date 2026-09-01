package com.xyuki.skyconverter.core;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JsonWriterTest {
    @Test
    public void writesVersionedPayloadAndEscapesSourceMetadata() {
        byte[] midi = new byte[] {
                'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 1, (byte) 0xE0,
                'M', 'T', 'r', 'k', 0, 0, 0, 4, 0, (byte) 0xFF, 0x2F, 0
        };
        SkyConverter.Conversion conversion = SkyConverter.convert(
                MidiFileReader.read(midi, "json.mid"),
                new SkyConverter.Options("C", 4, null, SkyConverter.ChromaticPolicy.DROP, "曲\"名\\测试")
        );

        String json = JsonWriter.colorPayload(
                conversion,
                "曲\"名\\测试.mid",
                "曲\"名\\测试",
                java.util.List.of("song.color-mobile-000.png", "song.color-mobile-001.png")
        );

        assertTrue(json.startsWith("{\"format\":\"sky-color-v1\""));
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\\\"));
        assertTrue(json.contains("\"key_order\":[\"A1\""));
        assertTrue(json.contains("\"mobile_png_pages\""));
    }
}
