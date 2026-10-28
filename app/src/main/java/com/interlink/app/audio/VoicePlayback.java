package com.interlink.app.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Continuous real-time voice playback loop consuming 20ms PCM frames from JitterBuffer.
 */
public class VoicePlayback {
    private static final String TAG = "VoicePlayback";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAME_SIZE_BYTES = 640;

    private final JitterBuffer jitterBuffer;
    private AudioTrack audioTrack;
    private Thread playbackThread;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    public VoicePlayback(JitterBuffer jitterBuffer) {
        this.jitterBuffer = jitterBuffer;
    }

    public synchronized boolean start() {
        stop();

        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioTrack buffer configuration");
            return false;
        }

        int bufferSize = Math.max(minBufferSize, FRAME_SIZE_BYTES * 4);

        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build();

            audioTrack = new AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize");
                stop();
                return false;
            }

            audioTrack.play();
            isPlaying.set(true);

            playbackThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

                    while (isPlaying.get()) {
                        byte[] frame = jitterBuffer.get();
                        if (frame != null && frame.length > 0) {
                            int written = 0;
                            while (written < frame.length && isPlaying.get()) {
                                int res = audioTrack.write(frame, written, frame.length - written, AudioTrack.WRITE_BLOCKING);
                                if (res < 0) {
                                    Log.e(TAG, "AudioTrack write error: " + res);
                                    break;
                                }
                                written += res;
                            }
                        }
                    }
                }
            }, "InterLink-VoicePlayback");
            playbackThread.start();
            Log.d(TAG, "AudioTrack playback loop started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception starting voice playback", e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        isPlaying.set(false);
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.pause();
                    audioTrack.flush();
                }
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }
}

