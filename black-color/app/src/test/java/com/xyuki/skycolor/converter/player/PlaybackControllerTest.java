package com.xyuki.skycolor.converter.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xyuki.skycolor.converter.core.BlackScoreReader;
import com.xyuki.skycolor.converter.core.ColorScoreConverter;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class PlaybackControllerTest {
    @Test
    public void timedPlaybackEmitsEventsInOrderAndCompletesAfterTail() {
        PlaybackSequence sequence = timedSequence();
        RecordingSink sink = new RecordingSink();
        List<PlaybackEvent> emitted = new ArrayList<>();
        PlaybackController controller = new PlaybackController(
                sequence,
                sink,
                new PlaybackController.Listener() {
                    @Override
                    public void onEvent(PlaybackEvent event) {
                        emitted.add(event);
                    }
                }
        );

        assertTrue(controller.start(1000L));
        controller.tick(1000L);
        controller.tick(1119L);
        controller.tick(1120L);
        controller.tick(1240L);
        controller.tick(2340L);

        assertEquals(List.of("A1", "B1", "C1"), sink.playedKeys);
        assertEquals(List.of("black", "red", "blue"),
                emitted.stream().map(event -> event.color).toList());
        assertEquals(PlaybackController.State.COMPLETED, controller.state());
        assertEquals(1340L, controller.positionMs());
    }

    @Test
    public void pauseAndResumeDoesNotReplayAlreadyEmittedEvent() {
        PlaybackController controller = new PlaybackController(
                timedSequence(),
                new RecordingSink(),
                null
        );

        assertTrue(controller.start(0L));
        controller.tick(0L);
        controller.pause(50L);
        assertEquals(PlaybackController.State.PAUSED, controller.state());
        assertTrue(controller.start(500L));
        controller.tick(619L);
        controller.tick(690L);

        assertEquals(2, controller.currentEventIndex());
    }

    @Test
    public void missingTimeDisablesTimedPlaybackButAllowsManualStep() {
        PlaybackSequence sequence = PlaybackSequence.fromConversion(
                ColorScoreConverter.convert(
                        new BlackScoreReader.ScoreDocument(
                                "raw.json", "raw.json", 0, "手动", "", "", 2,
                                List.of(
                                        new BlackScoreReader.SourceFrame(1, null, List.of("A1")),
                                        new BlackScoreReader.SourceFrame(2, null, List.of("B1"))
                                ),
                                List.of()
                        ),
                        null
                )
        );
        RecordingSink sink = new RecordingSink();
        List<String> errors = new ArrayList<>();
        PlaybackController controller = new PlaybackController(
                sequence,
                sink,
                new PlaybackController.Listener() {
                    @Override
                    public void onError(String message) {
                        errors.add(message);
                    }
                }
        );

        assertFalse(controller.start(0L));
        assertTrue(controller.stepNext());

        assertEquals(List.of("A1"), sink.playedKeys);
        assertTrue(errors.get(0).contains("source_time"));
    }

    @Test
    public void previousLayerAlsoPlaysAndStaysOnFirstLayerAtBeginning() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(
                timedSequence(),
                sink,
                null
        );

        assertTrue(controller.stepPrevious());
        assertEquals(0, controller.currentEventIndex());
        assertEquals(List.of("A1"), sink.playedKeys);

        assertTrue(controller.stepNext());
        assertTrue(controller.stepPrevious());
        assertEquals(0, controller.currentEventIndex());
        assertEquals(List.of("A1", "B1", "A1"), sink.playedKeys);
    }

    @Test
    public void stopResetsAndNextStartReplaysFromFirstLayer() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(
                timedSequence(),
                sink,
                null
        );

        controller.setSpeed(0.25, 0L);
        assertTrue(controller.start(0L));
        controller.stop();
        assertEquals(PlaybackController.State.STOPPED, controller.state());
        assertEquals(-1, controller.currentEventIndex());
        assertEquals(0L, controller.positionMs());
        assertEquals(0.25, controller.speedMultiplier(), 0.0);

        assertTrue(controller.start(500L));
        assertEquals(List.of("A1", "A1"), sink.playedKeys);
    }

    @Test
    public void seekSilentlyPositionsToNextTimedLayer() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(
                timedSequence(),
                sink,
                null
        );

        assertTrue(controller.seekTo(240L));
        assertEquals(PlaybackController.State.PAUSED, controller.state());
        assertEquals(240L, controller.positionMs());
        assertEquals(1, controller.currentEventIndex());
        assertTrue(controller.start(1000L));
        assertEquals(List.of("C1"), sink.playedKeys);
    }

    @Test
    public void halfSpeedTriggersTheNextLayerAfterTwiceTheWallTime() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(timedSequence(), sink, null);

        controller.setSpeed(0.5, 0L);
        assertTrue(controller.start(0L));
        controller.tick(238L);
        assertEquals(List.of("A1"), sink.playedKeys);
        controller.tick(240L);

        assertEquals(List.of("A1", "B1"), sink.playedKeys);
        assertEquals(120L, controller.positionMs());
    }

    @Test
    public void oneHundredthSpeedUsesTheFullWallClockStretch() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(timedSequence(), sink, null);

        controller.setSpeed(0.01, 0L);
        assertTrue(controller.start(0L));
        controller.tick(11949L);
        assertEquals(List.of("A1"), sink.playedKeys);
        controller.tick(12000L);

        assertEquals(List.of("A1", "B1"), sink.playedKeys);
        assertEquals(120L, controller.positionMs());
    }

    @Test
    public void changingSpeedWhilePlayingPreservesLogicalPosition() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(timedSequence(), sink, null);

        assertTrue(controller.start(0L));
        controller.tick(60L);
        controller.setSpeed(0.5, 60L);
        assertEquals(60L, controller.positionMs());
        assertEquals(List.of("A1"), sink.playedKeys);

        controller.tick(178L);
        assertEquals(119L, controller.positionMs());
        assertEquals(List.of("A1"), sink.playedKeys);
        controller.tick(180L);
        assertEquals(List.of("A1", "B1"), sink.playedKeys);
    }

    @Test
    public void pauseAndResumeKeepsTheSelectedSpeed() {
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(timedSequence(), sink, null);

        controller.setSpeed(0.5, 0L);
        assertTrue(controller.start(0L));
        controller.tick(120L);
        controller.pause(120L);
        assertEquals(60L, controller.positionMs());

        assertTrue(controller.start(1000L));
        controller.tick(1118L);
        assertEquals(119L, controller.positionMs());
        assertEquals(List.of("A1"), sink.playedKeys);
        controller.tick(1120L);
        assertEquals(List.of("A1", "B1"), sink.playedKeys);
    }

    @Test
    public void manualLayerAuditionDoesNotWaitForSelectedSpeed() {
        PlaybackSequence sequence = PlaybackSequence.fromConversion(
                ColorScoreConverter.convert(
                        new BlackScoreReader.ScoreDocument(
                                "manual.json", "manual.json", 0, "手动倍速", "", "", 2,
                                List.of(
                                        new BlackScoreReader.SourceFrame(1, null, List.of("A1")),
                                        new BlackScoreReader.SourceFrame(2, null, List.of("B1"))
                                ),
                                List.of()
                        ),
                        null
                )
        );
        RecordingSink sink = new RecordingSink();
        PlaybackController controller = new PlaybackController(sequence, sink, null);

        controller.setSpeed(0.01, 0L);
        assertTrue(controller.stepNext());
        assertTrue(controller.stepNext());
        assertEquals(List.of("A1", "B1"), sink.playedKeys);
    }

    private static PlaybackSequence timedSequence() {
        BlackScoreReader.ScoreDocument source = new BlackScoreReader.ScoreDocument(
                "source.json", "source.json", 0, "控制器测试", "", "", 3,
                List.of(
                        new BlackScoreReader.SourceFrame(1, 120, List.of("A1")),
                        new BlackScoreReader.SourceFrame(2, 240, List.of("B1")),
                        new BlackScoreReader.SourceFrame(3, 360, List.of("C1"))
                ),
                List.of()
        );
        return PlaybackSequence.fromConversion(ColorScoreConverter.convert(source, null));
    }

    private static final class RecordingSink implements PlaybackController.AudioSink {
        final List<String> playedKeys = new ArrayList<>();

        @Override
        public void play(List<String> keys, String key, float volume, int transpose) {
            playedKeys.add(String.join(" ", keys));
        }

        @Override
        public void stop() {
        }
    }
}
