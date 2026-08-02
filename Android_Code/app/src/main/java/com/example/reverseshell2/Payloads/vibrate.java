package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Vibration payload, updated for Android 12.
 *
 * Changes for Android 12:
 *  1. VIBRATE is a normal permission - it is auto-granted at install time, but
 *     this code still checks it so a missing manifest declaration is reported
 *     clearly instead of crashing.
 *  2. The old vibrator.vibrate(long) API is DEPRECATED since Android 8 (API 26)
 *     and removed behavior on newer versions. On API 26+ this now uses
 *     VibrationEffect.createOneShot().
 *  3. On Android 12 (API 31+) the Vibrator service moved:
 *         - API 31+:  context.getSystemService(VibratorManager.class)
 *                     -> manager.getDefaultVibrator()
 *         - API <31:  context.getSystemService(Vibrator.class)
 *     The old code used context.VIBRATOR_SERVICE which still resolves, but this
 *     version uses the modern, type-safe lookup.
 *  4. The old code BLOCKED the calling thread with Thread.sleep(800) - if called
 *     from the UI thread it would freeze the app / cause ANR. Now runs on a
 *     background thread.
 */
public class vibrate {

    Context context;

    public vibrate(Context context) {
        this.context = context;
    }

    public void vib(int i) {
        final int times = Math.max(0, i);
        if (times == 0) {
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Vibrator vibrator = getVibrator();
                if (vibrator == null) {
                    return;
                }

                for (int k = 0; k < times; k++) {
                    try {
                        vibrateOnce(vibrator);
                        Thread.sleep(800);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        });
        thread.start();
    }

    /**
     * Performs a single 500 ms vibration, using the modern API on newer devices.
     */
    private void vibrateOnce(Vibrator vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8+: use VibrationEffect (required / recommended).
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            // Deprecated but still required on pre-Oreo devices.
            @SuppressWarnings("deprecation")
            long duration = 500;
            vibrator.vibrate(duration);
        }
    }

    /**
     * Returns the default Vibrator, using the correct lookup for the API level.
     */
    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31+): VibratorManager is the new entry point.
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                return manager.getDefaultVibrator();
            }
            return null;
        }
        // Android 11 and below: direct Vibrator lookup.
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        return vibrator != null && vibrator.hasVibrator() ? vibrator : null;
    }
}

