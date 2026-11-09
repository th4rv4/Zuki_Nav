# Gixxer Nav

Google Maps turn-by-turn directions on the Suzuki Ride Connect dash
(Gixxer 250 / SF 250 and other Suzuki Ride Connect clusters), over Bluetooth LE.

Suzuki Ride Connect only navigates with Mappls. This app reads the guidance
notification Google Maps already shows and sends it to the cluster using the
cluster's own protocol (see [docs/PROTOCOL.md](docs/PROTOCOL.md)).

Unofficial and not affiliated with Suzuki. Use at your own risk, and don't
fiddle with your phone while riding.

## What it sends

- Turn arrow, distance to the turn, ETA and remaining distance, every 200 ms
- Clock (and optional speed-alert limit) every 2 s
- Rider name on connect

## How the turn arrow is chosen

1. **The Maps arrow itself.** Google Maps ships its arrows as named drawables
   (`maneuver_turn_sharp_left`, `maneuver_roundabout_enter_and_exit_cw_slight_right`, …).
   On first use the app renders those from the installed Maps app and
   fingerprints their shapes. Each notification arrow is matched to the
   nearest one. No Google artwork is bundled.
2. **The instruction text**, if Maps spells the maneuver out ("Sharp right",
   "take the 2nd exit"). English only.
3. **The previous arrow**, if neither gives an answer.

This approach, and the Maps notification layouts, follow
[KTM-Nav-GEN3](https://github.com/Pavanayi1/KTM-Nav-GEN3) (MIT).

## Setup

1. Build and install (below).
2. Open Gixxer Nav, tap **Permissions**, then **Notification Access** and enable it.
3. Force-stop Suzuki Ride Connect. The cluster accepts one phone at a time.
4. Ignition on, tap **Scan For Bike**, pick the `SBM21…` entry.
5. Start a route in Google Maps.

Use **Icon Test → Cycle All** once to check every icon ID on your dash.
Some IDs in `ClusterIcon` are marked unverified because nothing in the Suzuki
app uses them.

## Build

```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and an Android SDK with platform 36 (`local.properties` → `sdk.dir`).
For a signed release, add `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` to `local.properties` and run
`./gradlew assembleRelease`.

## Layout

```
protocol/   frame builder, checksum, distance format, icon table (pure Kotlin)
nav/        Maps notification parsing, arrow fingerprinting, listener
ble/        GATT client
service/    foreground service, boot receiver
ui/         main screen
```

## License

MIT
