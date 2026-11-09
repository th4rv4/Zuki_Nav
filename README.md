# Suzuki Nav

Google Maps turn-by-turn directions on the dash of Suzuki two-wheelers that
have **Suzuki Ride Connect**, over Bluetooth LE.

**This project is aimed at the Suzuki Ride Connect models sold in India**
(Suzuki Motorcycle India's Gixxer, V-Strom SX, Access, Burgman Street and
Avenis ranges). It was built from the Indian Suzuki Ride Connect app and
defaults to left-hand traffic. Suzuki bikes sold elsewhere use other
connectivity systems and are not supported.

Suzuki Ride Connect only navigates with Mappls. This app reads the guidance
notification Google Maps already shows and sends it to the cluster using the
cluster's own protocol (see [docs/PROTOCOL.md](docs/PROTOCOL.md)).

Unofficial and not affiliated with Suzuki. Use at your own risk, and don't
fiddle with your phone while riding.

## Supported models

Every vehicle Suzuki Ride Connect 8.0 can pair with. The model is detected
from the cluster's Bluetooth name (`S` + family letter + model code, e.g.
`SBM21…`).

| Model | Type | Name code | Family |
|---|---|---|---|
| Gixxer / Gixxer SF | Motorcycle | `BM11` | B |
| Gixxer 250 / SF 250 / FFV | Motorcycle | `BM21`, `BM22` | B |
| V-Strom SX | Motorcycle | `BM01` | B |
| Access 125 | Scooter | `AS01`, `AS02` | A |
| Access | Scooter | `AS21`, `BS4x` | A / B |
| Access TFT Edition | Scooter | `CS0x` | C |
| e-ACCESS | Scooter | `CEx` | C |
| Burgman Street | Scooter | `AS11`, `AS12`, `BS11`, `BS12`, `BS51` | A / B |
| Burgman Street EX | Scooter | `BS31`, `BS32` | B |
| Burgman Street TFT Edition | Scooter | `CS1x` | C |
| Avenis | Scooter | `BS21`, `BS22` | B |

What changes between models, all taken from the Suzuki app:

- **Family A** uses a different checksum and a status frame without weather.
- **Family C** (TFT) shows the ETA without a leading zero and gets no status
  frame (the Suzuki app's own status frame for them is broken).
- **Access-type** clusters (Access, e-ACCESS, TFT editions, Burgman `BS51`)
  use other icon IDs for a left off-ramp and the first exit of a
  counter-clockwise roundabout.

An unknown code still works: the family letter picks the checksum and layout.

Status: frame bytes are verified against the Suzuki app's own code for every
model above, but no model has been tested on a real bike yet. The author's
own bike is a Gixxer SF 250. Reports from other models are welcome,
especially results from **Icon Test → Cycle All**.

## What it sends

- Turn arrow, distance to the turn, ETA and remaining distance, every 200 ms
- Clock (and optional speed-alert limit) every 2 s, where the model takes it
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
2. Open Suzuki Nav, tap **Permissions**, then **Notification Access** and enable it.
3. Force-stop Suzuki Ride Connect. The cluster accepts one phone at a time.
4. Ignition on, tap **Scan For Bike**, pick your vehicle (it is listed with its model name).
5. Start a route in Google Maps.

Use **Icon Test → Cycle All** once to check every icon ID on your dash.
Some IDs in `ClusterIcon` are marked unverified because nothing in the Suzuki
app uses them.

## Build

Every push is built by GitHub Actions (`.github/workflows/build.yml`): unit
tests run and the debug APK is attached to the run as an artifact.

To build locally:

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
protocol/   frame builder, checksum, distance format, icon table, model table (pure Kotlin)
nav/        Maps notification parsing, arrow fingerprinting, listener
ble/        GATT client
service/    foreground service, boot receiver
ui/         main screen
```

## License

MIT
