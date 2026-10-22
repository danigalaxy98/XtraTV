#!/usr/bin/env bash
# The few lines of the original Xtra that the TV fork changes (see CAMBIOS-TV.md, section 2), kept as a script so they
# can be applied again after taking the original's version of those files. Both modes work from the repository root.
#   tv-touchpoints.sh apply   puts the changes back (does nothing for the ones already there)
#   tv-touchpoints.sh check   fails if any change is missing
#   tv-touchpoints.sh files   lists the files involved (a merge conflict only in these can be solved by `apply`)
set -euo pipefail

main_activity=app/src/main/java/com/github/andreyasadchy/xtra/ui/main/MainActivity.kt
app_gradle=app/build.gradle.kts
catalog=gradle/libs.versions.toml
manifest=app/src/main/AndroidManifest.xml

hook='if (com.github.andreyasadchy.xtra.ui.tv.TvHooks.openTvInterface(this)) return'
compose_alias='alias(libs.plugins.kotlin.compose)'
compose_plugin='kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }'
tv_apply='apply(from = "tv.gradle")'

missing=()

case "${1:-}" in
files)
  printf '%s\n' "$main_activity" "$app_gradle" "$catalog" "$manifest"
  ;;

apply)
  # 1. the TV interface takes over on TV: first line after super.onCreate in MainActivity.onCreate
  if ! grep -qF "TvHooks.openTvInterface" "$main_activity"; then
    line=$(awk 'index($0, "override fun onCreate(savedInstanceState: Bundle?) {") { f = 1 } f && index($0, "super.onCreate(savedInstanceState)") { print NR; exit }' "$main_activity")
    if [ -z "$line" ]; then
      echo "::error::No se encuentra MainActivity.onCreate: hay que poner a mano la llamada a TvHooks.openTvInterface"
      exit 1
    fi
    indent=$(sed -n "${line}p" "$main_activity" | sed 's/[^ ].*//')
    # head/tail keep the end of the file exactly as it is
    { head -n "$line" "$main_activity"; printf '%s%s\n' "$indent" "$hook"; tail -n +"$((line + 1))" "$main_activity"; } > "$main_activity.tmp"
    mv "$main_activity.tmp" "$main_activity"
  fi

  # 2. Compose plugin and the TV build script
  if ! grep -qF "$compose_alias" "$app_gradle"; then
    sed -i "s|^\([[:space:]]*\)alias(libs.plugins.android.application)\$|&\n\1$compose_alias|" "$app_gradle"
  fi
  if ! grep -qF "$tv_apply" "$app_gradle"; then
    [ -n "$(tail -c1 "$app_gradle")" ] && echo >> "$app_gradle"
    printf '\n// Android TV interface\n%s\n' "$tv_apply" >> "$app_gradle"
  fi

  # 3. version of the Compose plugin: the same as Kotlin
  if ! grep -qF 'org.jetbrains.kotlin.plugin.compose' "$catalog"; then
    sed -i "s|^kotlin-parcelize = |$compose_plugin\n&|" "$catalog"
  fi

  # 4. one launcher icon on TV: the one of TvActivity (declared in src/tv/AndroidManifest.xml)
  sed -i '/android.intent.category.LEANBACK_LAUNCHER/d' "$manifest"
  ;;

check)
  grep -qF "TvHooks.openTvInterface" "$main_activity" || missing+=("$main_activity: falta la llamada a TvHooks.openTvInterface en onCreate")
  grep -qF "$compose_alias" "$app_gradle" || missing+=("$app_gradle: falta el plugin kotlin.compose")
  grep -qF "$tv_apply" "$app_gradle" || missing+=("$app_gradle: falta apply(from = \"tv.gradle\")")
  grep -qF 'org.jetbrains.kotlin.plugin.compose' "$catalog" || missing+=("$catalog: falta el plugin kotlin-compose")
  if grep -qF 'android.intent.category.LEANBACK_LAUNCHER' "$manifest"; then
    missing+=("$manifest: MainActivity sigue declarando LEANBACK_LAUNCHER (dos iconos en la TV)")
  fi
  if [ "${#missing[@]}" != 0 ]; then
    for m in "${missing[@]}"; do echo "::error::$m"; done
    exit 1
  fi
  echo "TV touchpoints OK"
  ;;

*)
  echo "usage: $0 apply|check|files" >&2
  exit 2
  ;;
esac
