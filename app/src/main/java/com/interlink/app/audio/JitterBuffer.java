package com.interlink.app.audio;


import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Low-latency adaptive jitter buffer for 20ms PCM audio packets (640 bytes each).
 * Reorders out-of-sequence arrivals, drops expired packets, and provides silence
 * concealment during packet loss to prevent AudioTrack underruns.
 */
public class JitterBuffer {
    private static final String TAG = "JitterBuffer";

    public static final int FRAME_SIZE_BYTES = 640; // 20ms @ 16kHz 16-bit mono
    private static final int TARGET_BUFFER_FRAMES = 3; // ~60ms latency
    private static final int MAX_BUFFER_FRAMES = 10;   // ~200ms max backlog

    public static class Frame {
        public final long sequenceNumber;
        public final long timestamp;
        public final byte[] pcmData;

        public Frame(long sequenceNumber, long timestamp, byte[] pcmData) {
            this.sequenceNumber = sequenceNumber;
            this.timestamp = timestamp;
            this.pcmData = pcmData;
        }
    }

    private final PriorityQueue<Frame> queue;
    private final byte[] silenceFrame;
    private long nextPlaySequence = -1;
    private boolean isBuffering = true;

    // Metrics
    private long underrunCount = 0;
    private long droppedLateCount = 0;

    public JitterBuffer() {
        this.queue = new PriorityQueue<>(MAX_BUFFER_FRAMES, new Comparator<Frame>() {
            @Override
            public int compare(Frame a, Frame b) {
                return Long.compare(a.sequenceNumber, b.sequenceNumber);
            }
        });
        this.silenceFrame = new byte[FRAME_SIZE_BYTES];
        Arrays.fill(silenceFrame, (byte) 0);
    }

    /**
     * Enqueues an incoming decrypted PCM audio frame.
     */
    public synchronized void put(long sequenceNumber, long timestamp, byte[] pcmData) {
        if (pcmData == null || pcmData.length != FRAME_SIZE_BYTES) {
            return;
        }

        // If packet is older than current playout sequence, drop as too late
        if (nextPlaySequence != -1 && sequenceNumber < nextPlaySequence) {
            droppedLateCount++;
            return;
        }

        // Avoid duplicates
        for (Frame f : queue) {
            if (f.sequenceNumber == sequenceNumber) {
                return;
            }
        }

        // If queue exceeds max limit, purge oldest
        if (queue.size() >= MAX_BUFFER_FRAMES) {
            Frame purged = queue.poll();
            if (purged != null && nextPlaySequence != -1) {
                nextPlaySequence = Math.max(nextPlaySequence, purged.sequenceNumber + 1);
            }
        }

        queue.offer(new Frame(sequenceNumber, timestamp, pcmData));

        // When prebuffering, release buffer once target depth is achieved
        if (isBuffering && queue.size() >= TARGET_BUFFER_FRAMES) {
            isBuffering = false;
            Frame head = queue.peek();
            if (head != null && nextPlaySequence == -1) {
                nextPlaySequence = head.sequenceNumber;
            }
        }
    }

    /**
     * Retrieves the next 20ms PCM audio frame for AudioTrack playback.
     * If the exact expected sequence is missing, returns comfort silence.
     */
    public synchronized byte[] get() {
        if (isBuffering) {
            return silenceFrame;
        }

        if (queue.isEmpty()) {
            isBuffering = true; // Enter buffering mode on total underrun
            underrunCount++;
            return silenceFrame;
        }

        Frame head = queue.peek();
        if (head == null) {
            return silenceFrame;
        }

        if (nextPlaySequence == -1) {
            nextPlaySequence = head.sequenceNumber;
        }

        if (head.sequenceNumber == nextPlaySequence) {
            // Perfect match in sequence
            queue.poll();
            nextPlaySequence++;
            return head.pcmData;
        } else if (head.sequenceNumber > nextPlaySequence) {
            // Missing packet (packet loss); return silence concealment frame
            nextPlaySequence++;
            return silenceFrame;
        } else {
            // Outdated packet in queue, discard and retry
            queue.poll();
            return get();
        }
    }

    public synchronized void reset() {
        queue.clear();
        nextPlaySequence = -1;
        isBuffering = true;
    }

    public synchronized int getQueueSize() {
        return queue.size();
    }

    public long getUnderrunCount() {
        return underrunCount;
    }

    public long getDroppedLateCount() {
        return droppedLateCount;
    }
}
