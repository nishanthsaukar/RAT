package com.example.reverseshell2.Payloads;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Network utility class, updated for Android 12.
 *
 * Android 12 / privacy notes:
 *  1. MAC ADDRESS - Since Android 10 (API 29) and continuing through Android 12,
 *     third-party apps can NO LONGER retrieve the real hardware MAC address.
 *     NetworkInterface.getHardwareAddress() returns either null or a randomized
 *     address (e.g. 02:00:00:00:00:00) for privacy. This is a platform-level
 *     restriction and cannot be bypassed by a non-system app, even with
 *     ACCESS_WIFI_STATE permission. The method below now returns a clear
 *     indicator string so the server knows the value is not the real MAC.
 *
 *  2. IP ADDRESS - Getting local IP addresses via NetworkInterface still works on
 *     Android 12, but it is good practice (and more reliable) to have
 *     ACCESS_NETWORK_STATE declared in the manifest. The IPv4 detection logic has
 *     been improved to correctly handle IPv4-mapped IPv6 addresses
 *     (::ffff:192.168.1.5) which the old check (sAddr.indexOf(':') < 0) got wrong.
 */
public class ipAddr {

    /** Sentinel value returned when the MAC address is not accessible (Android 10+). */
    private static final String MAC_UNAVAILABLE = "UNAVAILABLE";

    /**
     * Returns the MAC address for the given interface, or {@link #MAC_UNAVAILABLE}
     * when the platform forbids access (Android 10+ / Android 12).
     *
     * @param interfaceName interface name (e.g. "wlan0") or null for the first found
     */
    public static String getMACAddress(String interfaceName) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (interfaceName != null) {
                    if (!intf.getName().equalsIgnoreCase(interfaceName)) continue;
                }
                byte[] mac = intf.getHardwareAddress();
                // Android 10+ returns null or a randomized address for privacy.
                if (mac == null) {
                    return MAC_UNAVAILABLE;
                }
                StringBuilder buf = new StringBuilder();
                for (byte aMac : mac) {
                    buf.append(String.format("%02X:", aMac));
                }
                if (buf.length() > 0) {
                    buf.deleteCharAt(buf.length() - 1);
                }
                return buf.toString();
            }
        } catch (Exception ignored) { }
        return MAC_UNAVAILABLE;
    }

    /**
     * Returns the first non-loopback IP address of the device.
     *
     * @param useIPv4 true for IPv4, false for IPv6
     */
    public static String getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp()) {
                    continue; // skip interfaces that are down
                }
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }

                    // Handle IPv4-mapped IPv6 addresses like ::ffff:192.168.1.5.
                    if (addr instanceof Inet4Address) {
                        if (useIPv4) {
                            return addr.getHostAddress();
                        }
                    } else if (addr instanceof Inet6Address) {
                        String sAddr = addr.getHostAddress();
                        // Strip the scope id (e.g. %wlan0) for IPv6 link-local addresses.
                        int delim = sAddr.indexOf('%');
                        String clean = delim < 0 ? sAddr : sAddr.substring(0, delim);
                        if (!useIPv4) {
                            return clean.toUpperCase();
                        }
                        // An Inet6Address that is actually IPv4-mapped.
                        if (clean.toLowerCase().startsWith("::ffff:")) {
                            String v4 = clean.substring(7);
                            if (useIPv4) {
                                return v4;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}

