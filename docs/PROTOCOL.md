# Suzuki Ride Connect cluster protocol

Recovered from **Suzuki Ride Connect 8.0** (`suzuki.com.suzuki`, version code 244)
for interoperability. The class names below are the obfuscated R8 names in
that build, so they will change in later releases.

## BLE link

| Step | Detail | Source |
|---|---|---|
| Discover | Device name is 12 characters and contains `SA`, `SB` or `SC` | `adapter/j.a()` |
| Model | Character 1 is the family (`A`, `B`, `C`), the next 2–3 the model; full table under *Models* below | `DeviceListingScanActivity.i()` |
| Connect | FastBLE, MTU 250 requested | `com/clj/fastble/b.j()` |
| Service | `gatt.getServices().get(3)`: the UUIDs are not hard-coded | `MyBleService.a()` |
| Write | characteristic `[0]` of that service | `MyBleService.f()` |
| Notify | characteristic `[1]` of that service (the app ignores what arrives) | `MyBleService.a()`, `services/c` |

No pairing, bonding or authentication step was found.

## Frame

Every frame is 30 bytes:

```
[0]      0xA5
[1]      type, ASCII
[2..27]  payload
[28]     checksum
[29]     0x7F
```

Checksum = sum of bytes 1..27, mod 256. A-series clusters (name char 1 = `A`)
use `255 - sum` instead (`SuzukiApplication.a()`, flag `k0.g` set in
`DeviceListingScanActivity.i()`).

| Type | Meaning | Built in |
|---|---|---|
| `'1'` | Turn-by-turn navigation | `application/fragment/d2.D()` |
| `'2'` | Incoming call | `CallReceiverBroadcast.d()`, `NotificationService.r()` |
| `'3'` | Status / clock / weather | `application/fragment/b0.run()` |
| `'4'` | Missed call | `NotificationService.q()` |
| `'5'` | SMS / WhatsApp message | `IncomingSms.c()`, `NotificationService.p()` |
| `'6'` | Hello (rider name) | `b0.run()`, `d2.E()` |

### `'1'` navigation

```
[2]      cluster turn-icon ID (raw byte, table below)
[3]      0xFF
[4..7]   distance to the turn, 4 ASCII chars
[8]      'M' metres / 'K' kilometres
[9..14]  ETA, 6 ASCII chars: "0742PM" (12 h) or "001942" (24 h)
[15..17] 0xFF
[18..21] remaining distance, 4 ASCII chars
[22]     'M' / 'K'
[23]     status: '1' OK, '0' no SIM, '4' GPS lost, '5' arrived, '6' no network, '2'/'3' unknown
[24]     '1' navigating, '0' navigation ended
[25..27] 0xFF
```

If the status is anything other than `1`, `3` or `5`, byte 2 is replaced with
46. Sent every 200 ms while navigating (`y1.run()` default case). On exit the
frame goes out 10 times with byte 24 = `'0'`.

Distance strings come from the Mappls formatter followed by Suzuki's own string
handling:

| Distance | Digits | Unit |
|---|---|---|
| 356 m (to the turn, floored to 10 m) | `0350` | M |
| 2.58 km | `02.5` | K (second decimal truncated) |
| 12.34 km | `12.3` | K |
| 123.4 km | `0123` | K |

### `'3'` status

```
[2..3]   "1N"
[4..6]   speed-alert limit "080", or FF FF FF when off
[7]      signal bars '1'..'3', or 0x00
[8..13]  clock "hhmmss", 12-hour
[14]     'Y' = clear SMS indicator, else 'N'
[15]     'Y' = clear missed-call indicator, else 'N'
[16..20] 0xFF
[21]     weather: 0 none, 1 clear, 2 cloudy, 3 fog, 4 showers, 5 thunder, 6 rain, 7 snow, 8 sleet, 9 hot, 10 cold, 11 windy
[22]     115 + temperature in °F (0 = none)
[23]     1
[24..27] 0xFF
```

That is the B-series layout. The A-series layout matches it up to byte 15,
except that byte 7 is always an ASCII digit. Bytes 16..27 are all 0xFF and
the checksum is inverted.

For C-series clusters the Suzuki app first does
`Integer.parseInt(f0.H.substring(2), 16)`, but nothing ever sets `f0.H`. The
exception is caught and an all-zero 30-byte frame goes out instead. Suzuki
Nav sends no status frame to them.

### `'6'` hello

```
[2..21]  rider name, NUL padded
[22..26] 0xFF
[27]     'F' first connection to this cluster, 'R' reconnect
```

### `'2'` call

```
[2..21]  caller name/number, NUL padded, '+' removed
[22]     'N' phone, 'W' WhatsApp
[23]     '1' ringing, '2' ended
[24..27] 0xFF
```

## Turn icons

`d2.C()` turns Mappls maneuver IDs into cluster IDs. The meaning of each
Mappls ID comes from the `$N` suffixes in
`translations/mappls-directions-en_short_instructions.json`. For roundabouts
it comes from `DirectionsUtils.getManeuverId()`, which buckets the angle
travelled into 45° steps: 65–71 for left-hand traffic and 58–64 for
right-hand traffic.

| Cluster | Mappls | Meaning |
|---|---|---|
| 1 | 0 | Turn left |
| 2 | 1 | Sharp left |
| 3 | 2 | Slight left / keep left |
| 4 | 3 | Turn right |
| 5 | 4 | Sharp right |
| 6 | 5 | Slight right / keep right |
| 7 | 6 | U-turn (right-hand traffic) |
| 8 | 7 | Straight |
| 9 | 8, 9, 10 | Destination |
| 10 | 75 | Off-ramp right |
| 11, 12 | 11, 12 | unverified |
| 13 | 13 | End of road, left |
| 14 | 14 | End of road, right |
| 15–19 | 53–57 | unverified |
| 20–26 | 65–71 | Roundabout exit, left-hand traffic, ≤45° … >270° |
| 27 | 19 | Fork / keep left |
| 28 | 20 | Fork / keep right |
| 29 | 17 | Fork sharp left |
| 30 | 18 | Fork sharp right |
| 31 | 15, 26, 27, 28 | Continue slight left / merge left |
| 32 | 16, 30, 31 | Continue slight right / merge right |
| 33 | 21 | Continue straight |
| 34–37 | 22–25 | unverified |
| 38 | 73 | Take the ramp (Access-type clusters: also 74) |
| 39 | 41 | U-turn (left-hand traffic) |
| 40–42 | 50–52 | unverified |
| 43 | 36 | Ferry |
| 44 | 74 | Off-ramp left |
| 45 | 72 | Enter roundabout |
| 46–52 | 58–64 | Roundabout exit, right-hand traffic |

Also used: 46 as the "no GPS / no network" placeholder, and 61, flashed
alternately with the arrow for alerts.

## Models

From `DeviceListingScanActivity.i()`. "Name" is the BLE name from character 1.

| Name | Model | Family | Checksum | Status layout |
|---|---|---|---|---|
| `CE…` | e-ACCESS | C | sum | none (see above) |
| `CS0…` | Access TFT Edition | C | sum | none |
| `CS1…` | Burgman Street TFT Edition | C | sum | none |
| `BS4…` | Access | B | sum | B |
| `AS21` | Access | A | 255 − sum | A |
| `AS01`, `AS02` | Access 125 | A | 255 − sum | A |
| `AS11`, `AS12` | Burgman Street | A | 255 − sum | A |
| `BS11`, `BS12`, `BS51` | Burgman Street | B | sum | B |
| `BS21`, `BS22` | Avenis | B | sum | B |
| `BS31`, `BS32` | Burgman Street EX | B | sum | B |
| `BM01` | V-Strom SX | B | sum | B |
| `BM11` | Gixxer / Gixxer SF | B | sum | B |
| `BM21`, `BM22` | Gixxer 250 / SF 250 / FFV | B | sum | B |

Other per-model rules in `d2`:

- e-ACCESS, Access TFT and Burgman Street TFT: if the ETA starts with `'0'`,
  byte 9 of the nav frame becomes a space (`d2.D()`).
- e-ACCESS, Access TFT, Burgman Street TFT, Access, and any `SBS51` cluster:
  Mappls 74 (off-ramp left) maps to cluster 38 instead of 44, and Mappls 58
  (first right-hand-traffic roundabout exit) to 44 instead of 46 (`d2.C()`).
