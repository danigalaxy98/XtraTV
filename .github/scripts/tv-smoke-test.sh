#!/usr/bin/env bash
# Smoke test of the TV build on an Android TV emulator: opens the app from the launcher, plays the first
# stream, leaves the player and opens a twitch.tv link. Fails on a crash, an ANR or a wrong screen;
# what is shown on screen (it depends on Twitch and the network) is not checked.
# Usage: tv-smoke-test.sh <apk>
set -uo pipefail

apk="$1"
pkg=com.github.andreyasadchy.xtra

fail() {
  echo "::error::$1"
  adb logcat -d -b crash | tail -n 80
  adb logcat -d | grep -E "AndroidRuntime|ANR in|$pkg" | tail -n 80
  exit 1
}

check() {
  if adb logcat -d -b crash | grep -q "$pkg"; then
    fail "crash: $1"
  fi
  if adb logcat -d | grep -q "ANR in $pkg"; then
    fail "ANR: $1"
  fi
  if [ -z "$(adb shell pidof "$pkg" | tr -d '\r')" ]; then
    fail "the app is not running: $1"
  fi
}

top_activity() {
  adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -n 1
}

key() {
  adb shell input keyevent "$1"
  sleep 2
}

adb install -r "$apk" || fail "install"
adb logcat -c
adb logcat -b crash -c || true

echo "== Launcher"
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "$pkg/.ui.tv.TvActivity"
sleep 25
check "start"
top_activity | grep -q "ui.tv.TvActivity" || fail "the launcher did not open TvActivity: $(top_activity)"

echo "== First stream"
key DPAD_RIGHT
key DPAD_CENTER
sleep 25
check "player"
echo "screen: $(top_activity)"

echo "== Overlay and back"
key DPAD_CENTER
key BACK
key BACK
sleep 5
check "leaving the player"

echo "== Channel link through MainActivity"
adb shell am start -W -a android.intent.action.VIEW -d "https://www.twitch.tv/twitch" -n "$pkg/.ui.main.MainActivity"
sleep 15
check "link"
top_activity | grep -q "ui.tv.TvActivity" || fail "the link did not open the TV interface: $(top_activity)"

key BACK
sleep 3
echo "Smoke test OK"
