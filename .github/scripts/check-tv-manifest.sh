#!/usr/bin/env bash
# Checks the merged manifest of a built APK: the TV launcher shows only the TV interface
# and every TV component is declared. Usage: check-tv-manifest.sh <apk>
set -euo pipefail

apk="$1"
aapt2="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n 1)aapt2"

badging=$("$aapt2" dump badging "$apk")
echo "$badging" | grep -E "^(application|launchable-activity|leanback-launchable-activity):" || true
if [ "$(echo "$badging" | grep -c '^leanback-launchable-activity:')" != 1 ] ||
  ! echo "$badging" | grep -q "^leanback-launchable-activity: name='com.github.andreyasadchy.xtra.ui.tv.TvActivity'"; then
  echo "::error::The TV launcher entry is not TvActivity alone"
  exit 1
fi

manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")
for component in TvPlayerActivity TvSettingsActivity TvLoginActivity TvInstallReceiver TvInitProvider; do
  if ! echo "$manifest" | grep -q "com.github.andreyasadchy.xtra.ui.tv.$component"; then
    echo "::error::$component is missing from the merged manifest"
    exit 1
  fi
done
echo "TV manifest OK"
