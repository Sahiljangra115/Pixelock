#!/usr/bin/env bash
# Device safety gate. Answers one question: can this build trap a phone?
# Needs the debug APK built and one emulator or test phone on adb.
# Do not run it on a phone you care about: it changes settings, reboots and uninstalls.
#
#   tools/safety_check.sh [group ...]     groups: basic block pause strict shade expiry kill crash friends pay clock update monkey background cleanup
set -u

# This script reboots, rewrites accessibility settings and uninstalls. Emulators only by default.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL=$(adb devices </dev/null | awk '/^emulator-/ && $2 == "device" { print $1; exit }')
  export ANDROID_SERIAL
fi
if [ -z "$ANDROID_SERIAL" ]; then echo "No emulator found."; exit 2; fi
if [ "$(adb shell getprop ro.kernel.qemu </dev/null | tr -d '\r')" != 1 ] && [ "${ALLOW_REAL_PHONE:-}" != yes ]; then
  echo "$ANDROID_SERIAL is a real phone. Refusing. Set ALLOW_REAL_PHONE=yes on a spare phone only."
  exit 2
fi

PKG=dev.pixelock.app.debug
SVC=$PKG/dev.pixelock.app.services.AppBlockerService
ADMIN=$PKG/dev.pixelock.app.receivers.AdminReceiver
APK=app/build/outputs/apk/debug/app-debug.apk
BLOCKED=${BLOCKED_APP:-com.android.chrome}
PASS=0
FAIL=0
SHOTS=${SHOTS:-/tmp/pixelock-shots}
mkdir -p "$SHOTS"

ok()   { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; adb exec-out screencap -p > "$SHOTS/fail_$FAIL.png" 2>/dev/null; }
check(){ if eval "$2"; then ok "$1"; else bad "$1 (saw: $(front))"; fi; }

front() {
  adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|ResumedActivity' \
    | sed -E 's/.* ([a-zA-Z0-9_.]+)\/([^ ]+) .*/\1/' | tr -d '\r'
}
is_front()  { [ "$(front)" = "$1" ]; }
home_pkg()  { adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1 | tr -d '\r'; }
is_home()   { is_front "$(home_pkg)"; }
wait_for()  { for _ in $(seq 1 "$2"); do eval "$1" && return 0; sleep 1; done; return 1; }
now_ms()    { echo $(($(adb shell date +%s | tr -d '\r') * 1000)); }
crashes()   { adb logcat -b crash -d 2>/dev/null | grep -c "$PKG"; }
go_home()   { adb shell input keyevent KEYCODE_HOME; sleep 1; }

# Force stop strips the service from the setting a moment later, so one write can lose that race.
# Keep writing until the system reports it bound.
enable_service() {
  for _ in 1 2 3 4 5 6 7 8; do
    adb shell settings put secure enabled_accessibility_services "$SVC"
    adb shell settings put secure accessibility_enabled 1
    wait_for service_bound 3 && return 0
  done
  return 1
}
service_bound() { adb shell dumpsys accessibility 2>/dev/null | grep -q 'Bound services:{Service\[label=Pixelock'; }

# Replaces settings on disk. The app must be stopped first, which also unbinds the service.
seed() {
  adb shell am force-stop $PKG
  printf '%s' "$1" | adb shell "run-as $PKG sh -c 'mkdir -p files && cat > files/settings.json'"
  enable_service
  sleep 2
}
# The end password is "pixelock1". The hash is PBKDF2 SHA256, 120000 rounds, salt "c2FsdHNhbHRzYWx0c2FsdA==".
PASSWORD=pixelock1
PASSWORD_JSON='"endPasswordSalt":"c2FsdHNhbHRzYWx0c2FsdA==","endPasswordHash":"yvELNCLzkwYPZq9r2X/Bf3anTRmsUkGjluFfY9/tZrY="'
base() { echo "\"onboardingDone\":true,\"uninstallLock\":false,\"focusBlockList\":[\"$BLOCKED\"],$PASSWORD_JSON"; }
locked_base() { base | sed 's/"uninstallLock":false/"uninstallLock":true/'; }
session_json() { echo "\"session\":{\"id\":\"t\",\"startedAtMs\":$1,\"endsAtMs\":$2}"; }

# uiautomator unbinds accessibility services while it runs, and the system binds a new instance after.
ui_bounds() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 -E "$1" | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' '
  wait_for service_bound 10
}
tap_text() {
  local b; read -r -a b <<< "$(ui_bounds "$1")"
  [ "${#b[@]}" -eq 4 ] || return 1
  adb shell input tap $(((b[0] + b[2]) / 2)) $(((b[1] + b[3]) / 2))
}
slide() {
  local b; read -r -a b <<< "$(ui_bounds 'content-desc="Slide to')"
  [ "${#b[@]}" -eq 4 ] || return 1
  local y=$(((b[1] + b[3]) / 2))
  adb shell input swipe $((b[0] + 90)) $y $((b[2] - 30)) $y 700
}
# Slide, password, then the confirm button once its 10 second wait is over.
end_timer() {
  slide
  sleep 2
  adb shell input text "${1:-$PASSWORD}"
  sleep 1
  tap_text 'text="(CONFIRM|Confirm)"'
}
finish_end() {
  sleep 12
  tap_text 'text="(CONFIRM|Confirm)"'
  sleep 2
}
open_app() { adb shell am start -n $PKG/dev.pixelock.app.ui.activity.MainActivity >/dev/null; sleep 2; }
admin_active() { adb shell dumpsys device_policy 2>/dev/null | grep -q "$ADMIN"; }
open_settings_end() {
  open_app
  tap_text 'content-desc="Settings'
  sleep 2
  adb shell input swipe 500 1500 500 200 300
  sleep 1
}
# Five taps on the version row. The script cannot remove the guard itself: dpm refuses non test admins.
open_developer() {
  open_settings_end
  local b; read -r -a b <<< "$(ui_bounds 'text="Version ')"
  [ "${#b[@]}" -eq 4 ] || return 1
  for _ in 1 2 3 4 5; do adb shell input tap $(((b[0] + b[2]) / 2)) $(((b[1] + b[3]) / 2)); done
  sleep 2
}
start_blocked() { adb shell monkey -p "$BLOCKED" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

group_basic() {
  echo "== install and start"
  adb logcat -b crash -c
  adb install -r -t "$APK" >/dev/null
  adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
  seed "{$(base)}"
  check "blocker service binds" service_bound
  open_app
  check "app opens" "is_front $PKG"
  go_home
  check "launcher stays usable with the service on" is_home
  start_blocked
  check "nothing blocked with no timer" "wait_for 'is_front $BLOCKED' 6"
  sleep 3
  check "blocked app stays open with no timer" "is_front $BLOCKED"
  go_home
}

group_block() {
  echo "== timer blocks only what it should"
  seed "{$(base)}"
  open_app
  slide
  sleep 2
  adb exec-out screencap -p > "$SHOTS/timer_running.png"
  start_blocked
  check "blocked app is closed during a timer" "wait_for '! is_front $BLOCKED' 5"
  check "block screen shows" "wait_for 'is_front $PKG' 4"
  adb exec-out screencap -p > "$SHOTS/block_screen.png"
  tap_text 'text="Go home"'
  check "block screen button leads home" "wait_for is_home 4"
  sleep 3
  check "home stays reachable, no bounce loop" is_home
  start_blocked
  sleep 2
  adb shell input keyevent KEYCODE_BACK
  check "back on the block screen leaves it" "wait_for is_home 4"

  adb shell am start -a android.intent.action.DIAL >/dev/null
  sleep 2
  check "dialer never blocked" "! is_home && ! is_front $PKG"
  go_home
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 2
  check "settings never blocked" "is_front com.android.settings"
  go_home
  adb shell am start -a android.intent.action.SENDTO -d sms:5551234 >/dev/null
  sleep 2
  check "sms never blocked" "! is_home && ! is_front $PKG"
  go_home
  adb shell am start -a android.intent.action.VIEW -d 'geo:12.97,77.59' >/dev/null
  sleep 2
  check "maps never blocked" "! is_home && ! is_front $PKG"
  go_home

  adb emu gsm call 5551234 >/dev/null
  sleep 3
  check "incoming call shows during a timer" "! is_front $PKG"
  adb emu gsm cancel 5551234 >/dev/null
  sleep 2
  go_home

  open_app
  end_timer
  finish_end
  start_blocked
  check "timer can be ended, then the app opens" "wait_for 'is_front $BLOCKED' 6"
  go_home
  check "no crash so far" "[ $(crashes) -eq 0 ]"
}

group_strict() {
  echo "== strict mode holds, and lets go"
  local t; t=$(now_ms)
  adb shell dpm set-active-admin "$ADMIN" >/dev/null
  seed "{$(base),\"strict\":{\"enabled\":true}}"
  open_app
  slide
  sleep 2
  adb exec-out screencap -p > "$SHOTS/strict_timer.png"
  check "end is refused while strict holds" "[ -n \"\$(ui_bounds 'content-desc=\"Strict Mode is on')\" ]"

  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
  check "app info page is closed" "wait_for '! is_front com.android.settings' 5"
  adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null
  check "accessibility page is closed" "wait_for '! is_front com.android.settings' 6"
  adb shell am start -a android.settings.WIFI_SETTINGS >/dev/null
  sleep 3
  check "wifi settings stay open" "is_front com.android.settings"
  go_home
  adb shell am start -a android.settings.SETTINGS >/dev/null
  sleep 3
  check "main settings stay open" "is_front com.android.settings"
  go_home
  check "uninstall is refused" "! adb shell pm uninstall $PKG 2>&1 | grep -q Success"

  echo "   restarting the phone"
  adb reboot
  adb wait-for-device
  wait_for "[ \"\$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')\" = 1 ]" 120
  sleep 15
  adb shell input keyevent KEYCODE_MENU
  sleep 2
  check "service is back after restart" "wait_for service_bound 30"
  start_blocked
  check "timer still blocks after restart" "wait_for '! is_front $BLOCKED' 6"
  go_home
  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
  sleep 4
  check "restart lets go of strict" "is_front com.android.settings"
  go_home
  open_app
  check "timer can end after restart" "[ -n \"\$(ui_bounds 'content-desc=\"Slide to end')\" ]"
  end_timer
  finish_end
  go_home
  adb shell dpm remove-active-admin "$ADMIN" >/dev/null 2>&1
}

group_pause() {
  echo "== pause is easy, ending is not"
  seed "{$(base)}"
  open_app
  slide
  sleep 2
  tap_text 'text="Pause 5 min'
  sleep 2
  check "pause saved" "settings_file | grep -q '.pausesUsed.:1'"
  start_blocked
  check "a pause opens the blocked app" "wait_for 'is_front $BLOCKED' 6"
  go_home
  open_app
  tap_text 'text="Resume now'
  sleep 2
  start_blocked
  check "resume closes it again" "wait_for '! is_front $BLOCKED' 6"
  go_home
  open_app
  end_timer wrongpass
  sleep 3
  check "a wrong password ends nothing" "settings_file | grep -q '.session.:'"
  end_timer
  check "the right password still has to wait" "on_screen 'Wait'"
  check "timer is not over during the wait" "settings_file | grep -q '.session.:'"
  finish_end
  check "password and wait end the timer" "! settings_file | grep -q '.session.:'"
  go_home
  local t; t=$(now_ms)
  # Strict is on but does not hold this timer, which is how it used to slip through.
  seed "{$(base),\"strict\":{\"enabled\":true},$(session_json "$t" $((t + 600000)))}"
  open_app
  tap_text 'content-desc="Settings'
  sleep 2
  tap_text 'text="Strict Mode"'
  sleep 2
  check "strict cannot be turned off during a timer" "settings_file | grep -q '.enabled.:true'"
  go_home
  seed "{$(base)}"
}

group_shade() {
  echo "== shade lock"
  local t; t=$(now_ms)
  seed "{$(base),\"shadeLockOn\":true,$(session_json "$t" $((t + 600000)))}"
  go_home
  adb shell cmd statusbar expand-notifications
  sleep 3
  check "shade closes during a timer" "! adb shell dumpsys window | grep -q 'mCurrentFocus.*NotificationShade'"
  go_home
  seed "{$(base),\"shadeLockOn\":true}"
  go_home
  adb shell cmd statusbar expand-notifications
  sleep 3
  check "shade works with no timer" "adb shell dumpsys window | grep -q 'mCurrentFocus.*NotificationShade'"
  adb shell cmd statusbar collapse
  sleep 1
}

group_expiry() {
  echo "== a timer always ends"
  local t; t=$(now_ms)
  seed "{$(base),\"strict\":{\"enabled\":true},$(session_json "$t" $((t + 20000)))}"
  go_home
  start_blocked
  check "blocked before the end" "wait_for '! is_front $BLOCKED' 5"
  go_home
  sleep 22
  start_blocked
  check "open after the end, with no alarm and no app open" "wait_for 'is_front $BLOCKED' 6"
  sleep 3
  check "stays open after the end" "is_front $BLOCKED"
  go_home
  local past=$((t - 3600000))
  seed "{$(base),$(session_json "$past" $((past + 1000)))}"
  start_blocked
  check "a stale timer on disk blocks nothing" "wait_for 'is_front $BLOCKED' 6"
  go_home
  printf 'not json{{{' | adb shell "run-as $PKG sh -c 'cat > files/settings.json'"
  adb shell am force-stop $PKG
  enable_service
  open_app
  check "broken settings file does not stop the app" "is_front $PKG"
  go_home
  start_blocked
  check "broken settings file blocks nothing" "wait_for 'is_front $BLOCKED' 6"
  go_home
}

group_kill() {
  echo "== developer switch"
  local t; t=$(now_ms)
  seed "{$(base),\"strict\":{\"enabled\":true,\"enforcementDisabled\":true},$(session_json "$t" $((t + 600000)))}"
  start_blocked
  check "enforcement off blocks nothing" "wait_for 'is_front $BLOCKED' 6"
  go_home
  # Stock Settings on the emulator can crash in its own drawing, so look twice. The guard would close it both times.
  for _ in 1 2; do
    adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
    sleep 4
    is_front com.android.settings && break
  done
  check "enforcement off drops the settings guard" "is_front com.android.settings"
  go_home

  adb shell dpm set-active-admin "$ADMIN" >/dev/null 2>&1
  seed "{$(base),\"strict\":{\"enabled\":true}}"
  open_app
  slide
  sleep 2
  open_developer
  check "five taps on the version open the hidden screen during strict" "on_screen 'Kill switch'"
  tap_text 'text="Kill switch"'
  sleep 2
  adb shell input text OFF
  sleep 1
  tap_text 'text="(CONFIRM|Confirm)"'
  sleep 33
  tap_text 'text="(CONFIRM|Confirm)"'
  sleep 3
  check "kill switch ends a strict timer" "! settings_file | grep -q '.session.:'"
  check "kill switch drops the uninstall guard" "! admin_active"
  go_home
  start_blocked
  check "kill switch blocks nothing" "wait_for 'is_front $BLOCKED' 6"
  go_home
  seed "{$(base)}"
}

group_crash() {
  echo "== crash loop breaker"
  local t; t=$(now_ms)
  seed "{$(base),$(session_json "$t" $((t + 600000)))}"
  adb shell run-as $PKG rm -f files/failsafe files/crash_times
  for _ in 1 2 3; do
    wait_for "[ -n \"\$(adb shell pidof $PKG:blocker | tr -d '\r')\" ]" 20
    adb shell am crash "$(adb shell pidof $PKG:blocker | tr -d '\r')" >/dev/null 2>&1
    sleep 4
  done
  check "breaker trips after 3 crashes" "adb shell run-as $PKG ls files/failsafe 2>/dev/null | grep -q failsafe"
  check "no screen thrown over the launcher" "wait_for is_home 4 || ! is_front $PKG"
  sleep 3
  start_blocked
  check "blocker is inert once tripped" "wait_for 'is_front $BLOCKED' 6"
  go_home
  open_app
  sleep 10  # The "blocking has stopped" alert drops over the gear button until it times out.
  tap_text 'content-desc="Settings"'
  sleep 2
  adb exec-out screencap -p > "$SHOTS/paused_banner.png"
  tap_text 'text="Blocking is paused"'
  sleep 2
  check "user can clear the breaker" "! adb shell run-as $PKG ls files/failsafe 2>/dev/null | grep -q failsafe"
  adb shell input keyevent KEYCODE_BACK
  go_home
  sleep 2
  start_blocked
  check "blocking is back after clearing" "wait_for '! is_front $BLOCKED' 6"
  go_home
  seed "{$(base)}"
}

settings_file() { adb shell run-as $PKG cat files/settings.json | tr -d '\r'; }
on_screen() { [ -n "$(ui_bounds "$1")" ]; }

# Needs a debug build with no server configured, so FakeBackend answers.
group_friends() {
  echo "== friends"
  adb logcat -b crash -c
  seed "{$(base),\"alias\":\"Sam\"}"
  open_app
  tap_text 'content-desc="Settings"'
  sleep 1
  tap_text 'text="Friends"'
  sleep 1
  adb exec-out screencap -p > "$SHOTS/friends.png"
  check "nothing is shared before joining" "! settings_file | grep -q '\"groups\":\\[\"'"
  tap_text 'text="Make a group"'
  sleep 2
  check "making a group saves it" "settings_file | grep -q '\"groups\":\\[\"'"
  tap_text 'text="Group '
  sleep 3
  adb exec-out screencap -p > "$SHOTS/board.png"
  check "board shows friends" "on_screen 'Asha'"
  check "board marks you" "on_screen 'Sam \\(you\\)'"
  check "board has no app names" "! on_screen 'Instagram|Chrome'"
  tap_text 'text="Week"'
  sleep 2
  check "board loads other periods" "on_screen 'Asha'"
  tap_text 'text="Show code or send link"'
  sleep 2
  adb exec-out screencap -p > "$SHOTS/invite.png"
  check "invite shows a code" "on_screen 'Send link'"
  adb shell input keyevent KEYCODE_BACK
  sleep 1

  adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_name:s:test --bind account_type:s:test
  local raw; raw=$(adb shell content query --uri content://com.android.contacts/raw_contacts --projection _id | tr -d '\r' | tail -1 | grep -oE '_id=[0-9]+' | cut -d= -f2)
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw" --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Tester
  adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$raw" --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:5551234
  adb shell pm grant $PKG android.permission.READ_CONTACTS
  tap_text 'text="Invite from contacts"'
  sleep 3
  adb exec-out screencap -p > "$SHOTS/contacts.png"
  check "contact picker lists a contact" "on_screen 'Tester'"
  tap_text 'Tester'
  sleep 1
  tap_text 'text="Invite"'
  sleep 3
  adb exec-out screencap -p > "$SHOTS/contact_invite.png"
  check "a contact invite opens the message app" "! is_front $PKG"
  open_app
  adb shell input keyevent KEYCODE_BACK
  sleep 1

  adb shell am start -a android.intent.action.VIEW -d pixelock://join/23456789ABCDEFGH >/dev/null
  sleep 3
  check "a link only asks" "on_screen 'Join this group'"
  check "a link does not join by itself" "! settings_file | grep -q 23456789ABCDEFGH"
  tap_text 'text="Join"'
  sleep 2
  check "joining saves the group" "settings_file | grep -q 23456789ABCDEFGH"
  adb shell am start -a android.intent.action.VIEW -d 'pixelock://join/evil' >/dev/null
  sleep 2
  check "a bad link opens nothing strange" "is_front $PKG && ! on_screen 'Join this group'"
  check "no crash in friends" "[ $(crashes) -eq 0 ]"
  go_home
  seed "{$(base)}"
}

group_pay() {
  echo "== paid unlock"
  adb logcat -b crash -c
  local t; t=$(now_ms)
  seed "{$(base),$(session_json "$t" $((t + 600000)))}"
  go_home
  start_blocked
  sleep 3  # A UI dump unbinds the service, so it must not land in the middle of the block.
  check "block screen offers a paid pass" "wait_for \"on_screen 'Pay to open'\" 6"
  adb exec-out screencap -p > "$SHOTS/pay_offer.png"
  tap_text 'text="Pay to open'
  sleep 2
  adb exec-out screencap -p > "$SHOTS/pay_page.png"
  check "payment page opens over a running timer" "is_front $PKG"
  check "nothing is unlocked before the server says paid" "! settings_file | grep -q 'unlocks.:\\[{'"
  check "paid app opens" "wait_for 'is_front $BLOCKED' 15"
  check "unlock is saved for that app only" "settings_file | grep -q 'unlocks.:\\[{[^}]*packageName.:.$BLOCKED'"
  sleep 3
  check "paid app stays open" "is_front $BLOCKED"
  check "order is cleared after the grant" "settings_file | grep -q 'pendingOrders.:\\[\\]'"
  go_home

  seed "{$(base)}"
  adb shell dpm set-active-admin "$ADMIN" >/dev/null 2>&1
  seed "{$(base),\"strict\":{\"enabled\":true}}"
  open_app
  slide
  sleep 2
  check "strict timer offers a paid way out" "on_screen 'Pay to end'"
  tap_text 'text="Pay to end'
  sleep 2
  adb shell input keyevent KEYCODE_BACK
  sleep 1
  check "leaving the payment page changes nothing" "settings_file | grep -q session"
  open_app
  tap_text 'text="Pay to end'
  check "paying ends the strict timer" "wait_for '! settings_file | grep -q .session.:' 15"
  go_home
  start_blocked
  check "apps open after paying" "wait_for 'is_front $BLOCKED' 6"
  go_home
  adb shell dpm remove-active-admin "$ADMIN" >/dev/null 2>&1
  check "no crash in payment" "[ $(crashes) -eq 0 ]"
  seed "{$(base)}"
}

# Needs a rootable emulator image, for setting the clock.
group_clock() {
  echo "== changing the date does not end strict"
  adb root >/dev/null
  sleep 3
  adb wait-for-device
  adb shell settings put global auto_time 0
  adb shell dpm set-active-admin "$ADMIN" >/dev/null 2>&1
  seed "{$(base),\"strict\":{\"enabled\":true}}"
  open_app
  slide
  sleep 2
  go_home
  adb shell date "$(date -d '+2 days' +%m%d%H%M%Y.%S)" >/dev/null
  sleep 3
  open_app
  go_home
  start_blocked
  check "date moved forward, app still closed" "wait_for '! is_front $BLOCKED' 6"
  check "date moved forward, timer still saved" "settings_file | grep -q '.session.:'"
  go_home
  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
  check "date moved forward, app info still closed" "wait_for is_home 6"
  adb shell date "$(date +%m%d%H%M%Y.%S)" >/dev/null
  adb shell settings put global auto_time 1
  sleep 2
  open_app
  check "date back, strict still holds" "on_screen 'Pay to end'"
  go_home
  adb shell run-as $PKG rm -f files/settings.json
  adb shell am force-stop $PKG
  adb shell dpm remove-active-admin "$ADMIN" >/dev/null 2>&1
  adb unroot >/dev/null
  sleep 3
  adb wait-for-device
  seed "{$(base)}"
}

group_update() {
  echo "== update keeps data and service"
  seed "{$(base),\"shadeLockOn\":true}"
  adb install -r -t "$APK" >/dev/null
  sleep 3
  check "settings survive an update" "adb shell run-as $PKG cat files/settings.json | grep -q shadeLockOn.:true"
  check "service is back after an update" "wait_for service_bound 20"
}

group_monkey() {
  echo "== 4000 random taps"
  seed "{$(base)}"
  adb logcat -b crash -c
  adb shell monkey -p $PKG --pct-syskeys 0 --pct-nav 5 --throttle 40 -s 42 4000 2>&1 | grep -E 'Events injected|CRASH|ANR|aborted' | head -5
  check "no crash or freeze" "[ $(crashes) -eq 0 ]"
  go_home
  check "launcher usable after monkey" is_home
}

# Stop in Active apps kills the blocker for good, so the app must never get that row.
group_background() {
  echo "== nothing stays up after the app is closed"
  seed "{$(base)}"
  open_app
  go_home
  check "no foreground service with no timer" "! adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"
  local now; now=$(now_ms)
  seed "{$(base),$(session_json "$now" $((now + 600000)))}"
  check "no foreground service during a timer" "! adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"
  check "the blocker does not hold the main process" "! adb shell dumpsys activity services $PKG | grep -q MultiInstanceInvalidationService"
  seed "{$(base)}"
  open_settings_end
  check "Settings does not offer the short video switch" "! on_screen 'Block short videos'"
  go_home
  open_developer
  check "the hidden screen has the short video switch" "on_screen 'Block short videos'"
  go_home
}

# The lock has to hold with no timer, and must never close a page setup needs.
group_lock() {
  echo "== uninstall lock and install block, with no timer"
  adb shell dpm set-active-admin "$ADMIN" >/dev/null 2>&1
  seed "{$(locked_base)}"
  go_home
  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
  check "app info page is closed" "wait_for '! is_front com.android.settings' 6"
  adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null
  sleep 4
  check "accessibility list stays open for setup" "is_front com.android.settings"
  go_home
  adb shell am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE $PKG >/dev/null
  sleep 4
  check "notification page stays open for setup" "is_front com.android.settings"
  go_home
  adb shell am start -a android.settings.WIFI_SETTINGS >/dev/null
  sleep 3
  check "wifi settings stay open" "is_front com.android.settings"
  go_home
  # The switch page refuses to open as a new task, so it has to be reached from its list.
  # Settings must start fresh, or Android just brings its last page back.
  adb shell am force-stop com.android.settings
  adb shell 'am start -n "com.android.settings/.Settings\$DeviceAdminSettingsActivity"' >/dev/null
  sleep 3
  tap_text 'text="Pixelock'
  check "device admin switch page is closed" "wait_for '! is_front com.android.settings' 6"
  check "uninstall is refused with no timer" "! adb shell pm uninstall $PKG 2>&1 | grep -q Success"
  seed "{$(locked_base),\"strict\":{\"enforcementDisabled\":true}}"
  go_home
  for _ in 1 2; do
    adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG >/dev/null
    sleep 4
    is_front com.android.settings && break
  done
  check "kill switch state lets go of the lock" "is_front com.android.settings"
  go_home
  seed "{$(base),\"bannedApps\":[{\"name\":\"Blocked\",\"packages\":[\"$BLOCKED\"]}]}"
  go_home
  start_blocked
  check "a banned app stays closed with no timer" "wait_for '! is_front $BLOCKED' 6"
  go_home
  seed "{$(base)}"
  adb shell dpm remove-active-admin "$ADMIN" >/dev/null 2>&1
}

group_cleanup() {
  echo "== app leaves cleanly"
  adb shell dpm set-active-admin "$ADMIN" >/dev/null 2>&1
  seed "{$(locked_base)}"
  open_settings_end
  check "Settings does not offer uninstall" "! on_screen 'Uninstall lock'"
  check "Settings does not offer the install block" "! on_screen 'Apps you cannot install'"
  go_home
  open_developer
  check "the hidden screen has the install block" "on_screen 'Apps you cannot install'"
  tap_text 'text="Uninstall lock"'
  sleep 2
  check "the hidden screen turns off the uninstall lock" "! admin_active"
  adb shell am force-stop $PKG
  check "uninstall works when no timer runs" "adb shell pm uninstall $PKG 2>&1 | grep -q Success"
  check "phone still fine after uninstall" "go_home; is_home"
}

GROUPS_TO_RUN=${*:-basic block pause strict shade expiry kill crash friends pay clock update monkey background lock cleanup}
for g in $GROUPS_TO_RUN; do "group_$g"; done
echo
echo "passed $PASS, failed $FAIL. Screens in $SHOTS"
[ "$FAIL" -eq 0 ]
