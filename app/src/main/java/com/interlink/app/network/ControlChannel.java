package com.interlink.app.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the TCP control socket connection between Host and Guest:
 * 1. Line-delimited JSON messaging.
 * 2. Handshake and mutual authentication.
 * 3. Keepalive heartbeats and RTT measurement.
 * 4. Heartbeat loss timeout detection.
 */
public class ControlChannel {
    private static final String TAG = "ControlChannel";
    private static final int SOCKET_TIMEOUT_MS = 6000;
    private static final int HEARTBEAT_INTERVAL_MS = 2000;
    private static final int HEARTBEAT_TIMEOUT_MS = 6000;

    public interface ControlCallback {
        void onConnected();
        void onMessage(JSONObject message);
        void onDisconnected(String reason);
        void onError(String error);
        void onRttMeasured(long rttMs);
    }

    private final ControlCallback callback;
    private final Handler mainHandler;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private Thread serverAcceptThread;
    private Thread readThread;
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatTask;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private long lastMessageReceivedTime = 0;

    public ControlChannel(ControlCallback callback) {
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Starts TCP server on the host.
     */
    public synchronized void startServer(final int port) {
        stop();
        isRunning.set(true);

        serverAcceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(port));
                    Log.d(TAG, "TCP Server listening on port " + port);

                    while (isRunning.get()) {
                        Socket incoming = serverSocket.accept();

                        // Strict 2-participant limit: if already connected, reject
                        if (isConnected.get()) {
                            Log.w(TAG, "Rejecting extra participant connection: " + incoming.getRemoteSocketAddress());
                            try {
                                BufferedWriter tmpWriter = new BufferedWriter(new OutputStreamWriter(incoming.getOutputStream(), StandardCharsets.UTF_8));
                                tmpWriter.write(PacketProtocol.createAuthFailure("SESSION_FULL").toString() + "\n");
                                tmpWriter.flush();
                                incoming.close();
                            } catch (Exception ignored) {
                            }
                            continue;
                        }

                        setupConnection(incoming);
                    }
                } catch (Exception e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Server socket accept error", e);
                        notifyError("Server socket error: " + e.getMessage());
                    }
                }
            }
        }, "InterLink-TCPAcceptor");
        serverAcceptThread.start();
    }

    /**
     * Connects as a guest to the host TCP control endpoint.
     */
    public synchronized void connectToHost(final String hostIp, final int port) {
        stop();
        isRunning.set(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "Connecting to host " + hostIp + ":" + port);
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(hostIp, port), SOCKET_TIMEOUT_MS);
                    setupConnection(socket);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to connect to host", e);
                    notifyError("Failed to connect to " + hostIp + ": " + e.getMessage());
                }
            }
        }, "InterLink-TCPConnector").start();
    }

    private synchronized void setupConnection(Socket socket) {
        try {
            this.clientSocket = socket;
            this.clientSocket.setTcpNoDelay(true);
            this.clientSocket.setKeepAlive(true);

            this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));

            isConnected.set(true);
            lastMessageReceivedTime = System.currentTimeMillis();

            startReadThread();
            startHeartbeats();

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onConnected();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed setting up connection", e);
            notifyError("Socket setup error: " + e.getMessage());
        }
    }

    private void startReadThread() {
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while (isRunning.get() && isConnected.get() && (line = reader.readLine()) != null) {
                        lastMessageReceivedTime = System.currentTimeMillis();
                        handleIncomingLine(line);
                    }
                } catch (Exception e) {
                    if (isRunning.get() && isConnected.get()) {
                        Log.w(TAG, "Read thread terminated: " + e.getMessage());
                    }
                } finally {
                    handleDisconnect("Connection closed by peer");
                }
            }
        }, "InterLink-TCPReader");
        readThread.start();
    }

    private void handleIncomingLine(String line) {
        try {
            final JSONObject json = new JSONObject(line);
            String type = json.optString(PacketProtocol.KEY_TYPE);

            if (PacketProtocol.MSG_HEARTBEAT.equals(type)) {
                // Respond immediately with ACK
                long ts = json.optLong(PacketProtocol.KEY_TIMESTAMP);
                sendMessage(PacketProtocol.createHeartbeatAck(ts));
                return;
            }

            if (PacketProtocol.MSG_HEARTBEAT_ACK.equals(type)) {
                long echoTs = json.optLong(PacketProtocol.KEY_ECHO_TIMESTAMP);
                final long rtt = System.currentTimeMillis() - echoTs;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onRttMeasured(rtt);
                        }
                    }
                });
                return;
            }

            // Deliver all other messages to upper layer
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onMessage(json);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse incoming JSON", e);
        }
    }

    /**
     * Sends a JSON control message over the socket.
     */
    public synchronized boolean sendMessage(final JSONObject message) {
        if (!isConnected.get() || writer == null) {
            return false;
        }

        try {
            writer.write(message.toString() + "\n");
            writer.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send message: " + message, e);
            handleDisconnect("Send error: " + e.getMessage());
            return false;
        }
    }

    private void startHeartbeats() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (!isConnected.get()) return;

                // Check heartbeat loss
                long elapsedSinceLastMsg = System.currentTimeMillis() - lastMessageReceivedTime;
                if (elapsedSinceLastMsg > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Heartbeat timeout! No response for " + elapsedSinceLastMsg + "ms");
                    handleDisconnect("Heartbeat timeout");
                    return;
                }

                // Send heartbeat
                try {
                    sendMessage(PacketProtocol.createHeartbeat());
                } catch (Exception ignored) {
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void handleDisconnect(final String reason) {
        if (!isConnected.compareAndSet(true, false)) {
            return;
        }

        cleanupSocket();

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onDisconnected(reason);
                }
            }
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    public synchronized void stop() {
        isRunning.set(false);
        isConnected.set(false);

        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }

        cleanupSocket();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }

        if (serverAcceptThread != null) {
            serverAcceptThread.interrupt();
            serverAcceptThread = null;
        }
    }

    private void cleanupSocket() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (Exception ignored) {
            }
            clientSocket = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
            reader = null;
        }
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
            writer = null;
        }
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
    }

    public boolean isConnected() {
        return isConnected.get();
    }
}

