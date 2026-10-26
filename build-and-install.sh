#!/usr/bin/env bash
# Build Pogoda (tests first) and install it on the TV over ADB, with cleanyourtv's ADB client, which works where the
# adb binary is blocked. Needs JDK 17 and Android SDK 34: Android Studio brings both (its JDK is picked up below),
# or set JAVA_HOME and ANDROID_HOME yourself.
#
# Usage (USB debugging on, TV on the network):
#   TV_HOST=<tv ip> ./build-and-install.sh            build, test, install
#   TV_HOST=<tv ip> ./build-and-install.sh --show     ...and open the weather screen on the TV
# CLEANYOURTV points at a cleanyourtv checkout (default ../cleanyourtv) with its tools/venv set up.
# Through cleanyourtv's tools/relay.py use TV_HOST=127.0.0.1 TV_PORT=15555.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV's IP address}"
export TV_HOST TV_PORT="${TV_PORT:-5555}"
TOOLS="${CLEANYOURTV:-../cleanyourtv}/tools"
PY="$TOOLS/venv/bin/python"
[ -x "$PY" ] || { echo "no cleanyourtv ADB client at $TOOLS (see README.md)" >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
./gradlew --no-daemon -q testReleaseUnitTest assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
# `pm install` over the shell, not `adb install`: no on-screen confirmation can hold it up.
"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/pogoda.apk
"$PY" -I "$TOOLS/tv.py" pm install -r /data/local/tmp/pogoda.apk
"$PY" -I "$TOOLS/tv.py" rm /data/local/tmp/pogoda.apk
if [ "${1:-}" = "--show" ]; then
  "$PY" -I "$TOOLS/tv.py" am start -n tv.pogoda.imgw/.WeatherActivity
fi
