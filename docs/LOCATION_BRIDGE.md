# LocationSpoofer -> NewBlackbox bridge

The `virtual-location-bridge` branch exposes one restricted `ContentProvider` so the companion LocationSpoofer app can control one container-wide location for all BlackBox guests.

## Authority

`content://top.niunaijun.blackbox.locationbridge`

Only calls from package `com.suseoaa.locationspoofer` are accepted. The provider resolves the Binder calling UID back to package names and rejects every other external caller.

## Methods

- `ping` - returns `bridge_version`, `enabled`, and current coordinates when enabled.
- `get_state` - same state payload as `ping`.
- `set_location` - requires `latitude` and `longitude` doubles in the extras `Bundle`.
- `clear_location` - removes the container-wide override and restores legacy per-package behavior.

## Kotlin client example

```kotlin
private val bridgeUri = Uri.parse("content://top.niunaijun.blackbox.locationbridge")

fun setNewBlackboxLocation(context: Context, latitude: Double, longitude: Double): Bundle? {
    val extras = Bundle().apply {
        putDouble("latitude", latitude)
        putDouble("longitude", longitude)
    }
    return context.contentResolver.call(bridgeUri, "set_location", null, extras)
}

fun clearNewBlackboxLocation(context: Context): Bundle? {
    return context.contentResolver.call(bridgeUri, "clear_location", null, null)
}

fun getNewBlackboxLocationState(context: Context): Bundle? {
    return context.contentResolver.call(bridgeUri, "get_state", null, null)
}
```

## Current scope

V1 intentionally transports only latitude and longitude. Altitude, speed, bearing, accuracy, route playback, GNSS/NMEA, Wi-Fi, cellular and BLE consistency can be added after Android 16 location callback compatibility is validated.
