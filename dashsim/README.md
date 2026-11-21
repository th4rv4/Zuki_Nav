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
- **Drop Link**: the link drops but the dash stays on (its Bluetooth goes off and on, as when the signal is lost).
- **Go Silent**: still connected, but nothing is sent.
- **Fuel**, **Reset Trip A / B**: set the tank and trip meters.

The made-up GPS goes on when the dash goes quiet: still where the bike stopped
after Ignition off, still moving at the last speed after Drop Link.

## Test script

With both phones on adb and everything set up (paired, Zuki Dash Sim chosen
in Zuki Sim):

    bash dashsim/simtest.sh        # the whole ride, about 5 minutes
    bash dashsim/simtest.sh 7      # from step 7 on

It drives both apps over adb: warm-up, a long ride at 60×, traffic stops,
Drop Link, fake calls and messages, a lunch break with alerts while the
app has stopped looking,
running out of fuel, and switching off and on. Logs and saved trips from
both phones go into `simtest/<date-time>/`.

The adb controls can also be used by hand:

    adb shell am broadcast -n com.th4rv4.zukidashsim/.DashControl --es cmd speed --es value 60
    adb shell am broadcast -n com.th4rv4.zukinav.sim/com.th4rv4.zukinav.sim.SimControl \
        --es cmd call --es caller saved --es end missed --ei secs 15

Zuki Dash Sim: `ignition on|off`, `engine on|off`, `speed <km/h>`,
`timex 1|10|60`, `skip`, `drop`, `silent on|off`, `fuel <litres>`.
Zuki Sim: `call` (caller saved|unknown|cnap|whatsapp, end
missed|answered|rejected, secs), `hangup`, `message` (from
saved|unknown|group|own, app whatsapp|sms), `clear`, `status`, and
`stopsearch` (as tapping Stop Searching For Bike), `connect` (as tapping Connect).

Zuki Sim also has the same calls and messages under Settings › Fake Alerts.
