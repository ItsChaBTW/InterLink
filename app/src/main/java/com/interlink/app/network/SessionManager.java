package com.interlink.app.network;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.util.Log;

import com.interlink.app.audio.AudioEngine;
import com.interlink.app.audio.BluetoothAudioManager;
import com.interlink.app.model.PeerDevice;
import com.interlink.app.model.SessionState;
import com.interlink.app.security.SessionSecurity;

import java.util.ArrayList;
import java.util.List;

/**
 * Central singleton orchestrating ConnectionManager and AudioEngine.
 * Handles microphone frame routing into encrypted UDP transport and updates UI/Service observers.
 */
public class SessionManager {
    private static final String TAG = "SessionManager";
    private static volatile SessionManager instance;

    public interface SessionObserver {
        void onStateChanged(SessionState state, String message);
        void onPeerUpdated(PeerDevice peer);
        void onAudioRouteChanged(String routeName, boolean isBluetooth);
        void onMuteStateChanged(boolean isMuted);
        void onError(String errorMessage);
    }

    private final Context context;
    private final SessionSecurity sessionSecurity;
    private final AudioEngine audioEngine;
    private final ConnectionManager connectionManager;
    private final List<SessionObserver> observers = new ArrayList<>();

    private boolean isMuted = false;

    public static SessionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SessionManager.class) {
                if (instance == null) {
                    instance = new SessionManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SessionManager(Context context) {
        this.context = context;
        this.sessionSecurity = new SessionSecurity();
        this.audioEngine = new AudioEngine(context);
        this.connectionManager = new ConnectionManager(context, sessionSecurity, audioEngine.getJitterBuffer());

        setupAudioBridge();
        setupConnectionBridge();
    }

    private void setupAudioBridge() {
        // Microphone frame captured -> Send via UDP VoiceTransport
        audioEngine.setOutputCallback(new AudioEngine.AudioOutputCallback() {
            @Override
            public void onAudioFrameReadyToSend(byte[] pcmData, int offset, int length) {
                if (connectionManager.getCurrentState() == SessionState.CONNECTED) {
                    VoiceTransport transport = connectionManager.getVoiceTransport();
                    if (transport != null) {
                        transport.sendAudioFrame(pcmData, offset, length);
                    }
                }
            }
        });

        // Bluetooth route updates
        audioEngine.getBluetoothAudioManager().setListener(new BluetoothAudioManager.AudioRouteListener() {
            @Override
            public void onAudioRouteChanged(String routeName, boolean isBluetooth) {
                connectionManager.sendAudioRouteUpdate(routeName);
                for (SessionObserver obs : getObserversCopy()) {
                    obs.onAudioRouteChanged(routeName, isBluetooth);
                }
            }

            @Override
            public void onAvailableDevicesChanged(List<AudioDeviceInfo> devices) {
                // Notified when devices change
            }
        });
    }

    private void setupConnectionBridge() {
        connectionManager.setConnectionListener(new ConnectionManager.ConnectionListener() {
            @Override
            public void onSessionStateChanged(SessionState newState, String message) {
                Log.d(TAG, "Session state transition: " + newState + " - " + message);

                if (newState == SessionState.CONNECTED) {
                    // Activate full duplex audio engine
                    audioEngine.start();
                    audioEngine.setMuted(isMuted);
                } else if (newState == SessionState.RECONNECTING) {
                    // Pause microphone capture during reconnection
                    audioEngine.stop();
                } else if (newState == SessionState.DISCONNECTED || newState == SessionState.ERROR) {
                    audioEngine.stop();
                }

                for (SessionObserver obs : getObserversCopy()) {
                    obs.onStateChanged(newState, message);
                }
            }

            @Override
            public void onPeerUpdated(PeerDevice peer) {
                for (SessionObserver obs : getObserversCopy()) {
                    obs.onPeerUpdated(peer);
                }
            }

            @Override
            public void onAuthFailed(String reason) {
                for (SessionObserver obs : getObserversCopy()) {
                    obs.onError(reason);
                }
            }
        });
    }

    public synchronized void registerObserver(SessionObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
            // Immediately dispatch current state
            observer.onStateChanged(connectionManager.getCurrentState(), "");
            if (connectionManager.getCurrentPeer() != null) {
                observer.onPeerUpdated(connectionManager.getCurrentPeer());
            }
            observer.onAudioRouteChanged(audioEngine.getBluetoothAudioManager().getCurrentRouteName(),
                    audioEngine.getBluetoothAudioManager().isBluetoothActive());
            observer.onMuteStateChanged(isMuted);
        }
    }

    public synchronized void unregisterObserver(SessionObserver observer) {
        observers.remove(observer);
    }

    private synchronized List<SessionObserver> getObserversCopy() {
        return new ArrayList<>(observers);
    }

    public void startHosting(String pin) {
        connectionManager.startHosting(pin);
    }

    public void joinSession(String hostIp, int controlPort, String pin) {
        connectionManager.joinSession(hostIp, controlPort, pin);
    }

    public void endSession() {
        connectionManager.disconnectSession("User ended session");
        audioEngine.stop();
    }

    public void toggleMute() {
        setMuted(!isMuted);
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
        audioEngine.setMuted(muted);
        connectionManager.sendMuteUpdate(muted);
        for (SessionObserver obs : getObserversCopy()) {
            obs.onMuteStateChanged(muted);
        }
    }

    public boolean isMuted() {
        return isMuted;
    }

    public boolean selectCommunicationDevice(AudioDeviceInfo device) {
        return audioEngine.selectCommunicationDevice(device);
    }

    public List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        return audioEngine.getAvailableCommunicationDevices();
    }

    public String getCurrentAudioRouteName() {
        return audioEngine.getBluetoothAudioManager().getCurrentRouteName();
    }

    public boolean isBluetoothActive() {
        return audioEngine.getBluetoothAudioManager().isBluetoothActive();
    }

    public SessionState getCurrentState() {
        return connectionManager.getCurrentState();
    }

    public PeerDevice getCurrentPeer() {
        return connectionManager.getCurrentPeer();
    }

    public String getCurrentSessionId() {
        return connectionManager.getCurrentSessionId();
    }

    public String getPairingPin() {
        return connectionManager.getPairingPin();
    }

    public HostDiscovery getHostDiscovery() {
        return connectionManager.getHostDiscovery();
    }
}

