#!/usr/bin/env bash
# The TV interface talks to the original Xtra only through ui/tv/upstream/ (and TvHooks.kt, the entry point the
# original calls). When the original changes an API, only those files should need fixing; this keeps it true.
# Everything else in ui/tv may import only: the TV package itself, resources (R, BuildConfig, the TV layout binding),
# the plain data models, and the preference helpers (util.C, util.prefs, util.tokenPrefs, util.applyTheme).
# Usage (from the repository root): check-tv-boundary.sh
set -euo pipefail

tv=app/src/main/java/com/github/andreyasadchy/xtra/ui/tv
allowed='^import com\.github\.andreyasadchy\.xtra\.(ui\.tv\.|R$|BuildConfig$|databinding\.ActivityTvPlayerBinding$|model\.ui\.(Stream|Video|User|Game)$|util\.(C|prefs|tokenPrefs|applyTheme)$)'

bad=0
while IFS= read -r file; do
  case "$file" in
    "$tv"/upstream/*|"$tv"/TvHooks.kt) continue ;;
  esac
  while IFS= read -r line; do
    echo "::error file=$file::imports the original directly (use ui/tv/upstream): ${line#*:}"
    bad=$((bad + 1))
  done < <(grep -nE '^import com\.github\.andreyasadchy\.xtra\.' "$file" | grep -vE "^[0-9]+:${allowed#^}" || true)
done < <(find "$tv" -name '*.kt' | sort)

if [ "$bad" != 0 ]; then
  echo "TV boundary BROKEN: $bad import(s) outside ui/tv/upstream"
  exit 1
fi
echo "TV boundary OK"
