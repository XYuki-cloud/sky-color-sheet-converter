package com.xyuki.skyconverter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.junit.Test;

public class SkyConverterTest {
    @Test
    public void readsVelocityZeroNoteOffAndTempoAwareMilliseconds() {
        byte[] midi = MidiTestData.formatOne(
                MidiTestData.track(
                        MidiTestData.tempo(0, 500_000),
                        MidiTestData.tempo(480, 1_000_000)
                ),
                MidiTestData.track(
                        MidiTestData.noteOn(0, 60, 100),
                        MidiTestData.noteOffAsNoteOn(480, 60),
                        MidiTestData.noteOn(480, 62, 100),
                        MidiTestData.noteOff(240, 62)
                )
        );

        MidiFileReader.Result result = MidiFileReader.read(midi, "tempo.mid");

        assertEquals(480, result.ticksPerBeat);
        assertEquals(2, result.notes.size());
        assertEquals(0, result.notes.get(0).startTick);
        assertEquals(0, result.notes.get(0).startMs);
        assertEquals(500, result.notes.get(0).durationMs);
        assertEquals(1_500, result.notes.get(1).startMs);
        assertEquals(500, result.notes.get(1).durationMs);
    }

    @Test
    public void mapsChromaticNotesAndCompressesOnlyDisjointSequentialFrames() {
        byte[] midi = MidiTestData.formatZero(
                MidiTestData.track(
                        MidiTestData.noteOn(0, 60, 100),
                        MidiTestData.noteOff(60, 60),
                        MidiTestData.noteOn(60, 62, 100),
                        MidiTestData.noteOff(60, 62),
                        MidiTestData.noteOn(60, 64, 100),
                        MidiTestData.noteOff(60, 64),
                        MidiTestData.noteOn(60, 61, 100),
                        MidiTestData.noteOff(60, 61),
                        MidiTestData.noteOn(240, 60, 100),
                        MidiTestData.noteOff(60, 60)
                )
        );

        SkyConverter.Conversion conversion = SkyConverter.convert(
                MidiFileReader.read(midi, "chord.mid"),
                new SkyConverter.Options("C", 4, null, SkyConverter.ChromaticPolicy.DROP, "Chord")
        );

        assertEquals(4, conversion.frames.size());
        assertEquals(List.of("A1"), conversion.frames.get(0).keys);
        assertEquals(List.of("A2"), conversion.frames.get(1).keys);
        assertEquals(List.of("A3"), conversion.frames.get(2).keys);
        assertEquals(2, conversion.colorImages.size());
        assertEquals(3, conversion.colorImages.get(0).layers.size());
        assertEquals(List.of("A2"), conversion.colorImages.get(0).layers.get(1).keys);
        assertEquals(List.of("A3"), conversion.colorImages.get(0).layers.get(2).keys);
        assertTrue(conversion.warnings.stream().anyMatch(value -> value.contains("半音")));
        assertFalse(conversion.colorImages.get(0).layers.get(0).keys.isEmpty());
    }

    @Test
    public void nearestPolicyKeepsChromaticPitchInsideTheSkyRange() {
        byte[] midi = MidiTestData.formatZero(
                MidiTestData.track(
                        MidiTestData.noteOn(0, 61, 100),
                        MidiTestData.noteOff(120, 61)
                )
        );

        SkyConverter.Conversion conversion = SkyConverter.convert(
                MidiFileReader.read(midi, "nearest.mid"),
                new SkyConverter.Options("C", 4, null, SkyConverter.ChromaticPolicy.NEAREST, "Nearest")
        );

        assertEquals(1, conversion.frames.size());
        assertEquals(List.of("A1"), conversion.frames.get(0).keys);
        assertTrue(conversion.warnings.stream().anyMatch(value -> value.contains("就近")));
    }

    private static final class MidiTestData {
        private static byte[] formatZero(byte[] track) {
            return fileHeader(0, 1, 480, track);
        }

        private static byte[] formatOne(byte[]... tracks) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeAscii(output, "MThd");
            writeInt(output, 6, 4);
            writeInt(output, 1, 2);
            writeInt(output, tracks.length, 2);
            writeInt(output, 480, 2);
            for (byte[] track : tracks) {
                writeAscii(output, "MTrk");
                writeInt(output, track.length, 4);
                output.writeBytes(track);
            }
            return output.toByteArray();
        }

        private static byte[] fileHeader(int format, int trackCount, int ppq, byte[] track) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeAscii(output, "MThd");
            writeInt(output, 6, 4);
            writeInt(output, format, 2);
            writeInt(output, trackCount, 2);
            writeInt(output, ppq, 2);
            writeAscii(output, "MTrk");
            writeInt(output, track.length, 4);
            output.writeBytes(track);
            return output.toByteArray();
        }

        private static byte[] track(byte[]... events) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] event : events) {
                output.writeBytes(event);
            }
            output.write(0);
            output.write(0xFF);
            output.write(0x2F);
            output.write(0);
            return output.toByteArray();
        }

        private static byte[] tempo(int delta, int microsecondsPerBeat) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVlq(output, delta);
            output.write(0xFF);
            output.write(0x51);
            output.write(3);
            writeInt(output, microsecondsPerBeat, 3);
            return output.toByteArray();
        }

        private static byte[] noteOn(int delta, int pitch, int velocity) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVlq(output, delta);
            output.write(0x90);
            output.write(pitch);
            output.write(velocity);
            return output.toByteArray();
        }

        private static byte[] noteOff(int delta, int pitch) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVlq(output, delta);
            output.write(0x80);
            output.write(pitch);
            output.write(0);
            return output.toByteArray();
        }

        private static byte[] noteOffAsNoteOn(int delta, int pitch) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVlq(output, delta);
            output.write(0x90);
            output.write(pitch);
            output.write(0);
            return output.toByteArray();
        }

        private static void writeAscii(ByteArrayOutputStream output, String text) {
            for (int index = 0; index < text.length(); index++) {
                output.write(text.charAt(index));
            }
        }

        private static void writeInt(ByteArrayOutputStream output, int value, int bytes) {
            for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
                output.write((value >>> shift) & 0xFF);
            }
        }

        private static void writeVlq(ByteArrayOutputStream output, int value) {
            int buffer = value & 0x7F;
            while ((value >>>= 7) != 0) {
                buffer <<= 8;
                buffer |= (value & 0x7F) | 0x80;
            }
            while (true) {
                output.write(buffer & 0xFF);
                if ((buffer & 0x80) != 0) {
                    buffer >>>= 8;
                } else {
                    return;
                }
            }
        }
    }
}
