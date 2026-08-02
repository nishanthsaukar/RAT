package com.example.reverseshell2;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * This test class is ALREADY compatible with Android 12 — it runs on the JVM,
 * not on the device, so Android API level changes do not affect it.
 *
 * Added: a basic test for the Android 12 config defaults (IP, port, icon,
 * useForegroundService) to verify they are set to sensible values.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {

    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    // ------------------------------------------------------------------
    // Android 12 config sanity checks
    // ------------------------------------------------------------------

    @Test
    public void config_hasValidIP() {
        // The C2 server IP should not be null or empty.
        assertNotNull("config.IP must not be null", config.IP);
        assertFalse("config.IP must not be empty", config.IP.isEmpty());
    }

    @Test
    public void config_hasValidPort() {
        assertNotNull("config.port must not be null", config.port);
        assertFalse("config.port must not be empty", config.port.isEmpty());
        // Port should be numeric and in the valid range 1-65535.
        int portNum = Integer.parseInt(config.port);
        assertTrue("config.port must be between 1 and 65535",
                portNum >= 1 && portNum <= 65535);
    }

    @Test
    public void config_useForegroundService_isTrue() {
        // On Android 12, useForegroundService MUST be true for the app to
        // keep running in the background and start the camera / mic / location
        // foreground services.
        assertTrue("config.useForegroundService must be true on Android 12",
                config.useForegroundService);
    }

    @Test
    public void config_requestNotificationPermission_isTrue() {
        // On Android 13+, POST_NOTIFICATIONS must be requested at runtime so
        // the foreground-service notification is shown to the user.
        assertTrue("config.requestNotificationPermission should be true",
                config.requestNotificationPermission);
    }
}
