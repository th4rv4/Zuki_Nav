#!/usr/bin/env bash
# Rides Zuki Sim against Zuki Dash Sim over adb, start to finish, and saves
# what happened into simtest/<date-time>/ for a look afterwards.
#
#   bash dashsim/simtest.sh            # the whole ride, about 5 min
#   bash dashsim/simtest.sh 7          # start at step 7
#
# Each wait is just past the app's own limit for that thing, so it's quick
# but still a real test. Step 7 stops the search (as Stop Searching For
# Bike does) and step 8 taps Connect. Calls and messages from a mid-ride
# dropout (step 5) show once the dash is back. A short tea stop (step 7)
# with both on 30 Min shows them on the way out. At lunch (step 8) calls
# are on 30 Min, so the missed call is kept but too old after 2 h, and
# messages on Never, so the message stays on the phone.
#
# At the end it checks each step against both phones' logs and the saved
# ride, looks for crashes, and prints OK or CHECK for each. Wake On Ignition itself is tested on the real bike: Android
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
       "Calls and messages" "Tea stop" "Lunch break" "Back on the bike" "Run out of fuel" "Park, off and on")
LAST=$(( ${#NAMES[@]} - 1 ))
# Rough length of each step in seconds, for the overall bar.
WEIGHT=(0 20 10 32 22 36 50 51 35 46 10 25)
TOTAL_W=0; for w in "${WEIGHT[@]}"; do TOTAL_W=$((TOTAL_W + w)); done
CUR=0; STEP_START=$(date +%s); RUN_START=$(date +%s); WAITING=""

draw() {
    [ -t 1 ] && [ -z "$FINISHED" ] || return
    local now done=0 i in_step pct filled bar elapsed
    now=$(date +%s)
    for ((i = 1; i < CUR; i++)); do done=$((done + WEIGHT[i])); done
    in_step=$((now - STEP_START)); [ $in_step -gt "${WEIGHT[CUR]:-0}" ] && in_step=${WEIGHT[CUR]:-0}
    [ "$CUR" -gt 0 ] && done=$((done + in_step))
    pct=$((done * 100 / TOTAL_W)); [ "$1" = "end" ] && pct=100
    filled=$((pct * 30 / 100))
    bar=$(printf '%*s' "$filled" '' | tr ' ' '#')$(printf '%*s' $((30 - filled)) '' | tr ' ' '-')
    elapsed=$(( (now - RUN_START) / 60 ))m$(( (now - RUN_START) % 60 ))s
    printf '\r\033[K[%s] %3d%%  Step %d/%d: %s  %s  (%s)' "$bar" "$pct" "$CUR" "$LAST" "${NAMES[CUR]}" "$WAITING" "$elapsed"
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
    [ "$r" = "ok" ] && return
    gone "$REALME" && die "Realme dropped off adb (cable pulled?)"
    say "  dash $*: ${r:-no answer}"
}
gone() { [ "$(adb -s "$1" get-state 2>/dev/null)" != "device" ]; }

sim() { # sim <cmd> [--es key value ...]
    local cmd=$1; shift
    local r
    r=$(adb -s "$POCO" shell am broadcast -n $SIM/com.th4rv4.zukinav.sim.SimControl --es cmd "$cmd" "$@" 2>&1 | tr -d '\r' | sed -n 's/.*data="\([^"]*\)".*/\1/p')
    [ "$r" = "ok" ] && return
    gone "$POCO" && die "POCO dropped off adb (cable pulled?)"
    say "  sim $cmd $*: ${r:-no answer}"
}

call()    { sim call --es caller "$1" --es end "$2" --ei secs "$3"; }
message() { sim message --es from "$1" --es app "${2:-whatsapp}"; }

# A line in both logs, to find each step later. No spaces (adb shell splits them).
mark() {
    CUR=$1; STEP_START=$(date +%s)
    local m="${RUN}_$1"
    dash mark "$m"
    sim mark --es text "$m"
    say "== Step $1/$LAST: ${NAMES[$1]}"
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

# expect <step|all> <want> <pattern> <what> [sim|dash]: checked at the end
# against Zuki Sim's (or Zuki Dash Sim's) log for that step, once it's all
# written out. <want>: yes, no, a number (at least that many) or =<number>.
EXPECT=()
expect() { EXPECT+=("$1"$'\t'"$2"$'\t'"$3"$'\t'"$4"$'\t'"${5:-sim}"); }

# section <step|all> <log>: the lines logged during that step.
section() {
    if [ "$1" = all ]; then
        awk -v m="${RUN}_1 ====" 'index($0, m) {f=1} f' "$2"
    else
        awk -v m="${RUN}_$1 ====" -v x="${RUN}_$(($1 + 1)) ====" 'index($0, x) {f=0} index($0, m) {f=1} f' "$2"
    fi
}

PASS=0; FAIL=0
report() { # report 1|0 <where> <what>
    if [ "$1" = 1 ]; then PASS=$((PASS + 1)); say "  OK, $2: $3"
    else FAIL=$((FAIL + 1)); say "  CHECK, $2: $3"; fi
}

checks() {
    local e n want pat what log file count ok
    for e in "${EXPECT[@]}"; do
        IFS=$'\t' read -r n want pat what log <<< "$e"
        file=$OUT/zuki-sim-log.txt; [ "$log" = dash ] && file=$OUT/dash-sim-log.txt
        count=$(section "$n" "$file" | grep -c -- "$pat")
        case "$want" in
            yes) ok=$((count > 0)) ;;
            no) ok=$((count == 0)) ;;
            =*) ok=$((count == ${want#=})) ;;
            *) ok=$((count >= want)) ;;
        esac
        [ "$n" = all ] && n="whole run" || n="step $n"
        report "$ok" "$n" "$what"
    done
    trip_checks
    crash_checks
    say "Checks: $PASS OK, $FAIL to look at"
}

# The ride from step 1, as Zuki Sim saved it.
trip_checks() {
    [ "$FROM" = 1 ] || return
    local odo ride
    odo=$(section 1 "$OUT/zuki-sim-log.txt" | grep -m1 -o 'ODO [0-9]*' | cut -d' ' -f2)
    ride=$(tr -d '\n\r ' < "$OUT/zuki-sim-trips.json" | sed 's/{"start"/\n{"start"/g' | grep "\"odo0\":${odo:-x}," | head -1)
    if [ -z "$ride" ]; then report 0 "ride" "the ride from step 1 was saved"; return; fi
    tf() { printf '%s' "$ride" | grep -o "\"$1\":[^,}]*" | head -1 | cut -d: -f2; }
    report "$([ "$(tf warmUp)" = true ] && echo 1 || echo 0)" "ride" "Engine Warm Up recorded ($(tf warm0) to $(tf warm1) °C)"
    report "$(awk -v k="$(tf km)" 'BEGIN { print (k >= 30) }')" "ride" "$(tf km) km (30+ expected)"
    report "$(awk -v n="$(tf stops)" 'BEGIN { print (n >= 2) }')" "ride" "$(tf stops) stops (the 2 traffic stops)"
    report "$(awk -v k="$(tf km)" -v m="$(tf moving)" -v t="$(tf top)" 'BEGIN { print (m > 0 && k * 3600 / m <= t * 1.1) }')" \
        "ride" "average speed no higher than the top speed ($(tf top) km/h)"
}

# Neither app crashed during the run.
crash_checks() {
    adb -s "$POCO" logcat -b crash -d 2>/dev/null | tr -d '\r' > "$OUT/crashes-poco.txt"
    adb -s "$REALME" logcat -b crash -d 2>/dev/null | tr -d '\r' > "$OUT/crashes-realme.txt"
    report "$(grep -q "$SIM" "$OUT/crashes-poco.txt" && echo 0 || echo 1)" "whole run" "Zuki Sim didn't crash"
    report "$(grep -q "$DASH" "$OUT/crashes-realme.txt" && echo 0 || echo 1)" "whole run" "Zuki Dash Sim didn't crash"
}

collect() {
    say "Collecting logs into $OUT"
    pause 6 "saving logs" # Zuki Sim writes its log in batches
    simlog > "$OUT/zuki-sim-log.txt"
    adb -s "$POCO" shell run-as $SIM cat files/trips.json 2>/dev/null | tr -d '\r' > "$OUT/zuki-sim-trips.json"
    adb -s "$REALME" shell run-as $DASH cat files/dash-log.txt 2>/dev/null | tr -d '\r' > "$OUT/dash-sim-log.txt"
    state > "$OUT/dash-sim-state.txt"
    checks
    draw end; echo; FINISHED=1
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
# A reinstall can drop Zuki Sim as the mock location app: set it again.
case "$STATUS" in *mock=off*)
    adb -s "$POCO" shell appops set $SIM android:mock_location allow
    STATUS=$(simask status)
    case "$STATUS" in
        *mock=on*) say "  Made Zuki Sim the mock location app again" ;;
        *) say "  Zuki Sim isn't the mock location app: no made-up GPS, so drops are judged without it" ;;
    esac ;;
esac
adb -s "$POCO" logcat -b crash -c 2>/dev/null
adb -s "$REALME" logcat -b crash -c 2>/dev/null
mark 1

# Starting partway: fill up, switch the bike on and ride off first, as the earlier
# steps would have left it. Off and on, so Bluetooth starts clean.
if [ "$FROM" -gt 1 ]; then
    say "  Getting the bike riding for step $FROM"
    dash timex 1; dash fuel 12; dash ignition off; pause 3 "bike off"
    dash ignition on; dash engine on
    wait_until 90 "connected" connected || die "Zuki Sim didn't connect"
    dash speed 60; pause 5 riding
fi

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
    expect 1 yes "Link READY" "connected to the dash"
    expect 1 yes "Ride started" "ride started"
    expect 1 yes "Phone connected and listening" "the dash heard from the phone" dash
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
    expect 3 yes "Ride time running 60" "ride time fast-forwarded 60x"
    expect 3 no "Link DISCONNECTED" "the link stayed up"
fi

# ---- 4. Traffic stops: 5 s at 60x is a 5 min stop ---------------------------
if step 4; then
    mark 4
    for i in 1 2; do
        dash speed 0; pause 5 stopped
        dash speed 60; pause 5 riding
    done
    expect 4 2 "Stop of 5 min added" "both traffic stops added to the ride"
fi

# ---- 5. Dash drops out while riding (at 1x) ---------------------------------
if step 5; then
    mark 5
    dash timex 1; pause 2
    dash drop
    # While the dash is out: held, then shown when it's back.
    message saved
    call saved missed 1
    # Zuki Sim's own word that the link is ready: the dash can see the phone
    # before the link is fully set up.
    wait_until 90 "link back after Drop Link" sim_says_since 5 "Link READY"
    pause 20 "alerts from the dropout" # the call 12 s after connecting, the message 5 s later
    expect 5 yes "Dash dropped while riding" "dropout seen as mid-ride, not the bike off"
    expect 5 yes "Showing what came in while the dash was away" "missed call and message from the dropout shown"
    expect 5 yes "<- Missed:" "the dash got the missed call" dash
    expect 5 yes "<- Message:" "the dash got the message" dash
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
    expect 6 2 "Missed(caller=.*whatsapp=false" "missed phone calls (saved contact, rejected Caller ID)"
    expect 6 yes "Answered(caller=98765 43210" "answered call from an unknown number"
    expect 6 yes "not kept on the dash" "rejected Caller ID call not kept as missed"
    expect 6 yes "Missed(caller=.*whatsapp=true" "missed WhatsApp call"
    expect 6 =4 "Message(sender=" "4 messages on the dash: saved, unknown, group, SMS (own reply left out)"
    expect 6 yes "Message(sender=Riders Club" "group message"
    expect 6 yes "Message(sender=.*whatsapp=false" "SMS"
    expect 6 yes "ClearMissed" "clearing on the phone clears the dash"
    expect 6 yes "<- Call:" "the dash got the calls" dash
    expect 6 4 "<- Message:" "the dash got the messages" dash
fi

# ---- 7. Tea stop: off for a few minutes, alerts shown on the way out ---------
if step 7; then
    mark 7
    # Both on 30 Min: a few minutes off is well inside it.
    sim parked --es value 30
    dash speed 0; dash timex 10; pause 3
    dash engine off; dash ignition off
    wait_until 30 "dash gone" disconnected
    pause 8 "bike off"   # at 10x the app decides "switched off" in a few seconds
    call saved missed 4; pause 6 "missed call at the tea stop"
    message saved; pause 2
    dash ignition on; dash engine on
    wait_until 60 "back on" connected || die "Zuki Sim didn't reconnect after the tea stop"
    pause 20 "alerts from the tea stop" # the call 12 s after connecting, the message 5 s later
    expect 7 yes "Bike switched off" "tea stop seen as the bike off"
    expect 7 2 "Bike off, keeping it for the next ride" "tea stop missed call and message kept"
    expect 7 yes "Showing what came in while the dash was away" "tea stop missed call and message shown"
    expect 7 yes "<- Missed:" "the dash got the tea stop missed call" dash
    expect 7 yes "<- Message:" "the dash got the tea stop message" dash
fi

# ---- 8. Lunch: switch off, alerts while parked, stop searching, skip 2 h ------
if step 8; then
    mark 8
    dash speed 0; dash timex 10; pause 3
    dash engine off; dash ignition off
    wait_until 30 "dash gone" disconnected
    pause 8 "bike off"
    # Parked: calls on 30 Min (kept, but 2 h later too old for the dash),
    # messages on Never (left on the phone).
    sim parked --es value 30 --es for calls
    sim parked --es value never --es for messages
    call saved missed 4; pause 6 "missed call while parked"
    # As tapping Stop Searching For Bike: the app stops looking.
    sim stopsearch; pause 3
    message saved; pause 2
    dash timex 1
    dash skip
    expect 8 =1 "Bike off, keeping it for the next ride" "lunch missed call kept (calls on 30 Min), message not (Never)"
fi

# ---- 9. Back on the bike -----------------------------------------------------
if step 9; then
    mark 9
    dash ignition on
    # Wake On Ignition itself is tested on the real bike: a phone playing the
    # dash hides its address in a way Android's watching doesn't pick up.
    # So tap Connect, as after Stop Searching For Bike.
    sim connect
    wait_until 60 "connected again" connected || die "Zuki Sim didn't come back"
    pause 20 "nothing from lunch" # held ones would show 12 to 17 s after connecting
    expect 9 yes "Missed call from while parked is too old" "lunch missed call too old after 2 h"
    expect 9 no "Message from while parked" "lunch message left on the phone (messages on Never)"
    expect 9 no "Showing what came in" "nothing from lunch shown"
    expect 9 no "<- Missed:" "nothing from lunch reached the dash" dash
    sim parked --es value never
    dash engine on
    dash speed 60; dash timex 60
    pause 15 riding
fi

# ---- 10. Run out of fuel -----------------------------------------------------
if step 10; then
    mark 10
    dash speed 100; dash fuel 0.2
    wait_until 60 "ran dry, engine stopped" engine_off
    pause 2
    expect 10 yes "Out of fuel: the engine stopped" "the dash ran dry" dash
    expect 10 yes "-> 0 bars" "Zuki Sim saw the gauge hit empty"
fi

# ---- 11. Park, then off and on -------------------------------------------------
if step 11; then
    mark 11
    dash timex 1; dash speed 0
    dash ignition off; pause 3
    for i in 1 2; do
        dash ignition on
        wait_until 60 "reconnect $i" connected
        pause 3
        dash ignition off
        pause 3
    done
    expect 11 2 "Link READY" "reconnected after each switch-on"
fi

# Across the whole run.
expect all no "Connected again on the same link, starting" "no working link started over"

collect
