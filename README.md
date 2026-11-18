# Bridge mock location (Android companion app)

Tiny app for the Android phone. The bridge sends it the iPhone's position and it
publishes that as the phone's location, so apps on the phone (a scooter app, say)
believe the phone is where the iPhone is.

- No screen, no launcher icon. One foreground service with a silent notification.
- Listens on `127.0.0.1:8422` only (UDP). Every datagram must carry the key the
  bridge generated; the bridge starts the service and passes the key through adb.
- Only the `adb shell` user can start it (`android:permission=DUMP`).
- Needs no location permission: it never reads the real location.

## Build (once, on a computer with Android Studio or JDK 17 + Gradle 8.7+)

```sh
cd android-mock
gradle assembleDebug          # or open the folder in Android Studio and Build > Build APK
# result: app/build/outputs/apk/debug/app-debug.apk
```

## Install (once)

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Nothing else. When you switch "Share my location" on in the web app, the bridge
runs, through its own adb connection:

```sh
appops set com.phonebridge.mock MOCK_LOCATION allow        # = "Select mock location app"
am start-foreground-service -n com.phonebridge.mock/.MockService --es key <key> --ei port 8422
```

Check by hand: `adb shell appops get com.phonebridge.mock MOCK_LOCATION` should say `allow`.

## Things that will not work

- Scooter apps that refuse mock locations. Android tells apps when a location came
  from a mock provider (`Location.isFromMockProvider`); some apps check it, and
  Play Integrity based checks cannot be satisfied by any mock location. Test with
  your scooter app before relying on this.
- Scooters that unlock over Bluetooth: the Android phone is at home, the scooter is
  next to the iPhone. Only scooters unlocked through the cloud (QR code or
  number) work.
- Android 12 and later remove mock providers when the app dies; Android 9 keeps them.
