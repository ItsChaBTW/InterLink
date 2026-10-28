package com.interlink.app.network;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Protocol framing for both real-time UDP voice datagrams and TCP control channel messages.
 */
public class PacketProtocol {
    // Magic header for voice packets: 'I', 'N', 'T', 'L' = 0x494E544C
    public static final int VOICE_MAGIC = 0x494E544C;
    public static final byte PROTOCOL_VERSION = 1;

    public static final byte TYPE_VOICE_PCM = 0x01;
    public static final byte TYPE_VOICE_SILENCE = 0x02;

    public static final int VOICE_HEADER_LENGTH = 24; // 4 magic + 1 ver + 1 type + 2 flags + 8 seq + 8 ts

    // Default ports
    public static final int DEFAULT_CONTROL_PORT = 45455;
    public static final int DEFAULT_VOICE_PORT = 45456;
    public static final int DISCOVERY_PORT = 45454;

    // Control message types
    public static final String MSG_HANDSHAKE_INIT = "HANDSHAKE_INIT";
    public static final String MSG_AUTH_CHALLENGE = "AUTH_CHALLENGE";
    public static final String MSG_AUTH_RESPONSE = "AUTH_RESPONSE";
    public static final String MSG_AUTH_SUCCESS = "AUTH_SUCCESS";
    public static final String MSG_AUTH_FAILURE = "AUTH_FAILURE";
    public static final String MSG_HEARTBEAT = "HEARTBEAT";
    public static final String MSG_HEARTBEAT_ACK = "HEARTBEAT_ACK";
    public static final String MSG_MUTE_UPDATE = "MUTE_UPDATE";
    public static final String MSG_AUDIO_ROUTE = "AUDIO_ROUTE";
    public static final String MSG_DISCONNECT = "DISCONNECT";

    // JSON fields
    public static final String KEY_TYPE = "type";
    public static final String KEY_VERSION = "version";
    public static final String KEY_DEVICE_NAME = "device_name";
    public static final String KEY_SESSION_ID = "session_id";
    public static final String KEY_SALT = "salt";
    public static final String KEY_CHALLENGE = "challenge";
    public static final String KEY_HMAC = "hmac";
    public static final String KEY_VOICE_PORT = "voice_port";
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_ECHO_TIMESTAMP = "echo_timestamp";
    public static final String KEY_MUTED = "muted";
    public static final String KEY_ROUTE_NAME = "route_name";
    public static final String KEY_REASON = "reason";

    /**
     * Voice packet model representing decoded packet header and payload.
     */
    public static class VoicePacket {
        public byte type;
        public long sequenceNumber;
        public long timestamp;
        public byte[] payloadWithIv; // IV + Ciphertext

        public VoicePacket(byte type, long sequenceNumber, long timestamp, byte[] payloadWithIv) {
            this.type = type;
            this.sequenceNumber = sequenceNumber;
            this.timestamp = timestamp;
            this.payloadWithIv = payloadWithIv;
        }
    }

    /**
     * Encodes header and encrypted payload into a raw UDP byte array.
     */
    public static byte[] encodeVoicePacket(byte type, long sequenceNumber, long timestamp, byte[] encryptedPayloadWithIv) {
        ByteBuffer buffer = ByteBuffer.allocate(VOICE_HEADER_LENGTH + encryptedPayloadWithIv.length);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(VOICE_MAGIC);
        buffer.put(PROTOCOL_VERSION);
        buffer.put(type);
        buffer.putShort((short) 0); // Reserved flags
        buffer.putLong(sequenceNumber);
        buffer.putLong(timestamp);
        buffer.put(encryptedPayloadWithIv);
        return buffer.array();
    }

    /**
     * Decodes and validates header of a raw UDP packet.
     */
    public static VoicePacket decodeVoicePacket(byte[] rawData, int length) {
        if (length < VOICE_HEADER_LENGTH) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(rawData, 0, length);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int magic = buffer.getInt();
        if (magic != VOICE_MAGIC) {
            return null;
        }

        byte version = buffer.get();
        if (version != PROTOCOL_VERSION) {
            return null;
        }

        byte type = buffer.get();
        buffer.getShort(); // Reserved flags
        long sequenceNumber = buffer.getLong();
        long timestamp = buffer.getLong();

        int payloadLength = length - VOICE_HEADER_LENGTH;
        byte[] payloadWithIv = new byte[payloadLength];
        buffer.get(payloadWithIv);

        return new VoicePacket(type, sequenceNumber, timestamp, payloadWithIv);
    }

    // Helper builders for control messages
    public static JSONObject createHandshakeInit(String deviceName) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_HANDSHAKE_INIT);
        json.put(KEY_VERSION, PROTOCOL_VERSION);
        json.put(KEY_DEVICE_NAME, deviceName);
        return json;
    }

    public static JSONObject createAuthChallenge(String sessionId, String saltBase64, String challengeBase64) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_AUTH_CHALLENGE);
        json.put(KEY_SESSION_ID, sessionId);
        json.put(KEY_SALT, saltBase64);
        json.put(KEY_CHALLENGE, challengeBase64);
        return json;
    }

    public static JSONObject createAuthResponse(String hmacBase64, String challengeBase64, int voicePort) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_AUTH_RESPONSE);
        json.put(KEY_HMAC, hmacBase64);
        json.put(KEY_CHALLENGE, challengeBase64);
        json.put(KEY_VOICE_PORT, voicePort);
        return json;
    }

    public static JSONObject createAuthSuccess(String hmacBase64, int voicePort, String hostDeviceName) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_AUTH_SUCCESS);
        json.put(KEY_HMAC, hmacBase64);
        json.put(KEY_VOICE_PORT, voicePort);
        json.put(KEY_DEVICE_NAME, hostDeviceName);
        return json;
    }

    public static JSONObject createAuthFailure(String reason) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_AUTH_FAILURE);
        json.put(KEY_REASON, reason);
        return json;
    }

    public static JSONObject createHeartbeat() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_HEARTBEAT);
        json.put(KEY_TIMESTAMP, System.currentTimeMillis());
        return json;
    }

    public static JSONObject createHeartbeatAck(long echoTimestamp) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_HEARTBEAT_ACK);
        json.put(KEY_ECHO_TIMESTAMP, echoTimestamp);
        return json;
    }

    public static JSONObject createMuteUpdate(boolean isMuted) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_MUTE_UPDATE);
        json.put(KEY_MUTED, isMuted);
        return json;
    }

    public static JSONObject createAudioRouteUpdate(String routeName) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_AUDIO_ROUTE);
        json.put(KEY_ROUTE_NAME, routeName);
        return json;
    }

    public static JSONObject createDisconnect(String reason) throws JSONException {
        JSONObject json = new JSONObject();
        json.put(KEY_TYPE, MSG_DISCONNECT);
        json.put(KEY_REASON, reason);
        return json;
    }
}

