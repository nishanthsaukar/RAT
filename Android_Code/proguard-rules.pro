# ============================================================
# ProGuard / R8 rules for Android 12 (API 31)
#
# The app uses foreground services (mainService, CameraService,
# audioManager, videoRecorder, screenRecorder) that are started
# via Intent with explicit class references. ProGuard can
# rename/obfuscate these class names, causing runtime crashes.
#
# Android 12 changes:
# 1. ALL foreground service classes must be KEPT (not renamed)
#    because they are referenced by name in AndroidManifest.xml
#    and started via Intent.
#
# 2. The CameraService and CameraPreview use Camera2 API classes
#    (CameraManager, CameraDevice, etc.) - these are Android
#    framework classes and are automatically kept, but the
#    camera callback inner classes must be preserved.
#
# 3. MediaRecorder, MediaProjection, and MediaCodec are Android
#    framework classes - automatically kept.
#
# 4. Socket/IO classes (OutputStream, BufferedReader, etc.) are
#    Java SE classes - automatically kept.
# ============================================================

# ============================================================
# Keep ALL service classes (started via Intent / manifest)
# ============================================================
-keep class com.example.reverseshell2.mainService { *; }
-keep class com.example.reverseshell2.jobScheduler { *; }

-keep class com.example.reverseshell2.Payloads.CameraService { *; }
-keep class com.example.reverseshell2.Payloads.CameraPreview { *; }
-keep class com.example.reverseshell2.Payloads.audioManager { *; }
-keep class com.example.reverseshell2.Payloads.videoRecorder { *; }
-keep class com.example.reverseshell2.Payloads.screenRecorder { *; }
-keep class com.example.reverseshell2.Payloads.ScreenRecordActivity { *; }

# ============================================================
# Keep broadcast receivers and activities (manifest references)
# ============================================================
-keep class com.example.reverseshell2.MainActivity { *; }
-keep class com.example.reverseshell2.controlPanel { *; }
-keep class com.example.reverseshell2.broadcastReciever { *; }
-keep class com.example.reverseshell2.keypadListner { *; }

# ============================================================
# Keep the tcpConnection class (AsyncTask, accessed via .execute)
# ============================================================
-keep class com.example.reverseshell2.tcpConnection { *; }

# ============================================================
# Keep the jumper and functions utility classes
# ============================================================
-keep class com.example.reverseshell2.jumper { *; }
-keep class com.example.reverseshell2.functions { *; }

# ============================================================
# Keep config class (static fields accessed directly)
# ============================================================
-keep class com.example.reverseshell2.config { *; }

# ============================================================
# Keep payload helper classes
# ============================================================
-keep class com.example.reverseshell2.Payloads.ipAddr { *; }
-keep class com.example.reverseshell2.Payloads.locationManager { *; }
-keep class com.example.reverseshell2.Payloads.readSMS { *; }
-keep class com.example.reverseshell2.Payloads.readCallLogs { *; }
-keep class com.example.reverseshell2.Payloads.vibrate { *; }
-keep class com.example.reverseshell2.Payloads.newShell { *; }

# ============================================================
# Android 12: keep inner classes used as callbacks
# ============================================================
-keepclassmembers class com.example.reverseshell2.Payloads.CameraPreview {
    *;
}
-keepclassmembers class com.example.reverseshell2.Payloads.videoRecorder {
    *;
}
-keepclassmembers class com.example.reverseshell2.Payloads.screenRecorder {
    *;
}

# ============================================================
# Keep the static OutputStream field (tcpConnection.out) used
# by all payload services to write data back to the socket
# ============================================================
-keepclassmembers class com.example.reverseshell2.tcpConnection {
    public static java.io.OutputStream out;
}

# ============================================================
# Keep screenRecorder static fields (RESULT_CODE, RESULT_DATA)
# that are set by ScreenRecordActivity and read by the service
# ============================================================
-keepclassmembers class com.example.reverseshell2.Payloads.screenRecorder {
    public static int RESULT_CODE;
    public static android.content.Intent RESULT_DATA;
    public static boolean consentPending;
}

# ============================================================
# General Android 12 recommendations
# ============================================================

# Keep the R (resources) class (used by notification icons)
-keep class **.R
-keep class **.R$* { *; }

# Keep Serializable/Parcelable classes
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep enum classes (used in some Android 12 APIs)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep annotations (used by Android 12 annotation-based APIs)
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
