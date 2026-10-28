package com.interlink.app.model;

/**
 * Represents the current lifecycle and connectivity state of the InterLink session.
 */
public enum SessionState {
    IDLE,
    HOSTING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    ERROR;

    public boolean isActive() {
        return this == HOSTING || this == CONNECTING || this == AUTHENTICATING ||
               this == CONNECTED || this == RECONNECTING;
    }

    public boolean canStreamAudio() {
        return this == CONNECTED;
    }

    public boolean isHosting() {
        return this == HOSTING;
    }
}

