package com.interlink.app.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.util.Log;

import java.util.List;

/**
 * High-level coordinator of all audio subsystems:
 * Microphone capture, playback loop, jitter buffer, and Bluetooth helmet routing.
 */
public class AudioEngine {
    private static final String TAG = "AudioEngine";

    public interface AudioOutputCallback {
        void onAudioFrameReadyToSend(byte[] pcmData, int offset, int length);
    }

    private final Context context;
    private final JitterBuffer jitterBuffer;
    private final MicrophoneCapture microphoneCapture;
    private final VoicePlayback voicePlayback;
    private final BluetoothAudioManager bluetoothAudioManager;

    private AudioOutputCallback outputCallback;
    private boolean isRunning = false;

    public AudioEngine(Context context) {
        this.context = context.getApplicationContext();
        this.jitterBuffer = new JitterBuffer();

        this.microphoneCapture = new MicrophoneCapture(new MicrophoneCapture.AudioFrameListener() {
            @Override
            public void onAudioFrameCaptured(byte[] audioData, int offset, int length) {
                if (outputCallback != null && isRunning) {
                    outputCallback.onAudioFrameReadyToSend(audioData, offset, length);
                }
            }
        });

        this.voicePlayback = new VoicePlayback(this.jitterBuffer);
        this.bluetoothAudioManager = new BluetoothAudioManager(this.context);
    }

    public void setOutputCallback(AudioOutputCallback outputCallback) {
        this.outputCallback = outputCallback;
    }

    public synchronized boolean start() {
        if (isRunning) return true;

        Log.d(TAG, "Starting AudioEngine...");
        bluetoothAudioManager.start();

        jitterBuffer.reset();
        boolean playbackStarted = voicePlayback.start();
        boolean captureStarted = microphoneCapture.start();

        if (!playbackStarted || !captureStarted) {
            Log.e(TAG, "AudioEngine failed to start full duplex components. Capture=" + captureStarted + ", Playback=" + playbackStarted);
            stop();
            return false;
        }

        isRunning = true;
        Log.d(TAG, "AudioEngine started successfully");
        return true;
    }

    public synchronized void stop() {
        if (!isRunning) return;

        Log.d(TAG, "Stopping AudioEngine...");
        isRunning = false;
        microphoneCapture.stop();
        voicePlayback.stop();
        bluetoothAudioManager.stop();
        jitterBuffer.reset();
    }

    public void setMuted(boolean muted) {
        microphoneCapture.setMuted(muted);
    }

    public boolean isMuted() {
        return microphoneCapture.isMuted();
    }

    public JitterBuffer getJitterBuffer() {
        return jitterBuffer;
    }

    public BluetoothAudioManager getBluetoothAudioManager() {
        return bluetoothAudioManager;
    }

    public List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        return bluetoothAudioManager.getAvailableDevices();
    }

    public boolean selectCommunicationDevice(AudioDeviceInfo device) {
        return bluetoothAudioManager.setCommunicationDevice(device);
    }

    public void resetToDefaultAudioRoute() {
        bluetoothAudioManager.clearCommunicationDevice();
    }

    public boolean isRunning() {
        return isRunning;
    }
}

