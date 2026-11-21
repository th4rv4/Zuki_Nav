#!/usr/bin/env bash
# Rides Zuki Sim against Zuki Dash Sim over adb, start to finish, and saves
# what happened into simtest/<date-time>/ for a look afterwards.
#
#   bash dashsim/simtest.sh            # the whole ride, about 5 min
#   bash dashsim/simtest.sh 7          # start at step 7
#
# Each wait is just past the app's own limit for that thing, so it's quick
# but still a real test. Step 7 stops the search (as Stop Searching For
# Bike does) so the app is asleep when alerts come in, and step 8 taps
# Connect. Wake On Ignition itself is tested on the real bike: Android
# doesn't pick up a phone playing the dash the way it does the dash.
#
# Both phones on adb, both test apps installed and set up (paired, bike
# chosen in Zuki Sim). Override the phones with
# POCO=<serial> REALME=<serial> if they change.

POCO=${POCO:-559HI7JVXOFEWGJR}
REALME=${REALME:-e362d4f9}
FROM=${1:-1}

SIM=com.th4rv4.zukinav.sim
DASH=com.th4rv4.zukidashsim
RUN=$(date +%Y%m%d-%H%M%S)
OUT=simtest/$RUN
mkdir -p "$OUT"
NOTES=$OUT/steps.txt

# ---- Progress bar ----------------------------------------------------------

NAMES=("" "Warm up" "Ride off" "Long ride at 60x" "Traffic stops" "Drop Link"
       "Calls and messages" "Lunch break" "Back on the bike" "Run out of fuel" "Park, off and on")
# Rough length of each step in seconds, for the overall bar.
WEIGHT=(0 20 10 32 22 35 50 35 40 10 25)
TOTAL_W=0; for w in "${WEIGHT[@]}"; do TOTAL_W=$((TOTAL_W + w)); done
CUR=0; STEP_START=$(date +%s); RUN_START=$(date +%s); WAITING=""

draw() {
    [ -t 1 ] || return
    local now done=0 i in_step pct filled bar elapsed
    now=$(date +%s)
    for ((i = 1; i < CUR; i++)); do done=$((done + WEIGHT[i])); done
    in_step=$((now - STEP_START)); [ $in_step -gt "${WEIGHT[CUR]:-0}" ] && in_step=${WEIGHT[CUR]:-0}
    [ "$CUR" -gt 0 ] && done=$((done + in_step))
    pct=$((done * 100 / TOTAL_W)); [ "$1" = "end" ] && pct=100
    filled=$((pct * 30 / 100))
    bar=$(printf '%*s' "$filled" '' | tr ' ' '#')$(printf '%*s' $((30 - filled)) '' | tr ' ' '-')
    elapsed=$(( (now - RUN_START) / 60 ))m$(( (now - RUN_START) % 60 ))s
    printf '\r\033[K[%s] %3d%%  Step %d/10: %s  %s  (%s)' "$bar" "$pct" "$CUR" "${NAMES[CUR]}" "$WAITING" "$elapsed"
}

say() {
    [ -t 1 ] && printf '\r\033[K'
    echo "$(date +%H:%M:%S) $*" | tee -a "$NOTES"
    draw
}

# pause <seconds> [what]: waits with a countdown on the bar.
pause() {
    local left=$1
    while [ "$left" -gt 0 ]; do
        WAITING="${2:-waiting} ${left}s"; draw
        sleep 1; left=$((left - 1))
    done
    WAITING=""; draw
}
die() { say "STOPPED: $*"; collect; exit 1; }

# ---- The two apps ----------------------------------------------------------

dash() { # dash <cmd> [value]
    local r
    r=$(adb -s "$REALME" shell am broadcast -n $DASH/.DashControl --es cmd "$1" ${2:+--es value "$2"} 2>&1 | tr -d '\r' | sed -n 's/.*data="\([^"]*\)".*/\1/p')
    [ "$r" = "ok" ] || say "  dash $*: ${r:-no answer}"
}

sim() { # sim <cmd> [--es key value ...]
    local cmd=$1; shift
    local r
    r=$(adb -s "$POCO" shell am broadcast -n $SIM/com.th4rv4.zukinav.sim.SimControl --es cmd "$cmd" "$@" 2>&1 | tr -d '\r' | sed -n 's/.*data="\([^"]*\)".*/\1/p')
    [ "$r" = "ok" ] || say "  sim $cmd $*: ${r:-no answer}"
}

call()    { sim call --es caller "$1" --es end "$2" --ei secs "$3"; }
message() { sim message --es from "$1" --es app "${2:-whatsapp}"; }

# A line in both logs, to find each step later. No spaces (adb shell splits them).
mark() {
    CUR=$1; STEP_START=$(date +%s)
    local m="${RUN}_$1"
    dash mark "$m"
    sim mark --es text "$m"
    say "== Step $1/10: ${NAMES[$1]}"
}

state() { adb -s "$REALME" shell run-as $DASH cat files/state.txt 2>/dev/null | tr -d '\r'; }
get() { state | sed -n "s/^$1=//p"; }

# wait_until <seconds> <what> <test...>: polls the test every 2 s.
wait_until() {
    local limit=$1 what=$2; shift 2
    local t=0
    while ! "$@"; do
        WAITING="waiting for $what ${t}s"; draw
        sleep 2; t=$((t + 2))
        if [ $t -ge "$limit" ]; then WAITING=""; say "  gave up waiting ($limit s): $what"; return 1; fi
    done
    WAITING=""
    say "  $what (${t} s)"
}

connected()    { [ "$(get connected)" = "true" ]; }
disconnected() { [ "$(get connected)" = "false" ]; }
engine_off()   { [ "$(get engine)" = "false" ]; }
warm()         { [ "$(get engineC)" -ge 42 ] 2>/dev/null; }

simlog() { adb -s "$POCO" shell run-as $SIM sh -c "'cat files/logs/log-*.txt'" 2>/dev/null | tr -d '\r'; }
simask() { # simask <cmd>: Zuki Sim's answer
    adb -s "$POCO" shell am broadcast -n $SIM/com.th4rv4.zukinav.sim.SimControl --es cmd "$1" 2>&1 | tr -d '\r' | sed -n 's/.*data="\([^"]*\)".*/\1/p'
}
# Zuki Sim's log since this run started has <pattern>.
sim_says() { simlog | awk -v m="${RUN}_1" 'index($0, m) {f=1} f' | grep -q "$1"; }
sim_says_since() { simlog | awk -v m="${RUN}_$1" 'index($0, m) {f=1} f' | grep -q "$2"; }

collect() {
    say "Collecting logs into $OUT"
    pause 6 "saving logs" # Zuki Sim writes its log in batches
    simlog > "$OUT/zuki-sim-log.txt"
    adb -s "$POCO" shell run-as $SIM cat files/trips.json 2>/dev/null | tr -d '\r' > "$OUT/zuki-sim-trips.json"
    adb -s "$REALME" shell run-as $DASH cat files/dash-log.txt 2>/dev/null | tr -d '\r' > "$OUT/dash-sim-log.txt"
    state > "$OUT/dash-sim-state.txt"
    draw end; echo
    say "Done. Tell Claude the folder: $OUT"
}

step() { [ "$1" -ge "$FROM" ]; }

# ---- Start -----------------------------------------------------------------

adb devices | grep -q "^$POCO[[:space:]]*device" || die "POCO ($POCO) not on adb"
adb devices | grep -q "^$REALME[[:space:]]*device" || die "Realme ($REALME) not on adb"
adb -s "$REALME" shell am start -n $DASH/.MainActivity > /dev/null
adb -s "$POCO" shell am start -n $SIM/com.th4rv4.zukinav.ui.MainActivity > /dev/null
sleep 3
say "Run $RUN, from step $FROM"
STATUS=$(simask status)
say "Zuki Sim: ${STATUS:-no answer}"
case "$STATUS" in *notifications=off*) say "  Zuki Sim has no notification access: the fake calls and messages won't work";; esac
mark 1

# ---- 1. Warm up standing still (60+ ride s and +10 °C, at 10x) --------------
if step 1; then
    dash timex 1; dash speed 0; dash fuel 12
    # Start cold: with the engine off, Skip 2 h lets it cool right down.
    dash engine off; dash ignition off; dash skip
    dash ignition on
    wait_until 90 "connected" connected || die "Zuki Sim didn't connect"
    dash engine on; dash timex 10
    wait_until 60 "engine warm (42 °C)" warm
    pause 4 "warming"   # 60+ s of ride time standing still
    dash timex 1
fi

# ---- 2. Ride off -----------------------------------------------------------
if step 2; then
    mark 2
    dash speed 60
    pause 8
fi

# ---- 3. Long ride: 30 s at 60x is half an hour ------------------------------
if step 3; then
    mark 3
    dash timex 60
    pause 30 riding
fi

# ---- 4. Traffic stops: 5 s at 60x is a 5 min stop ---------------------------
if step 4; then
    mark 4
    for i in 1 2; do
        dash speed 0; pause 5 stopped
        dash speed 60; pause 5 riding
    done
fi

# ---- 5. Dash drops out while riding (at 1x) ---------------------------------
if step 5; then
    mark 5
    dash timex 1; pause 2
    dash drop
    wait_until 60 "link back after Drop Link" connected
    pause 3
fi

# ---- 6. Calls and messages while riding (1x) -------------------------------
if step 6; then
    mark 6
    dash timex 1
    call saved missed 4;       pause 6 "missed call"
    call unknown answered 3;   pause 9 "answered call"
    call cnap rejected 3;      pause 4 "rejected call"
    call whatsapp missed 4;    pause 6 "WhatsApp call"
    message saved;             pause 2
    message unknown;           pause 2
    message group;             pause 2
    message own;               pause 2
    message saved sms;         pause 3
    sim clear;                 pause 2
fi

# ---- 7. Lunch: switch off, alerts while away, stop searching, skip 2 h -------
if step 7; then
    mark 7
    # At 10x the app decides "switched off, not dropped" in a few seconds.
    dash speed 0; dash timex 10; pause 3
    dash engine off; dash ignition off
    wait_until 30 "dash gone" disconnected
    pause 8 "bike off"
    call saved missed 4; pause 6 "missed call while away"
    # As tapping Stop Searching For Bike: the app stops looking.
    sim stopsearch; pause 3
    # Comes in while the link isn't running at all.
    message saved; pause 2
    dash timex 1
    dash skip
fi

# ---- 8. Back on the bike -----------------------------------------------------
if step 8; then
    mark 8
    dash ignition on
    # Wake On Ignition itself is tested on the real bike: a phone playing the
    # dash hides its address in a way Android's watching doesn't pick up.
    # So tap Connect, as after Stop Searching For Bike.
    sim connect
    wait_until 60 "connected again" connected || die "Zuki Sim didn't come back"
    pause 14 "alerts from while away" # they show 12 s after connecting
    dash engine on
    dash speed 60; dash timex 60
    pause 15 riding
fi

# ---- 9. Run out of fuel ------------------------------------------------------
if step 9; then
    mark 9
    dash speed 100; dash fuel 0.2
    wait_until 60 "ran dry, engine stopped" engine_off
    pause 2
fi

# ---- 10. Park, then off and on -------------------------------------------------
if step 10; then
    mark 10
    dash timex 1; dash speed 0
    dash ignition off; pause 3
    for i in 1 2; do
        dash ignition on
        wait_until 60 "reconnect $i" connected
        pause 3
        dash ignition off
        pause 3
    done
fi

collect
