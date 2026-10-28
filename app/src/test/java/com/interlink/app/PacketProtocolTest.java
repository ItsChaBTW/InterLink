package com.interlink.app;

import com.interlink.app.network.PacketProtocol;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PacketProtocolTest {

    @Test
    public void testVoicePacketEncodeDecode() {
        byte type = PacketProtocol.TYPE_VOICE_PCM;
        long seq = 123456789L;
        long timestamp = 9876543210L;
        byte[] payload = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        byte[] encoded = PacketProtocol.encodeVoicePacket(type, seq, timestamp, payload);
        assertNotNull(encoded);
        assertEquals(PacketProtocol.VOICE_HEADER_LENGTH + payload.length, encoded.length);

        PacketProtocol.VoicePacket decoded = PacketProtocol.decodeVoicePacket(encoded, encoded.length);
        assertNotNull(decoded);
        assertEquals(type, decoded.type);
        assertEquals(seq, decoded.sequenceNumber);
        assertEquals(timestamp, decoded.timestamp);
        assertArrayEquals(payload, decoded.payloadWithIv);
    }

    @Test
    public void testDecodeInvalidMagicReturnsNull() {
        byte[] invalidData = new byte[PacketProtocol.VOICE_HEADER_LENGTH + 10];
        // Header bytes without magic
        PacketProtocol.VoicePacket packet = PacketProtocol.decodeVoicePacket(invalidData, invalidData.length);
        assertNull("Packet with incorrect magic must be rejected", packet);
    }

    @Test
    public void testDecodeShortBufferReturnsNull() {
        byte[] shortData = new byte[PacketProtocol.VOICE_HEADER_LENGTH - 1];
        PacketProtocol.VoicePacket packet = PacketProtocol.decodeVoicePacket(shortData, shortData.length);
        assertNull("Short buffer must return null", packet);
    }

    @Test
    public void testControlMessageSerialization() throws Exception {
        // Handshake Init
        JSONObject initMsg = PacketProtocol.createHandshakeInit("Pixel 7 Pro");
        assertEquals(PacketProtocol.MSG_HANDSHAKE_INIT, initMsg.getString(PacketProtocol.KEY_TYPE));
        assertEquals("Pixel 7 Pro", initMsg.getString(PacketProtocol.KEY_DEVICE_NAME));

        // Heartbeat
        JSONObject hbMsg = PacketProtocol.createHeartbeat();
        assertEquals(PacketProtocol.MSG_HEARTBEAT, hbMsg.getString(PacketProtocol.KEY_TYPE));
        long ts = hbMsg.getLong(PacketProtocol.KEY_TIMESTAMP);

        // Heartbeat Ack
        JSONObject hbAck = PacketProtocol.createHeartbeatAck(ts);
        assertEquals(PacketProtocol.MSG_HEARTBEAT_ACK, hbAck.getString(PacketProtocol.KEY_TYPE));
        assertEquals(ts, hbAck.getLong(PacketProtocol.KEY_ECHO_TIMESTAMP));

        // Mute update
        JSONObject muteMsg = PacketProtocol.createMuteUpdate(true);
        assertEquals(true, muteMsg.getBoolean(PacketProtocol.KEY_MUTED));
    }
}

