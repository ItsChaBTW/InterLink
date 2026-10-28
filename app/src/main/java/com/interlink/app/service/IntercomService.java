package com.interlink.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.interlink.app.R;
import com.interlink.app.SessionActivity;
import com.interlink.app.model.PeerDevice;
import com.interlink.app.model.SessionState;
import com.interlink.app.network.SessionManager;

/**
 * Foreground Service maintaining audio capture, playback, and network transport
 * during screen-off operation with Android while-in-use microphone permissions.
 */
public class IntercomService extends Service implements SessionManager.SessionObserver {
    private static final String TAG = "IntercomService";
    private static final String CHANNEL_ID = "interlink_intercom_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_FOREGROUND = "com.interlink.app.action.START_FOREGROUND";
    public static final String ACTION_STOP_FOREGROUND = "com.interlink.app.action.STOP_FOREGROUND";
    public static final String ACTION_TOGGLE_MUTE = "com.interlink.app.action.TOGGLE_MUTE";
    public static final String ACTION_END_SESSION = "com.interlink.app.action.END_SESSION";

    public class LocalBinder extends Binder {
        public IntercomService getService() {
            return IntercomService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private SessionManager sessionManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "IntercomService created");
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        sessionManager = SessionManager.getInstance(this);
        sessionManager.registerObserver(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "onStartCommand action: " + action);

            if (ACTION_START_FOREGROUND.equals(action)) {
                startForegroundServiceInternal();
            } else if (ACTION_TOGGLE_MUTE.equals(action)) {
                sessionManager.toggleMute();
            } else if (ACTION_END_SESSION.equals(action)) {
                sessionManager.endSession();
                stopForegroundServiceInternal();
            } else if (ACTION_STOP_FOREGROUND.equals(action)) {
                stopForegroundServiceInternal();
            }
        }
        return START_NOT_STICKY;
    }

    private void startForegroundServiceInternal() {
        acquireWakeLock();
        Notification notification = buildNotification("Connecting to partner...", false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundServiceInternal() {
        releaseWakeLock();
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String statusText, boolean isMuted) {
        Intent sessionIntent = new Intent(this, SessionActivity.class);
        sessionIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 0, sessionIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Mute / Unmute action
        Intent muteIntent = new Intent(this, IntercomService.class);
        muteIntent.setAction(ACTION_TOGGLE_MUTE);
        PendingIntent mutePendingIntent = PendingIntent.getService(
                this, 1, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // End session action
        Intent endIntent = new Intent(this, IntercomService.class);
        endIntent.setAction(ACTION_END_SESSION);
        PendingIntent endPendingIntent = PendingIntent.getService(
                this, 2, endIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String muteActionTitle = isMuted ? getString(R.string.notif_action_unmute) : getString(R.string.notif_action_mute);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title_active))
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_logo_interlink)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_mic, muteActionTitle, mutePendingIntent)
                .addAction(R.drawable.ic_call_end, getString(R.string.notif_action_end), endPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String statusText, boolean isMuted) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText, isMuted));
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InterLink:IntercomWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(4 * 60 * 60 * 1000L); // 4 hours maximum timeout safety guard
            Log.d(TAG, "Partial wake lock acquired");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "Partial wake lock released");
        }
    }

    @Override
    public void onStateChanged(SessionState state, String message) {
        if (state == SessionState.CONNECTED) {
            PeerDevice peer = sessionManager.getCurrentPeer();
            String name = (peer != null) ? peer.getDeviceName() : "Partner";
            updateNotification("Connected to " + name + " • Full Duplex", sessionManager.isMuted());
        } else if (state == SessionState.RECONNECTING) {
            updateNotification("Reconnecting to riding partner...", sessionManager.isMuted());
        } else if (state == SessionState.DISCONNECTED || state == SessionState.ERROR || state == SessionState.IDLE) {
            stopForegroundServiceInternal();
        }
    }

    @Override
    public void onPeerUpdated(PeerDevice peer) {
        if (sessionManager.getCurrentState() == SessionState.CONNECTED) {
            String route = sessionManager.getCurrentAudioRouteName();
            updateNotification(peer.getDeviceName() + " (" + peer.getRoundTripTimeMs() + "ms RTT) • " + route, sessionManager.isMuted());
        }
    }

    @Override
    public void onAudioRouteChanged(String routeName, boolean isBluetooth) {
        PeerDevice peer = sessionManager.getCurrentPeer();
        String name = (peer != null) ? peer.getDeviceName() : "Partner";
        updateNotification("Connected to " + name + " • " + routeName, sessionManager.isMuted());
    }

    @Override
    public void onMuteStateChanged(boolean isMuted) {
        PeerDevice peer = sessionManager.getCurrentPeer();
        String name = (peer != null) ? peer.getDeviceName() : "Partner";
        String status = isMuted ? "MUTED" : "LIVE";
        updateNotification("Mic " + status + " • Connected to " + name, isMuted);
    }

    @Override
    public void onError(String errorMessage) {
        // Handled in state change
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "IntercomService destroyed");
        sessionManager.unregisterObserver(this);
        releaseWakeLock();
    }
}

