package com.interlink.app.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures microphone audio in 20ms frames (640 bytes) @ 16kHz 16-bit Mono PCM
 * using VOICE_COMMUNICATION source with hardware AEC & Noise Suppression.
 */
public class MicrophoneCapture {
    private static final String TAG = "MicrophoneCapture";

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAME_SIZE_BYTES = 640; // 20ms

    public interface AudioFrameListener {
        void onAudioFrameCaptured(byte[] audioData, int offset, int length);
    }

    private final AudioFrameListener frameListener;
    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    private Thread captureThread;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isMuted = new AtomicBoolean(false);

    public MicrophoneCapture(AudioFrameListener frameListener) {
        this.frameListener = frameListener;
    }

    public void setMuted(boolean muted) {
        this.isMuted.set(muted);
        Log.d(TAG, "Microphone mute state: " + muted);
    }

    public boolean isMuted() {
        return isMuted.get();
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start() {
        stop();

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer configuration");
            return false;
        }

        int bufferSize = Math.max(minBufferSize, FRAME_SIZE_BYTES * 4);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                stop();
                return false;
            }

            // Enable Acoustic Echo Cancellation (AEC) if available
            int audioSessionId = audioRecord.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                    if (echoCanceler != null) {
                        echoCanceler.setEnabled(true);
                        Log.d(TAG, "AcousticEchoCanceler enabled");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not initialize AcousticEchoCanceler", e);
                }
            }

            // Enable Noise Suppressor (NS) if available
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                    if (noiseSuppressor != null) {
                        noiseSuppressor.setEnabled(true);
                        Log.d(TAG, "NoiseSuppressor enabled");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not initialize NoiseSuppressor", e);
                }
            }

            audioRecord.startRecording();
            isRecording.set(true);

            captureThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                    byte[] audioBuffer = new byte[FRAME_SIZE_BYTES];
                    byte[] silenceBuffer = new byte[FRAME_SIZE_BYTES];
                    Arrays.fill(silenceBuffer, (byte) 0);

                    while (isRecording.get()) {
                        int bytesRead = 0;
                        while (bytesRead < FRAME_SIZE_BYTES && isRecording.get()) {
                            int read = audioRecord.read(audioBuffer, bytesRead, FRAME_SIZE_BYTES - bytesRead);
                            if (read < 0) {
                                Log.e(TAG, "AudioRecord read error: " + read);
                                break;
                            }
                            bytesRead += read;
                        }

                        if (bytesRead == FRAME_SIZE_BYTES && isRecording.get()) {
                            if (isMuted.get()) {
                                // When muted, transmit comfort silence or zeros to keep timing intact
                                if (frameListener != null) {
                                    frameListener.onAudioFrameCaptured(silenceBuffer, 0, FRAME_SIZE_BYTES);
                                }
                            } else {
                                if (frameListener != null) {
                                    frameListener.onAudioFrameCaptured(audioBuffer, 0, FRAME_SIZE_BYTES);
                                }
                            }
                        }
                    }
                }
            }, "InterLink-MicCapture");
            captureThread.start();
            Log.d(TAG, "Microphone capture started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception starting microphone capture", e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        isRecording.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
        if (echoCanceler != null) {
            try {
                echoCanceler.release();
            } catch (Exception ignored) {
            }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Exception ignored) {
            }
            noiseSuppressor = null;
        }
    }

    public boolean isRecording() {
        return isRecording.get();
    }
}

