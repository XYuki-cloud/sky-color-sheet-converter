package com.xyuki.skycolor.converter.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import com.xyuki.skycolor.converter.core.BlackScoreReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Low-dependency AudioTrack mixer for short Sky-style synthesized notes. */
public final class SynthAudioEngine implements PlaybackController.AudioSink {
    private static final int BLOCK_FRAMES = 512;

    private final Context context;
    private final Object lock = new Object();
    private final ArrayList<Voice> voices = new ArrayList<>();
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stop();
        }
    };
    private AudioTrack audioTrack;
    private Thread renderThread;
    private AudioFocusRequest audioFocusRequest;
    private boolean focusHeld;
    private volatile boolean running;

    public SynthAudioEngine(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("音频上下文不能为空");
        }
        this.context = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void play(List<String> keys, String key, float volume, int transpose) {
        if (keys == null || keys.isEmpty() || volume <= 0f) {
            return;
        }
        ensureOutput();
        ArrayList<Voice> newVoices = new ArrayList<>();
        for (String label : keys) {
            int keyIndex = keyIndex(label);
            if (keyIndex < 0) {
                continue;
            }
            newVoices.add(new Voice(
                    PitchMapper.frequencyForKeyIndex(key, keyIndex, transpose),
                    Math.min(1f, volume)
            ));
        }
        if (newVoices.isEmpty()) {
            return;
        }
        synchronized (lock) {
            voices.addAll(newVoices);
            lock.notifyAll();
        }
        try {
            audioTrack.play();
        } catch (IllegalStateException exception) {
            stop();
            throw new IllegalStateException("无法开始音频播放", exception);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            voices.clear();
            lock.notifyAll();
        }
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause();
                }
                track.flush();
            } catch (IllegalStateException ignored) {
                // The release path may race with a focus-loss callback.
            }
        }
    }

    @Override
    public void release() {
        running = false;
        synchronized (lock) {
            voices.clear();
            lock.notifyAll();
        }
        Thread thread = renderThread;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(400L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        renderThread = null;
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
        abandonAudioFocus();
    }

    private void ensureOutput() {
        if (audioTrack != null) {
            requestAudioFocus();
            return;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(ToneSynthesizer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minimumBytes = AudioTrack.getMinBufferSize(
                ToneSynthesizer.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minimumBytes <= 0) {
            minimumBytes = ToneSynthesizer.SAMPLE_RATE * 2 / 4;
        }
        int bufferBytes = Math.max(minimumBytes, BLOCK_FRAMES * 2 * 4);
        AudioTrack track = new AudioTrack(
                attributes,
                format,
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("设备不支持 PCM 音频输出");
        }
        audioTrack = track;
        running = true;
        renderThread = new Thread(this::renderLoop, "sky-color-audio");
        renderThread.start();
        requestAudioFocus();
    }

    private void requestAudioFocus() {
        if (focusHeld || audioManager == null) {
            return;
        }
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                audioFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
            }
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            );
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw new IllegalStateException("无法获得音频焦点");
        }
        focusHeld = true;
    }

    private void abandonAudioFocus() {
        if (!focusHeld || audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
        focusHeld = false;
    }

    private void renderLoop() {
        short[] buffer = new short[BLOCK_FRAMES];
        while (running) {
            synchronized (lock) {
                while (running && voices.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException exception) {
                        if (!running) {
                            return;
                        }
                    }
                }
            }
            if (!running) {
                return;
            }
            Arrays.fill(buffer, (short) 0);
            synchronized (lock) {
                for (int frame = 0; frame < buffer.length; frame++) {
                    double mixed = 0.0;
                    for (int index = voices.size() - 1; index >= 0; index--) {
                        Voice voice = voices.get(index);
                        mixed += ToneSynthesizer.sample(
                                voice.frequency,
                                voice.frame,
                                voice.volume
                        );
                        voice.frame++;
                        if (voice.frame >= ToneSynthesizer.NOTE_DURATION_MS
                                * ToneSynthesizer.SAMPLE_RATE / 1000L) {
                            voices.remove(index);
                        }
                    }
                    buffer[frame] = ToneSynthesizer.toPcm(mixed);
                }
            }
            AudioTrack track = audioTrack;
            if (track == null) {
                return;
            }
            try {
                int written = track.write(buffer, 0, buffer.length);
                if (written < 0 && running) {
                    return;
                }
            } catch (IllegalStateException exception) {
                if (running) {
                    return;
                }
            }
        }
    }

    private static int keyIndex(String label) {
        if (label == null) {
            return -1;
        }
        for (int index = 0; index < BlackScoreReader.KEY_LABELS.length; index++) {
            if (BlackScoreReader.KEY_LABELS[index].equalsIgnoreCase(label.trim())) {
                return index;
            }
        }
        return -1;
    }

    private static final class Voice {
        final double frequency;
        final float volume;
        long frame;

        Voice(double frequency, float volume) {
            this.frequency = frequency;
            this.volume = volume;
        }
    }
}
