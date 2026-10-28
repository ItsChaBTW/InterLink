package com.interlink.app.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.interlink.app.audio.JitterBuffer;
import com.interlink.app.security.SessionSecurity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages low-latency full-duplex UDP transmission and reception of encrypted voice packets.
 */
public class VoiceTransport {
    private static final String TAG = "VoiceTransport";
    private static final int MAX_UDP_PACKET_SIZE = 1500;
    private static final int SEND_QUEUE_CAPACITY = 20; // 400ms max backlog

    public interface TelemetryListener {
        void onTelemetryUpdate(long jitterMs, float lossPercent);
    }

    private final SessionSecurity sessionSecurity;
    private final JitterBuffer jitterBuffer;
    private final Handler mainHandler;
    private TelemetryListener telemetryListener;

    private DatagramSocket datagramSocket;
    private InetAddress remoteAddress;
    private int remoteVoicePort;

    private Thread receiveThread;
    private Thread sendThread;
    private final BlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final AtomicLong outgoingSeq = new AtomicLong(0);

    // RFC 3550 Jitter and Loss calculation
    private long lastTransitTime = 0;
    private double estimatedJitter = 0.0;
    private long totalExpectedPackets = 0;
    private long totalReceivedPackets = 0;
    private long lastSeqReceived = -1;

    public VoiceTransport(SessionSecurity sessionSecurity, JitterBuffer jitterBuffer) {
        this.sessionSecurity = sessionSecurity;
        this.jitterBuffer = jitterBuffer;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setTelemetryListener(TelemetryListener listener) {
        this.telemetryListener = listener;
    }

    /**
     * Starts UDP voice transport bound to a local port.
     */
    public synchronized int start(int preferredPort, String peerIp, int peerVoicePort) throws Exception {
        stop();
        isRunning.set(true);

        this.remoteAddress = InetAddress.getByName(peerIp);
        this.remoteVoicePort = peerVoicePort;

        // Attempt binding to preferred port, fallback to any available port
        try {
            datagramSocket = new DatagramSocket(preferredPort);
        } catch (SocketException e) {
            Log.w(TAG, "Preferred voice port " + preferredPort + " busy, binding to ephemeral port");
            datagramSocket = new DatagramSocket();
        }

        datagramSocket.setSendBufferSize(64 * 1024);
        datagramSocket.setReceiveBufferSize(64 * 1024);

        int boundPort = datagramSocket.getLocalPort();
        Log.d(TAG, "VoiceTransport bound to UDP port " + boundPort + " targeting " + peerIp + ":" + peerVoicePort);

        startReceiver();
        startSender();

        return boundPort;
    }

    /**
     * Updates target peer IP and port (e.g. after handshake negotiation).
     */
    public synchronized void updatePeerEndpoint(String peerIp, int peerVoicePort) {
        try {
            this.remoteAddress = InetAddress.getByName(peerIp);
            this.remoteVoicePort = peerVoicePort;
            Log.d(TAG, "Updated remote voice endpoint to " + peerIp + ":" + peerVoicePort);
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve remote voice address: " + peerIp, e);
        }
    }

    /**
     * Enqueues an unencrypted PCM audio frame (640 bytes) to be encrypted and sent.
     */
    public boolean sendAudioFrame(byte[] pcmData, int offset, int length) {
        if (!isRunning.get() || remoteAddress == null || remoteVoicePort <= 0) {
            return false;
        }

        try {
            long seq = outgoingSeq.getAndIncrement();
            long now = System.currentTimeMillis();

            // Encrypt frame
            byte[] encryptedWithIv = sessionSecurity.encryptVoiceFrame(seq, pcmData, offset, length);

            // Encode packet header + payload
            byte[] packetBytes = PacketProtocol.encodeVoicePacket(
                    PacketProtocol.TYPE_VOICE_PCM,
                    seq,
                    now,
                    encryptedWithIv
            );

            // Non-blocking offer; if full, drop oldest to avoid latency buildup
            if (!sendQueue.offer(packetBytes)) {
                sendQueue.poll();
                sendQueue.offer(packetBytes);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to encode and enqueue voice frame", e);
            return false;
        }
    }

    private void startSender() {
        sendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning.get()) {
                    try {
                        byte[] packetData = sendQueue.take();
                        if (remoteAddress != null && remoteVoicePort > 0 && datagramSocket != null && !datagramSocket.isClosed()) {
                            DatagramPacket packet = new DatagramPacket(
                                    packetData,
                                    packetData.length,
                                    remoteAddress,
                                    remoteVoicePort
                            );
                            datagramSocket.send(packet);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Socket send error", e);
                        }
                    }
                }
            }
        }, "InterLink-VoiceSender");
        sendThread.start();
    }

    private void startReceiver() {
        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] recvBuffer = new byte[MAX_UDP_PACKET_SIZE];
                while (isRunning.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(recvBuffer, recvBuffer.length);
                        datagramSocket.receive(packet);

                        long receiveTime = System.currentTimeMillis();
                        PacketProtocol.VoicePacket decoded = PacketProtocol.decodeVoicePacket(packet.getData(), packet.getLength());

                        if (decoded == null) {
                            continue;
                        }

                        // Decrypt payload
                        try {
                            byte[] pcmAudio = sessionSecurity.decryptVoiceFrame(
                                    decoded.sequenceNumber,
                                    decoded.payloadWithIv,
                                    0,
                                    decoded.payloadWithIv.length
                            );

                            // Push to JitterBuffer for smooth playback
                            jitterBuffer.put(decoded.sequenceNumber, decoded.timestamp, pcmAudio);

                            // Calculate RFC 3550 Telemetry
                            updateTelemetryMetrics(decoded.sequenceNumber, decoded.timestamp, receiveTime);
                        } catch (SecurityException se) {
                            // Replay or tampered packet, dropped silently
                        } catch (Exception de) {
                            Log.w(TAG, "Decryption error on packet seq=" + decoded.sequenceNumber);
                        }

                    } catch (SocketException se) {
                        break; // Normal during stop
                    } catch (Exception e) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Voice receive error", e);
                        }
                    }
                }
            }
        }, "InterLink-VoiceReceiver");
        receiveThread.start();
    }

    private void updateTelemetryMetrics(long seq, long sendTime, long recvTime) {
        totalReceivedPackets++;
        if (lastSeqReceived != -1) {
            long diff = seq - lastSeqReceived;
            if (diff > 0) {
                totalExpectedPackets += diff;
            }
        } else {
            totalExpectedPackets = 1;
        }
        lastSeqReceived = seq;

        // RFC 3550 Interarrival Jitter calculation:
        // D(i,j) = (R_j - S_j) - (R_i - S_i)
        // J = J + (|D| - J) / 16
        long transit = recvTime - sendTime;
        if (lastTransitTime != 0) {
            long d = Math.abs(transit - lastTransitTime);
            estimatedJitter = estimatedJitter + ((double) d - estimatedJitter) / 16.0;
        }
        lastTransitTime = transit;

        if (totalReceivedPackets % 50 == 0) {
            final long jitterMs = Math.round(estimatedJitter);
            long lost = Math.max(0, totalExpectedPackets - totalReceivedPackets);
            final float lossPercent = totalExpectedPackets > 0 ? (lost * 100.0f) / totalExpectedPackets : 0.0f;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (telemetryListener != null) {
                        telemetryListener.onTelemetryUpdate(jitterMs, lossPercent);
                    }
                }
            });
        }
    }

    public synchronized void stop() {
        isRunning.set(false);
        if (datagramSocket != null) {
            datagramSocket.close();
            datagramSocket = null;
        }
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        sendQueue.clear();
        outgoingSeq.set(0);
        lastSeqReceived = -1;
        totalExpectedPackets = 0;
        totalReceivedPackets = 0;
    }
}

