package com.interlink.app.network;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles offline peer discovery and local network interface / IP resolution.
 */
public class HostDiscovery {
    private static final String TAG = "HostDiscovery";
    private static final String BEACON_PREFIX = "INTERLINK_BEACON";

    public interface DiscoveryListener {
        void onHostDiscovered(String deviceName, String ipAddress, int controlPort, String sessionId);
    }

    private final Context context;
    private final Handler mainHandler;
    private WifiManager.MulticastLock multicastLock;

    private DatagramSocket broadcastSocket;
    private DatagramSocket listenerSocket;
    private Thread broadcastThread;
    private Thread listenerThread;
    private final AtomicBoolean isBroadcasting = new AtomicBoolean(false);
    private final AtomicBoolean isListening = new AtomicBoolean(false);

    public HostDiscovery(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Resolves the primary local IPv4 address (e.g. on wlan0, ap0, etc.).
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "127.0.0.1";

            String candidate = null;
            for (NetworkInterface nif : Collections.list(interfaces)) {
                if (nif.isLoopback() || !nif.isUp()) continue;

                String name = nif.getName().toLowerCase();
                boolean isWirelessOrAp = name.contains("wlan") || name.contains("ap") ||
                                         name.contains("swlan") || name.contains("rndis");

                for (InterfaceAddress addr : nif.getInterfaceAddresses()) {
                    InetAddress inet = addr.getAddress();
                    if (inet instanceof Inet4Address && !inet.isLoopbackAddress()) {
                        String ip = inet.getHostAddress();
                        if (isWirelessOrAp) {
                            return ip; // Prioritize AP or WLAN
                        }
                        if (candidate == null) {
                            candidate = ip;
                        }
                    }
                }
            }
            if (candidate != null) return candidate;
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve local IP address", e);
        }
        return "127.0.0.1";
    }

    /**
     * Attempts to resolve the gateway IP address (which is typically the host hotspot IP for a guest).
     */
    public String getGatewayIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    int ip = dhcp.gateway;
                    return String.format("%d.%d.%d.%d",
                            (ip & 0xff),
                            (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff),
                            (ip >> 24 & 0xff));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query DHCP gateway", e);
        }
        return "192.168.43.1"; // Standard Android Hotspot gateway fallback
    }

    /**
     * Begins periodic UDP broadcast announcements on the local subnet.
     */
    public synchronized void startBroadcasting(final String deviceName, final int controlPort, final String sessionId) {
        stopBroadcasting();
        isBroadcasting.set(true);

        broadcastThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    broadcastSocket = new DatagramSocket();
                    broadcastSocket.setBroadcast(true);

                    String payload = BEACON_PREFIX + ":" + deviceName + ":" + controlPort + ":" + sessionId;
                    byte[] data = payload.getBytes("UTF-8");

                    while (isBroadcasting.get()) {
                        List<InetAddress> broadcastTargets = getBroadcastAddresses();
                        for (InetAddress target : broadcastTargets) {
                            try {
                                DatagramPacket packet = new DatagramPacket(data, data.length, target, PacketProtocol.DISCOVERY_PORT);
                                broadcastSocket.send(packet);
                            } catch (Exception e) {
                                // Silently handle single broadcast target failure
                            }
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                    Log.e(TAG, "Broadcasting error", e);
                } finally {
                    if (broadcastSocket != null && !broadcastSocket.isClosed()) {
                        broadcastSocket.close();
                    }
                }
            }
        }, "InterLink-DiscoveryBroadcaster");
        broadcastThread.start();
    }

    public synchronized void stopBroadcasting() {
        isBroadcasting.set(false);
        if (broadcastSocket != null) {
            broadcastSocket.close();
        }
        if (broadcastThread != null) {
            broadcastThread.interrupt();
            broadcastThread = null;
        }
    }

    /**
     * Starts listening for host beacons on the discovery port.
     */
    public synchronized void startListening(final DiscoveryListener listener) {
        stopListening();
        acquireMulticastLock();
        isListening.set(true);

        listenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[1024];
                try {
                    listenerSocket = new DatagramSocket(PacketProtocol.DISCOVERY_PORT);
                    listenerSocket.setBroadcast(true);

                    while (isListening.get()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        listenerSocket.receive(packet);

                        String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                        if (message.startsWith(BEACON_PREFIX)) {
                            String[] parts = message.split(":");
                            if (parts.length >= 4) {
                                final String hostName = parts[1];
                                final int port = Integer.parseInt(parts[2]);
                                final String sId = parts[3];
                                final String hostIp = packet.getAddress().getHostAddress();

                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (listener != null) {
                                            listener.onHostDiscovered(hostName, hostIp, port, sId);
                                        }
                                    }
                                });
                            }
                        }
                    }
                } catch (SocketException se) {
                    // Normal during stop
                } catch (Exception e) {
                    Log.e(TAG, "Discovery listener error", e);
                } finally {
                    if (listenerSocket != null && !listenerSocket.isClosed()) {
                        listenerSocket.close();
                    }
                    releaseMulticastLock();
                }
            }
        }, "InterLink-DiscoveryListener");
        listenerThread.start();
    }

    public synchronized void stopListening() {
        isListening.set(false);
        if (listenerSocket != null) {
            listenerSocket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        releaseMulticastLock();
    }

    private List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nif : Collections.list(interfaces)) {
                    if (nif.isLoopback() || !nif.isUp()) continue;
                    for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                        InetAddress broadcast = ifAddr.getBroadcast();
                        if (broadcast != null) {
                            list.add(broadcast);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enumerating broadcast addresses", e);
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        return list;
    }

    private void acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("InterLink_MulticastLock");
                    multicastLock.setReferenceCounted(true);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire multicast lock", e);
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release multicast lock", e);
        }
    }
}

