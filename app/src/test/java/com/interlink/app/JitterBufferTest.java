package com.interlink.app;

import com.interlink.app.audio.JitterBuffer;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class JitterBufferTest {

    private JitterBuffer jitterBuffer;

    @Before
    public void setUp() {
        jitterBuffer = new JitterBuffer();
    }

    private byte[] createTestFrame(byte marker) {
        byte[] frame = new byte[JitterBuffer.FRAME_SIZE_BYTES];
        Arrays.fill(frame, marker);
        return frame;
    }

    @Test
    public void testInOrderBufferingAndPlayout() {
        byte[] frame0 = createTestFrame((byte) 0x11);
        byte[] frame1 = createTestFrame((byte) 0x22);
        byte[] frame2 = createTestFrame((byte) 0x33);
        byte[] frame3 = createTestFrame((byte) 0x44);

        // Put initial target frames (3 frames to satisfy prebuffering)
        jitterBuffer.put(0, 1000, frame0);
        jitterBuffer.put(1, 1020, frame1);
        jitterBuffer.put(2, 1040, frame2);

        // Now playout should release frame 0, 1, 2 in order
        assertArrayEquals(frame0, jitterBuffer.get());
        assertArrayEquals(frame1, jitterBuffer.get());

        // Enqueue next frame
        jitterBuffer.put(3, 1060, frame3);
        assertArrayEquals(frame2, jitterBuffer.get());
        assertArrayEquals(frame3, jitterBuffer.get());
    }

    @Test
    public void testOutOfOrderReordering() {
        byte[] frame0 = createTestFrame((byte) 0x10);
        byte[] frame1 = createTestFrame((byte) 0x20);
        byte[] frame2 = createTestFrame((byte) 0x30);

        // Arrive out of order: frame 1, frame 0, frame 2
        jitterBuffer.put(1, 1020, frame1);
        jitterBuffer.put(0, 1000, frame0);
        jitterBuffer.put(2, 1040, frame2);

        // Playout must reorder them correctly: 0, then 1, then 2
        assertArrayEquals(frame0, jitterBuffer.get());
        assertArrayEquals(frame1, jitterBuffer.get());
        assertArrayEquals(frame2, jitterBuffer.get());
    }

    @Test
    public void testPacketLossSilenceConcealment() {
        byte[] frame0 = createTestFrame((byte) 0x01);
        // frame 1 is lost!
        byte[] frame2 = createTestFrame((byte) 0x03);
        byte[] frame3 = createTestFrame((byte) 0x04);

        jitterBuffer.put(0, 1000, frame0);
        jitterBuffer.put(2, 1040, frame2);
        jitterBuffer.put(3, 1060, frame3);

        // Playout: frame 0
        assertArrayEquals(frame0, jitterBuffer.get());

        // Playout for missing frame 1 should return comfort silence
        byte[] silence = jitterBuffer.get();
        assertNotNull(silence);
        assertEquals(JitterBuffer.FRAME_SIZE_BYTES, silence.length);
        assertEquals(0, silence[0]); // All zeros

        // Playout continues with frame 2
        assertArrayEquals(frame2, jitterBuffer.get());
    }

    @Test
    public void testDuplicatePacketsDiscarded() {
        byte[] frame0 = createTestFrame((byte) 0x0A);
        byte[] frame1 = createTestFrame((byte) 0x0B);

        jitterBuffer.put(0, 1000, frame0);
        jitterBuffer.put(0, 1000, frame0); // Duplicate!
        jitterBuffer.put(1, 1020, frame1);
        jitterBuffer.put(1, 1020, frame1); // Duplicate!

        assertEquals("Duplicates must not increase queue count", 2, jitterBuffer.getQueueSize());
    }
}

