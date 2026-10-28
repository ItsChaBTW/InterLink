package com.interlink.app.network;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.Base64;
import android.util.Log;

import com.interlink.app.audio.JitterBuffer;
import com.interlink.app.model.PeerDevice;
import com.interlink.app.model.SessionState;
import com.interlink.app.security.SessionSecurity;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates session negotiation, authentication, full-duplex voice transport,
 * and bounded exponential backoff reconnection.
 */
public class ConnectionManager {
    private static final String TAG = "ConnectionManager";
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    public interface ConnectionListener {
        void onSessionStateChanged(SessionState newState, String message);
        void onPeerUpdated(PeerDevice peer);
        void onAuthFailed(String reason);
    }

    private final Context context;
    private final Handler mainHandler;
    private final SessionSecurity sessionSecurity;
    private final HostDiscovery hostDiscovery;
    private final JitterBuffer jitterBuffer;

    private ControlChannel controlChannel;
    private VoiceTransport voiceTransport;
    private ConnectionListener listener;

    private SessionState currentState = SessionState.IDLE;
    private PeerDevice currentPeer;
    private String currentSessionId;
    private String pairingPin;
    private byte[] sessionSalt;
    private byte[] pendingHostChallenge;
    private byte[] pendingGuestChallenge;
    private boolean isHost = false;

    private int localVoicePort = PacketProtocol.DEFAULT_VOICE_PORT;
    private String targetHostIp;
    private int targetControlPort = PacketProtocol.DEFAULT_CONTROL_PORT;

    // Bounded exponential backoff reconnection
    private int reconnectAttempts = 0;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);

    public ConnectionManager(Context context, SessionSecurity sessionSecurity, JitterBuffer jitterBuffer) {
        this.context = context.getApplicationContext();
        this.sessionSecurity = sessionSecurity;
        this.jitterBuffer = jitterBuffer;
        this.hostDiscovery = new HostDiscovery(this.context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public SessionState getCurrentState() {
        return currentState;
    }

    public PeerDevice getCurrentPeer() {
        return currentPeer;
    }

    public VoiceTransport getVoiceTransport() {
        return voiceTransport;
    }

    public HostDiscovery getHostDiscovery() {
        return hostDiscovery;
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getPairingPin() {
        return pairingPin;
    }

    /**
     * Starts hosting an intercom session.
     */
    public synchronized void startHosting(String pin) {
        disconnectSession("Starting new host session");
        this.isHost = true;
        this.pairingPin = pin;
        this.currentSessionId = UUID.randomUUID().toString().substring(0, 8);
        this.sessionSalt = sessionSecurity.generateSalt();

        setState(SessionState.HOSTING, "Waiting for riding partner to connect...");

        // Setup control channel
        controlChannel = new ControlChannel(createControlCallback());
        controlChannel.startServer(PacketProtocol.DEFAULT_CONTROL_PORT);

        // Start discovery broadcasting
        hostDiscovery.startBroadcasting(Build.MODEL, PacketProtocol.DEFAULT_CONTROL_PORT, currentSessionId);
    }

    /**
     * Connects as a guest to a host.
     */
    public synchronized void joinSession(String hostIp, int controlPort, String pin) {
        disconnectSession("Joining session");
        this.isHost = false;
        this.targetHostIp = hostIp;
        this.targetControlPort = controlPort;
        this.pairingPin = pin;
        this.reconnectAttempts = 0;

        setState(SessionState.CONNECTING, "Connecting to host " + hostIp + "...");

        controlChannel = new ControlChannel(createControlCallback());
        controlChannel.connectToHost(hostIp, controlPort);
    }

    private ControlChannel.ControlCallback createControlCallback() {
        return new ControlChannel.ControlCallback() {
            @Override
            public void onConnected() {
                Log.d(TAG, "Control channel TCP connected");
                if (!isHost) {
                    setState(SessionState.AUTHENTICATING, "Exchanging authentication keys...");
                    try {
                        controlChannel.sendMessage(PacketProtocol.createHandshakeInit(Build.MODEL));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed sending HANDSHAKE_INIT", e);
                    }
                }
            }

            @Override
            public void onMessage(JSONObject message) {
                handleControlMessage(message);
            }

            @Override
            public void onDisconnected(String reason) {
                Log.w(TAG, "Control channel disconnected: " + reason);
                handleConnectionLoss(reason);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Control channel error: " + error);
                if (currentState == SessionState.CONNECTING || currentState == SessionState.AUTHENTICATING) {
                    setState(SessionState.ERROR, error);
                } else {
                    handleConnectionLoss(error);
                }
            }

            @Override
            public void onRttMeasured(long rttMs) {
                if (currentPeer != null) {
                    currentPeer.setRoundTripTimeMs(rttMs);
                    currentPeer.updateLastSeen();
                    if (listener != null) {
                        listener.onPeerUpdated(currentPeer);
                    }
                }
            }
        };
    }

    private void handleControlMessage(JSONObject message) {
        String type = message.optString(PacketProtocol.KEY_TYPE);
        Log.d(TAG, "Received control message: " + type);

        try {
            if (PacketProtocol.MSG_HANDSHAKE_INIT.equals(type)) {
                if (!isHost) return;
                String guestName = message.optString(PacketProtocol.KEY_DEVICE_NAME, "Rider Guest");
                currentPeer = new PeerDevice(guestName, null, targetControlPort, 0, PeerDevice.Role.GUEST);

                setState(SessionState.AUTHENTICATING, "Authenticating PIN with " + guestName + "...");

                // Host initializes key with pairingPin & salt
                sessionSecurity.initializeSessionKey(pairingPin, sessionSalt);

                // Host generates challenge and sends to guest
                pendingHostChallenge = sessionSecurity.generateChallenge();
                String saltB64 = Base64.getEncoder().encodeToString(sessionSalt);
                String challengeB64 = Base64.getEncoder().encodeToString(pendingHostChallenge);

                controlChannel.sendMessage(PacketProtocol.createAuthChallenge(currentSessionId, saltB64, challengeB64));

            } else if (PacketProtocol.MSG_AUTH_CHALLENGE.equals(type)) {
                if (isHost) return;
                this.currentSessionId = message.optString(PacketProtocol.KEY_SESSION_ID);
                String saltB64 = message.optString(PacketProtocol.KEY_SALT);
                String challengeB64 = message.optString(PacketProtocol.KEY_CHALLENGE);

                byte[] salt = Base64.getDecoder().decode(saltB64);
                byte[] hostChallenge = Base64.getDecoder().decode(challengeB64);

                // Guest derives key from entered PIN + received Salt
                sessionSecurity.initializeSessionKey(pairingPin, salt);

                // Guest computes HMAC of host challenge
                byte[] hmacResponse = sessionSecurity.computeHmac(hostChallenge);

                // Guest generates guest challenge for mutual auth
                pendingGuestChallenge = sessionSecurity.generateChallenge();

                // Prepare UDP voice transport
                initVoiceTransport(targetHostIp, PacketProtocol.DEFAULT_VOICE_PORT);

                String hmacB64 = Base64.getEncoder().encodeToString(hmacResponse);
                String guestChallengeB64 = Base64.getEncoder().encodeToString(pendingGuestChallenge);

                controlChannel.sendMessage(PacketProtocol.createAuthResponse(hmacB64, guestChallengeB64, localVoicePort));

            } else if (PacketProtocol.MSG_AUTH_RESPONSE.equals(type)) {
                if (!isHost) return;
                String hmacB64 = message.optString(PacketProtocol.KEY_HMAC);
                String guestChallengeB64 = message.optString(PacketProtocol.KEY_CHALLENGE);
                int guestVoicePort = message.optInt(PacketProtocol.KEY_VOICE_PORT, PacketProtocol.DEFAULT_VOICE_PORT);

                byte[] receivedHmac = Base64.getDecoder().decode(hmacB64);
                byte[] guestChallenge = Base64.getDecoder().decode(guestChallengeB64);

                // Verify Guest's HMAC against our challenge
                boolean valid = sessionSecurity.verifyHmac(pendingHostChallenge, receivedHmac);
                if (!valid) {
                    Log.w(TAG, "Guest HMAC verification failed! Invalid PIN.");
                    controlChannel.sendMessage(PacketProtocol.createAuthFailure("INVALID_PIN"));
                    if (listener != null) {
                        listener.onAuthFailed("Invalid pairing PIN from guest");
                    }
                    disconnectSession("Authentication failed");
                    return;
                }

                // Compute HMAC response for mutual authentication
                byte[] hostHmac = sessionSecurity.computeHmac(guestChallenge);
                String hostHmacB64 = Base64.getEncoder().encodeToString(hostHmac);

                // Initialize host voice transport
                String guestIp = "127.0.0.1"; // Will resolve via socket
                initVoiceTransport(guestIp, guestVoicePort);

                controlChannel.sendMessage(PacketProtocol.createAuthSuccess(hostHmacB64, localVoicePort, Build.MODEL));

                // Stop discovery beaconing now that session is paired
                hostDiscovery.stopBroadcasting();

                // Complete connection
                setState(SessionState.CONNECTED, "Connected with " + (currentPeer != null ? currentPeer.getDeviceName() : "Rider"));

            } else if (PacketProtocol.MSG_AUTH_SUCCESS.equals(type)) {
                if (isHost) return;
                String hostHmacB64 = message.optString(PacketProtocol.KEY_HMAC);
                int hostVoicePort = message.optInt(PacketProtocol.KEY_VOICE_PORT);
                String hostName = message.optString(PacketProtocol.KEY_DEVICE_NAME, "Host Rider");

                byte[] receivedHostHmac = Base64.getDecoder().decode(hostHmacB64);
                boolean valid = sessionSecurity.verifyHmac(pendingGuestChallenge, receivedHostHmac);

                if (!valid) {
                    Log.e(TAG, "Host mutual auth HMAC failed!");
                    disconnectSession("Mutual authentication failed");
                    return;
                }

                currentPeer = new PeerDevice(hostName, targetHostIp, targetControlPort, hostVoicePort, PeerDevice.Role.HOST);
                if (voiceTransport != null) {
                    voiceTransport.updatePeerEndpoint(targetHostIp, hostVoicePort);
                }

                reconnectAttempts = 0;
                isReconnecting.set(false);

                setState(SessionState.CONNECTED, "Connected with " + hostName);

            } else if (PacketProtocol.MSG_AUTH_FAILURE.equals(type)) {
                String reason = message.optString(PacketProtocol.KEY_REASON, "Authentication failed");
                Log.w(TAG, "Auth failure received: " + reason);
                if (listener != null) {
                    listener.onAuthFailed(reason);
                }
                setState(SessionState.ERROR, "Authentication Failed: " + reason);
                disconnectSession("Auth failed");

            } else if (PacketProtocol.MSG_MUTE_UPDATE.equals(type)) {
                boolean muted = message.optBoolean(PacketProtocol.KEY_MUTED, false);
                if (currentPeer != null) {
                    currentPeer.setMuted(muted);
                    if (listener != null) {
                        listener.onPeerUpdated(currentPeer);
                    }
                }

            } else if (PacketProtocol.MSG_AUDIO_ROUTE.equals(type)) {
                String route = message.optString(PacketProtocol.KEY_ROUTE_NAME, "Unknown");
                if (currentPeer != null) {
                    currentPeer.setAudioRouteName(route);
                    if (listener != null) {
                        listener.onPeerUpdated(currentPeer);
                    }
                }

            } else if (PacketProtocol.MSG_DISCONNECT.equals(type)) {
                String reason = message.optString(PacketProtocol.KEY_REASON, "Peer left session");
                Log.i(TAG, "Remote peer disconnected gracefully: " + reason);
                disconnectSession(reason);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing control message " + type, e);
        }
    }

    private void initVoiceTransport(String peerIp, int peerVoicePort) {
        if (voiceTransport != null) {
            voiceTransport.stop();
        }
        voiceTransport = new VoiceTransport(sessionSecurity, jitterBuffer);
        voiceTransport.setTelemetryListener(new VoiceTransport.TelemetryListener() {
            @Override
            public void onTelemetryUpdate(long jitterMs, float lossPercent) {
                if (currentPeer != null) {
                    currentPeer.setJitterMs(jitterMs);
                    currentPeer.setPacketLossPercent(lossPercent);
                    if (listener != null) {
                        listener.onPeerUpdated(currentPeer);
                    }
                }
            }
        });

        try {
            localVoicePort = voiceTransport.start(PacketProtocol.DEFAULT_VOICE_PORT, peerIp, peerVoicePort);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VoiceTransport", e);
        }
    }

    /**
     * Handles connection drop with bounded exponential backoff reconnection.
     */
    private void handleConnectionLoss(String reason) {
        if (currentState == SessionState.IDLE || currentState == SessionState.DISCONNECTED) {
            return;
        }

        if (isHost) {
            // As host, restart server listening and discovery
            setState(SessionState.RECONNECTING, "Rider lost. Waiting for reconnection...");
            startHosting(pairingPin);
            return;
        }

        // As guest, attempt reconnect with bounded exponential backoff: 1s, 2s, 4s, 8s (max 5)
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++;
            long backoffDelayMs = (long) Math.min(8000, 1000 * Math.pow(2, reconnectAttempts - 1));
            isReconnecting.set(true);

            setState(SessionState.RECONNECTING, "Connection lost. Reconnecting in " + (backoffDelayMs / 1000) + "s (Attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")...");

            reconnectHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isReconnecting.get() && targetHostIp != null && pairingPin != null) {
                        Log.d(TAG, "Executing reconnection attempt " + reconnectAttempts);
                        joinSession(targetHostIp, targetControlPort, pairingPin);
                    }
                }
            }, backoffDelayMs);
        } else {
            setState(SessionState.DISCONNECTED, "Rider unreachable after " + MAX_RECONNECT_ATTEMPTS + " attempts.");
            disconnectSession("Max reconnection attempts exceeded");
        }
    }

    public synchronized void sendMuteUpdate(boolean isMuted) {
        if (controlChannel != null && controlChannel.isConnected()) {
            try {
                controlChannel.sendMessage(PacketProtocol.createMuteUpdate(isMuted));
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void sendAudioRouteUpdate(String routeName) {
        if (controlChannel != null && controlChannel.isConnected()) {
            try {
                controlChannel.sendMessage(PacketProtocol.createAudioRouteUpdate(routeName));
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void disconnectSession(String reason) {
        reconnectHandler.removeCallbacksAndMessages(null);
        isReconnecting.set(false);

        if (controlChannel != null && controlChannel.isConnected()) {
            try {
                controlChannel.sendMessage(PacketProtocol.createDisconnect(reason));
            } catch (Exception ignored) {
            }
        }

        if (controlChannel != null) {
            controlChannel.stop();
            controlChannel = null;
        }
        if (voiceTransport != null) {
            voiceTransport.stop();
            voiceTransport = null;
        }
        hostDiscovery.stopBroadcasting();
        hostDiscovery.stopListening();
        sessionSecurity.destroy();

        currentPeer = null;
        setState(SessionState.DISCONNECTED, reason);
    }

    private void setState(final SessionState state, final String message) {
        this.currentState = state;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onSessionStateChanged(state, message);
                }
            }
        });
    }
}
