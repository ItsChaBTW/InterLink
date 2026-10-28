package com.interlink.app.ui;

import android.app.Application;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.interlink.app.model.PeerDevice;
import com.interlink.app.model.SessionState;
import com.interlink.app.network.HostDiscovery;
import com.interlink.app.network.SessionManager;
import com.interlink.app.security.SessionSecurity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ViewModel managing session UI state, timer, device route selection, and discovery.
 */
public class SessionViewModel extends AndroidViewModel implements SessionManager.SessionObserver {

    public static class DiscoveredHost {
        public final String name;
        public final String ip;
        public final int port;
        public final String sessionId;

        public DiscoveredHost(String name, String ip, int port, String sessionId) {
            this.name = name;
            this.ip = ip;
            this.port = port;
            this.sessionId = sessionId;
        }
    }

    private final SessionManager sessionManager;
    private final MutableLiveData<SessionState> sessionState = new MutableLiveData<>(SessionState.IDLE);
    private final MutableLiveData<String> statusMessage = new MutableLiveData<>("");
    private final MutableLiveData<PeerDevice> peerDevice = new MutableLiveData<>(null);
    private final MutableLiveData<String> audioRouteName = new MutableLiveData<>("Internal");
    private final MutableLiveData<Boolean> isBluetoothActive = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isMuted = new MutableLiveData<>(false);
    private final MutableLiveData<String> sessionDurationFormatted = new MutableLiveData<>("00:00");
    private final MutableLiveData<List<DiscoveredHost>> discoveredHosts = new MutableLiveData<>(new ArrayList<DiscoveredHost>());

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long sessionStartTime = 0;
    private boolean isTimerRunning = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimerRunning && sessionStartTime > 0) {
                long elapsedSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000;
                long minutes = elapsedSeconds / 60;
                long seconds = elapsedSeconds % 60;
                sessionDurationFormatted.setValue(String.format(Locale.US, "%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    public SessionViewModel(@NonNull Application application) {
        super(application);
        this.sessionManager = SessionManager.getInstance(application);
        this.sessionManager.registerObserver(this);
    }

    public LiveData<SessionState> getSessionState() {
        return sessionState;
    }

    public LiveData<String> getStatusMessage() {
        return statusMessage;
    }

    public LiveData<PeerDevice> getPeerDevice() {
        return peerDevice;
    }

    public LiveData<String> getAudioRouteName() {
        return audioRouteName;
    }

    public LiveData<Boolean> getIsBluetoothActive() {
        return isBluetoothActive;
    }

    public LiveData<Boolean> getIsMuted() {
        return isMuted;
    }

    public LiveData<String> getSessionDurationFormatted() {
        return sessionDurationFormatted;
    }

    public LiveData<List<DiscoveredHost>> getDiscoveredHosts() {
        return discoveredHosts;
    }

    public void startHosting(String customPin) {
        String pin = (customPin != null && !customPin.trim().isEmpty()) ? customPin.trim() : SessionSecurity.generatePairingPin();
        sessionManager.startHosting(pin);
    }

    public void joinSession(String hostIp, int controlPort, String pin) {
        sessionManager.joinSession(hostIp, controlPort, pin);
    }

    public void endSession() {
        sessionManager.endSession();
        stopTimer();
    }

    public void toggleMute() {
        sessionManager.toggleMute();
    }

    public void selectCommunicationDevice(AudioDeviceInfo device) {
        sessionManager.selectCommunicationDevice(device);
    }

    public List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        return sessionManager.getAvailableCommunicationDevices();
    }

    public void startHostDiscovery() {
        final List<DiscoveredHost> list = new ArrayList<>();
        discoveredHosts.setValue(list);
        sessionManager.getHostDiscovery().startListening(new HostDiscovery.DiscoveryListener() {
            @Override
            public void onHostDiscovered(String deviceName, String ipAddress, int controlPort, String sessionId) {
                List<DiscoveredHost> current = discoveredHosts.getValue();
                if (current == null) current = new ArrayList<>();

                boolean exists = false;
                for (DiscoveredHost h : current) {
                    if (h.ip.equals(ipAddress) && h.port == controlPort) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    current.add(new DiscoveredHost(deviceName, ipAddress, controlPort, sessionId));
                    discoveredHosts.setValue(current);
                }
            }
        });
    }

    public void stopHostDiscovery() {
        sessionManager.getHostDiscovery().stopListening();
    }

    private void startTimer() {
        if (!isTimerRunning) {
            sessionStartTime = System.currentTimeMillis();
            isTimerRunning = true;
            timerHandler.post(timerRunnable);
        }
    }

    private void stopTimer() {
        isTimerRunning = false;
        sessionStartTime = 0;
        timerHandler.removeCallbacks(timerRunnable);
        sessionDurationFormatted.setValue("00:00");
    }

    @Override
    public void onStateChanged(SessionState state, String message) {
        sessionState.setValue(state);
        statusMessage.setValue(message);

        if (state == SessionState.CONNECTED) {
            startTimer();
        } else if (state == SessionState.DISCONNECTED || state == SessionState.IDLE || state == SessionState.ERROR) {
            stopTimer();
        }
    }

    @Override
    public void onPeerUpdated(PeerDevice peer) {
        peerDevice.setValue(peer);
    }

    @Override
    public void onAudioRouteChanged(String routeName, boolean isBluetooth) {
        audioRouteName.setValue(routeName);
        isBluetoothActive.setValue(isBluetooth);
    }

    @Override
    public void onMuteStateChanged(boolean muted) {
        isMuted.setValue(muted);
    }

    @Override
    public void onError(String errorMessage) {
        statusMessage.setValue(errorMessage);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        sessionManager.unregisterObserver(this);
        stopHostDiscovery();
        stopTimer();
    }
}

