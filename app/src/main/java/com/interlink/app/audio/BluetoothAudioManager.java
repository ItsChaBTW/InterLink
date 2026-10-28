package com.interlink.app.audio;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages communication audio routing for Bluetooth helmet headsets (HFP / SCO / BLE Audio),
 * wired headsets, and phone speakers using modern Android 12+ (API 31+) communication device APIs.
 */
public class BluetoothAudioManager {
    private static final String TAG = "BluetoothAudioMgr";

    public interface AudioRouteListener {
        void onAudioRouteChanged(String routeName, boolean isBluetooth);
        void onAvailableDevicesChanged(List<AudioDeviceInfo> devices);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final Handler mainHandler;
    private AudioRouteListener listener;

    private AudioDeviceCallback deviceCallback;
    private AudioFocusRequest audioFocusRequest;
    private AudioDeviceInfo selectedDevice;

    public BluetoothAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(AudioRouteListener listener) {
        this.listener = listener;
    }

    /**
     * Initializes communication mode and registers device callbacks.
     */
    public synchronized void start() {
        if (audioManager == null) return;

        requestAudioFocus();

        // Switch to MODE_IN_COMMUNICATION for voice duplexing
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.e(TAG, "Failed setting MODE_IN_COMMUNICATION", e);
        }

        // Register device attach/detach callback
        if (deviceCallback == null) {
            deviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    super.onAudioDevicesAdded(addedDevices);
                    Log.d(TAG, "Audio device(s) added");
                    handleDevicesUpdated();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    super.onAudioDevicesRemoved(removedDevices);
                    Log.d(TAG, "Audio device(s) removed");
                    handleDevicesUpdated();
                }
            };
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
        }

        // Select best route (preferring Bluetooth helmet headset)
        autoSelectBestDevice();
    }

    /**
     * Auto-selects helmet headset (Bluetooth SCO / BLE Headset) if attached, else default earpiece/speaker.
     */
    public synchronized void autoSelectBestDevice() {
        if (audioManager == null) return;

        List<AudioDeviceInfo> commDevices = audioManager.getAvailableCommunicationDevices();
        AudioDeviceInfo bestCandidate = null;

        // 1. Look for Bluetooth SCO or BLE Headset
        for (AudioDeviceInfo device : commDevices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                bestCandidate = device;
                break;
            }
        }

        // 2. Look for Wired Headset
        if (bestCandidate == null) {
            for (AudioDeviceInfo device : commDevices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    bestCandidate = device;
                    break;
                }
            }
        }

        if (bestCandidate != null) {
            setCommunicationDevice(bestCandidate);
        } else {
            // Default built-in speaker/earpiece
            clearCommunicationDevice();
        }

        notifyRouteChanged();
    }

    /**
     * Explicitly sets the active communication audio device.
     */
    public synchronized boolean setCommunicationDevice(AudioDeviceInfo device) {
        if (audioManager == null || device == null) return false;

        boolean success = audioManager.setCommunicationDevice(device);
        if (success) {
            this.selectedDevice = device;
            Log.d(TAG, "Successfully activated communication device: " + getDeviceDisplayName(device));
        } else {
            Log.w(TAG, "Failed to activate communication device: " + getDeviceDisplayName(device));
        }
        notifyRouteChanged();
        return success;
    }

    /**
     * Clears selected communication device and falls back to system routing.
     */
    public synchronized void clearCommunicationDevice() {
        if (audioManager != null) {
            audioManager.clearCommunicationDevice();
        }
        this.selectedDevice = null;
        Log.d(TAG, "Communication device cleared");
        notifyRouteChanged();
    }

    /**
     * Gets all currently available communication devices.
     */
    public List<AudioDeviceInfo> getAvailableDevices() {
        if (audioManager != null) {
            return audioManager.getAvailableCommunicationDevices();
        }
        return new ArrayList<>();
    }

    public AudioDeviceInfo getSelectedDevice() {
        return selectedDevice;
    }

    public String getCurrentRouteName() {
        if (selectedDevice != null) {
            return getDeviceDisplayName(selectedDevice);
        }
        AudioDeviceInfo active = audioManager != null ? audioManager.getCommunicationDevice() : null;
        if (active != null) {
            return getDeviceDisplayName(active);
        }
        return "Internal Audio (Speaker/Mic)";
    }

    public boolean isBluetoothActive() {
        if (selectedDevice != null) {
            int type = selectedDevice.getType();
            return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                   type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                   type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
        }
        return false;
    }

    public static String getDeviceDisplayName(AudioDeviceInfo device) {
        if (device == null) return "Unknown Device";
        CharSequence name = device.getProductName();
        String strName = (name != null && name.length() > 0) ? name.toString() : "Audio Device";

        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return strName + " (Helmet Bluetooth SCO)";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return strName + " (BLE Helmet Audio)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return strName + " (Wired Helmet Headset)";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Phone Speakerphone";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Phone Earpiece";
            default:
                return strName;
        }
    }

    private void handleDevicesUpdated() {
        autoSelectBestDevice();
        if (listener != null) {
            listener.onAvailableDevicesChanged(getAvailableDevices());
        }
    }

    private void notifyRouteChanged() {
        final String route = getCurrentRouteName();
        final boolean isBt = isBluetoothActive();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAudioRouteChanged(route, isBt);
                }
            }
        });
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        try {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            Log.d(TAG, "Audio focus changed: " + focusChange);
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } catch (Exception e) {
            Log.e(TAG, "Failed requesting audio focus", e);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            } catch (Exception e) {
                Log.e(TAG, "Failed abandoning audio focus", e);
            }
        }
    }

    public synchronized void stop() {
        if (audioManager != null) {
            clearCommunicationDevice();
            if (deviceCallback != null) {
                audioManager.unregisterAudioDeviceCallback(deviceCallback);
                deviceCallback = null;
            }
            try {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception ignored) {
            }
            abandonAudioFocus();
        }
        selectedDevice = null;
    }
}

