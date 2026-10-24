#!/usr/bin/env bash
# Drives the installed release APK like a user: open an audio file with the app, wait for the
# trim screen, tap "Convert to piano notes", wait for the result. Fails on a crash or a timeout.
# Needs the debug app + androidTest APKs installed (they hand the file to the release app).
set -u
asset="$1"
dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null > ui.xml
}
fail() {
  echo "FAILED ($asset): $1"
  adb logcat -d > "logcat-release-$asset.txt"
  grep -A40 "Process: com.pianoscribe.app," "logcat-release-$asset.txt" | head -60
  grep -A25 "PianoScribe" "logcat-release-$asset.txt" | head -60
  grep -o 'text="[^"]*"' ui.xml | head -40
  exit 1
}
# Only the release app's own crashes count: the uiautomator dump tool itself can time out and
# crash on a loaded emulator, which is logged as a FATAL EXCEPTION too (with no process name).
crashed() { adb logcat -d -s AndroidRuntime:E | grep -A1 "FATAL EXCEPTION" | grep -q "Process: com.pianoscribe.app,"; }
# Under load the emulator's own apps (the launcher) can stop responding and their dialog covers
# the screen: wait for them. PianoScribe itself not responding is a failure.
other_anr() {
  grep -q "isn't responding" ui.xml || return 1
  grep -q "PianoScribe isn't responding" ui.xml && fail "the app stopped responding (ANR)"
  local b
  b=$(grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ui.xml | grep -o 'bounds=.*' | head -1)
  [ -n "$b" ] || return 1
  read -r x1 y1 x2 y2 <<< "$(echo "$b" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/')"
  echo "Another app is not responding: tapping Wait"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

adb shell am force-stop com.pianoscribe.app
adb logcat -c
# In the background: the test process serves the file and must stay alive while the release app reads it.
adb shell am instrument -w -e release true -e asset "$asset" -e holdSec 300 -e class com.pianoscribe.app.ReleaseImportTest \
  com.pianoscribe.app.debug.test/androidx.test.runner.AndroidJUnitRunner > instrument.log 2>&1 &
trap 'adb shell am force-stop com.pianoscribe.app.debug >/dev/null 2>&1' EXIT

found=0
for i in $(seq 1 40); do
  sleep 2
  crashed && fail "crashed while opening the file"
  # The convert button is below the fold on a small screen: scroll down first.
  dump
  other_anr && continue
  adb shell input swipe 160 560 160 120 300
  dump
  if grep -q 'text="Convert to piano notes"' ui.xml; then found=1; break; fi
done
[ $found = 1 ] || fail "trim screen never showed the convert button"
bounds=$(grep -o 'text="Convert to piano notes"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ui.xml | grep -o 'bounds=.*' | head -1)
read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/')"
adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))

done=0
for i in $(seq 1 90); do
  sleep 2
  crashed && fail "crashed while converting"
  dump
  other_anr && continue
  if grep -q 'text="Piano Roll"' ui.xml; then done=1; break; fi
done
[ $done = 1 ] || fail "result screen never appeared"
adb shell pidof com.pianoscribe.app >/dev/null || fail "app is not running"
echo "Release APK imported and converted $asset"
