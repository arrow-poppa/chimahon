#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/build/selection-evidence
collect_evidence() {
  adb logcat -d > app/build/selection-evidence/logcat.txt
  adb pull /sdcard/Android/data/app.chimahon.dev/files/selection-tests app/build/selection-evidence || true
}
trap collect_evidence EXIT
adb logcat -c
./gradlew :app:connectedDebugAndroidTest -PtargetAbis=x86_64 -PnativeSelectionTests=true -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.tachiyomi.ui.dictionary.DictionaryNativeSelectionTest --no-daemon
