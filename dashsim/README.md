# Zuki Dash Sim

A spare phone pretending to be the Gixxer SF 250's dash, so Zuki Nav can be
tested at home. It talks to **Zuki Sim**, a test build of Zuki Nav that
installs next to the real app and keeps its own settings, rides and log.

## Build

    ./gradlew assembleSim              # Zuki Sim, for the main phone
    ./gradlew :dashsim:assembleDebug   # Zuki Dash Sim, for the spare phone

## Set up

1. Install Zuki Dash Sim on the spare phone and open it. It renames that
   phone's Bluetooth to `SBM21SIMDASH`.
2. Install Zuki Sim on the main phone.
3. Developer options > Select mock location app > Zuki Sim, so the made-up
   ride's position is used as GPS.
4. In Zuki Dash Sim press Ignition, then in Zuki Sim scan and pick
   `SBM21SIMDASH`.

## Controls

- **Ignition / Engine**: the key and the starter. Ignition off drops the link
  the way a bike switching off does.
- **Speed**: where the throttle is held; the bike speeds up and slows down
  smoothly.
- **Time 1× / 10× / 60×**: the ride clock on both phones runs that much
  faster. **Skip 2 h** jumps ahead, for a lunch break (engine off).
- **Drop Link**: the link drops but the dash stays on.
- **Go Silent**: still connected, but nothing is sent.
- **Fuel**, **Reset Trip A / B**: set the tank and trip meters.

The made-up GPS goes on when the dash goes quiet: still where the bike stopped
after Ignition off, still moving at the last speed after Drop Link.
