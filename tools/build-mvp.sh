#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-/opt/codex-airplay-sdk}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
cd "$project_dir"
./gradlew assembleMvp --no-daemon --console=plain --max-workers=4
printf 'APK: %s/app/build/outputs/apk/mvp/app-mvp.apk\n' "$project_dir"
