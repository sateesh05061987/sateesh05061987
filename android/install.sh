#!/usr/bin/env bash
# Installs KATAVU Usage over USB (adb) and grants EVERYTHING at install time, so there is no
# in-app setup: the mic/phone/call-log permissions are granted (-g) and the accessibility
# recorder service is switched on. Android does not allow a normal tap-install to do this.
#
#   ./install.sh                                  # installs the debug APK
#   ./install.sh path/to/app-release.apk          # installs a given APK
#
# Needs: USB debugging on, the phone connected and authorised (adb devices shows it).
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PKG=com.katavu.usage
SVC="$PKG/$PKG.recorder.CallRecorderService"
[ -f "$APK" ] || { echo "!! APK not found: $APK (build it with ./gradlew assembleDebug)"; exit 1; }

"$ADB" get-state >/dev/null 2>&1 || { echo "!! no device. Enable USB debugging and connect the phone."; exit 1; }

echo "==> Installing $APK (granting all runtime permissions)"
"$ADB" install -r -g "$APK"

echo "==> Enabling the KATAVU Usage recorder (accessibility service)"
cur="$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$cur" in
  *"$SVC"*) ;;                                   # already listed
  null|"") "$ADB" shell settings put secure enabled_accessibility_services "$SVC" ;;
  *)       "$ADB" shell settings put secure enabled_accessibility_services "$cur:$SVC" ;;
esac
"$ADB" shell settings put secure accessibility_enabled 1 >/dev/null

echo "==> Done. Permissions granted and recorder enabled — no in-app setup needed."
