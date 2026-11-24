#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-/home/box/Android/sdk}"
tools/signing.sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease -PruntimeAbi=arm64-v8a
mkdir -p dist-apk
install -m 644 app/build/outputs/apk/release/app-release.apk dist-apk/deepseek-harness-test.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist-apk/deepseek-harness-test.apk
