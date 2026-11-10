# Zuki Nav

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
- Incoming, answered and missed calls (phone and WhatsApp), with the
  caller's name
- New WhatsApp and SMS messages, with the sender's name and unread count

**Skip Business SMS** (off by default) leaves out SMS from registered
business senders, such as banks, OTPs, government and promotions
(`AD-HPGOVT-S`, `VM-HDFCBK`), so only messages from people reach the
dash.

Calls and messages are read from the phone's notifications, through the
same Notification Access that reads Google Maps; no extra permission is
needed. When you clear the missed-call or message notifications on the
phone, the dash clears its indicators too. Both can be turned off in
Settings.

## What it shows from the bike

The dash reports its fuel gauge, ODO and both trip meters every few
seconds. The Ride tab shows them live, and keeps the last readings (with
when they were received) after the ignition is off.

## Trips

Every ride is saved as a trip, from ignition on to ignition off. A stop
with the engine off for less than 3 minutes (a fuel stop) stays part of
the same ride. Distance comes from Trip A, with the ODO covering any
stretch where Trip A was reset. Each trip has its distance, moving time,
average and top speed, time stopped, and the ODO at the start and end.

Trips can be renamed, starred, shared or deleted. Up to 300 trips are
kept; starred ones are never removed. Rides under 0.5 km are skipped
unless **Skip Short Rides** is turned off.

**Start And End Places** (off by default) names where each ride started
and ended, using the phone's location. It needs location allowed all the
time, because a ride ends with the ignition off while the app is in the
background. Fuel economy is not shown: the Gixxer SF 250 dash does not
send it.

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
2. Open Zuki Nav. The **Set Up Zuki Nav** screen lists what is needed:
   - **Nearby Devices**: tap **Grant**.
   - **Notification Access**: tap **Grant** and switch Zuki Nav on.
   - **Close Suzuki Ride Connect**: tap **Force Stop**. The dash accepts
     one phone at a time.
   - **Your Name** (optional): the dash greets you with it when it
     connects. It can be changed later in Settings.
3. Turn the ignition on. Under **Find Your Bike**, tap your bike (it is
   listed with its model name), then **Finish Setup**.
4. Start a route in Google Maps. The **Ride** tab shows the next turn and
   what is being sent to the dash.

## App layout

- **Ride**: the bike and its connection, fuel, ODO and trip meters, the
  ride in progress, live guidance (arrow, distance, ETA, remaining
  distance, dash icon number) and a Connect/Disconnect button.
- **Trips**: saved rides with this month's total, and a Starred filter.
- **Dash Test**: show any turn icon on the dash, mark it **Looks Right** or
  **Looks Wrong** (the next icon follows automatically), see progress for
  all 64 icons, and **Share Results**. **Text Test** sends your own text
  into the number spots under the arrow. Tests only run while Google Maps
  isn't navigating.

Optional extras in Settings: **Startup Animation** spins the compass arrow
on the dash after "Welcome", and **Startup Greeting** shows a short text for 3
seconds on the dash's call screen. The dash always puts its own
"CALLER:" label above it; no other screen can show text. Both are skipped when a route is already running,
and a route starting mid-animation takes over at once.
- **Settings**: rider name, speed alert, call and message alerts, traffic
  side, theme (System, Light or Dark), trip recording, Auto-Connect, Start
  On Boot, Change Bike, updates, and the log.

## Updates

**Settings → Updates → Check For Updates** looks at this repository's
latest GitHub release. If it is newer than the installed app, it shows
what changed and a **Download And Install** button; Android then asks to
confirm the update. Settings are kept. The first time, Android asks to
allow Zuki Nav to install apps.

With **Check On Launch** on, the app checks at most once a day when it
opens and shows a message if a new version is out. The app only goes
online to talk to GitHub.

Run **Dash Test → Cycle All** once to check every icon on your dash.

> **Some icons may not show.** Icons 47 to 64 depend on the traffic side
> (right-hand-traffic roundabouts are 47–52) and on the Suzuki model. On a
> Gixxer SF 250, icon 46 is a "!" and 47 to 64 are blank. The app sends the
> icon the Traffic Side setting asks for either way.

Icons 1 to 46 have all been checked on a Gixxer SF 250 dash.

## Build

GitHub Actions (`.github/workflows/build.yml`) builds when a version tag
is pushed, or when started by hand (Actions → Build → Run workflow). Plain
commits don't trigger it. Unit tests run and the debug APK is attached to
the run as an artifact.

A tag push also publishes a GitHub Release named after the tag, with the
APK attached and every commit since the previous `v*` tag listed in the
notes. The app's version name is taken from the tag (`v0.2.0` → `0.2.0`).

```
git tag v0.2.0
git push origin v0.2.0
```

To build locally:

```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with `app/debug.keystore` (password `android`), which
is checked in so that every build, local or CI, can update the previous one.
It is a public key: anyone can sign an APK with it, so only install builds
from this repo's releases.

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
ui/         screens (Ride, Dash Test, Settings, Setup) and shared components
```

## License

MIT
