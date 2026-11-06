# VPN Switcher — per-app VPN country for Android TV

A small TV app that switches your VPN country per app: open МК ТВ and it connects to North Macedonia, open Stremio and it connects to the US, and so on. It drives [OpenVPN for Android](https://f-droid.org/packages/de.blinkt.openvpn/) through that app's remote-control API, using Private Internet Access's official OpenVPN profiles (every PIA region is built in). An accessibility service sees which app comes to the front (it never reads the screen) and connects that app's region, turns the VPN off, or leaves it as it is.

## Setup on the TV

1. Install [OpenVPN for Android](https://f-droid.org/packages/de.blinkt.openvpn/) and VPN Switcher (`./gradlew assembleRelease`, then `adb install`).
2. Open VPN Switcher and follow its five steps: allow control, allow VPN, PIA login, and turn it on under Settings → Accessibility → Services.
3. Pick a region for each app on the right.

## Build

You need JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed if keystore.properties exists, otherwise debug-signed
```

Release signing reads `keystore.properties` from the project root, which git ignores (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

The `de.blinkt.openvpn.api` AIDL files come from OpenVPN for Android's public remote-control API.
