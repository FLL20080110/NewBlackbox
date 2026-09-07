package top.niunaijun.blackboxa.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.Arrays;

import top.niunaijun.blackbox.entity.location.BLocation;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;

/**
 * Narrow IPC bridge used by the companion LocationSpoofer app to control the
 * container-wide virtual location. No guest application needs to be configured
 * individually.
 */
public final class LocationBridgeProvider extends ContentProvider {

    public static final String AUTHORITY = "top.niunaijun.blackbox.locationbridge";
    public static final String METHOD_PING = "ping";
    public static final String METHOD_SET_LOCATION = "set_location";
    public static final String METHOD_CLEAR_LOCATION = "clear_location";
    public static final String METHOD_GET_STATE = "get_state";

    private static final String ALLOWED_CONTROLLER_PACKAGE = "com.suseoaa.locationspoofer";
    private static final int BRIDGE_VERSION = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceAllowedCaller();

        Bundle result = new Bundle();
        result.putInt("bridge_version", BRIDGE_VERSION);

        if (METHOD_PING.equals(method) || METHOD_GET_STATE.equals(method)) {
            BLocation location = BLocationManager.get().getGlobalLocation();
            result.putBoolean("enabled", location != null);
            if (location != null) {
                result.putDouble("latitude", location.getLatitude());
                result.putDouble("longitude", location.getLongitude());
            }
            return result;
        }

        if (METHOD_SET_LOCATION.equals(method)) {
            if (extras == null || !extras.containsKey("latitude") || !extras.containsKey("longitude")) {
                throw new IllegalArgumentException("latitude and longitude are required");
            }

            double latitude = extras.getDouble("latitude");
            double longitude = extras.getDouble("longitude");
            validateCoordinates(latitude, longitude);

            BLocationManager.get().setContainerLocation(new BLocation(latitude, longitude));
            result.putBoolean("enabled", true);
            result.putDouble("latitude", latitude);
            result.putDouble("longitude", longitude);
            return result;
        }

        if (METHOD_CLEAR_LOCATION.equals(method)) {
            BLocationManager.get().clearContainerLocation();
            result.putBoolean("enabled", false);
            return result;
        }

        throw new IllegalArgumentException("Unknown bridge method: " + method);
    }

    private void enforceAllowedCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return;
        }

        if (getContext() == null) {
            throw new SecurityException("Bridge context unavailable");
        }

        PackageManager packageManager = getContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages != null && Arrays.asList(packages).contains(ALLOWED_CONTROLLER_PACKAGE)) {
            return;
        }

        throw new SecurityException("Caller is not allowed to control container location");
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < -90.0d || latitude > 90.0d) {
            throw new IllegalArgumentException("Invalid latitude");
        }
        if (Double.isNaN(longitude) || Double.isInfinite(longitude) || longitude < -180.0d || longitude > 180.0d) {
            throw new IllegalArgumentException("Invalid longitude");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
