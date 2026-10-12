#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME=/opt/codex-airplay-sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mkdir -p "$ANDROID_HOME/cmdline-tools" /opt/codex-airplay-downloads
archive=/opt/codex-airplay-downloads/commandlinetools-linux-15859902_latest.zip
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl --fail --location --retry 3 --output "$archive" https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
    printf '%s  %s\n' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 "$archive" | sha256sum --check
    unzip -q "$archive" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
# SDK licenses are required for the user-requested local Android build.
set +o pipefail
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/opt/codex-airplay-downloads/sdk-licenses.log
set -o pipefail
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.0.12077973' 'cmake;3.22.1'
