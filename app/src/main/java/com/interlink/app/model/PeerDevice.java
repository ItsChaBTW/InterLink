package com.interlink.app.model;

import java.io.Serializable;

/**
 * Represents the remote intercom participant and real-time telemetry metrics.
 */
public class PeerDevice implements Serializable {
    public enum Role {
        HOST,
        GUEST
    }

    private String deviceName;
    private String ipAddress;
    private int controlPort;
    private int voicePort;
    private Role role;
    private boolean isMuted;
    private String audioRouteName;

    // Real-time telemetry
    private long roundTripTimeMs;
    private long jitterMs;
    private float packetLossPercent;
    private long lastSeenTimestamp;

    public PeerDevice(String deviceName, String ipAddress, int controlPort, int voicePort, Role role) {
        this.deviceName = deviceName != null ? deviceName : "Rider";
        this.ipAddress = ipAddress;
        this.controlPort = controlPort;
        this.voicePort = voicePort;
        this.role = role;
        this.isMuted = false;
        this.audioRouteName = "Internal";
        this.lastSeenTimestamp = System.currentTimeMillis();
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getControlPort() {
        return controlPort;
    }

    public void setControlPort(int controlPort) {
        this.controlPort = controlPort;
    }

    public int getVoicePort() {
        return voicePort;
    }

    public void setVoicePort(int voicePort) {
        this.voicePort = voicePort;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setMuted(boolean muted) {
        isMuted = muted;
    }

    public String getAudioRouteName() {
        return audioRouteName;
    }

    public void setAudioRouteName(String audioRouteName) {
        this.audioRouteName = audioRouteName;
    }

    public long getRoundTripTimeMs() {
        return roundTripTimeMs;
    }

    public void setRoundTripTimeMs(long roundTripTimeMs) {
        this.roundTripTimeMs = roundTripTimeMs;
    }

    public long getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(long jitterMs) {
        this.jitterMs = jitterMs;
    }

    public float getPacketLossPercent() {
        return packetLossPercent;
    }

    public void setPacketLossPercent(float packetLossPercent) {
        this.packetLossPercent = packetLossPercent;
    }

    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }

    public void updateLastSeen() {
        this.lastSeenTimestamp = System.currentTimeMillis();
    }
}

