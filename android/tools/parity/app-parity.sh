#!/bin/bash
# Checks the Android data layer (android/data) against the iOS app's own code:
#  1. writes the backup the Android code produces,
#  2. copies the iOS project (everything but android/) to a scratch folder,
#     adds ios/AndroidParityGoldens.swift to its tests and runs that on a
#     simulator: it writes golden files from the Swift code (a backup, charge
#     sessions, the SDG&E schedule, tariffs, battery defaults, CSV) and
#     imports Android's backup,
#  3. runs the Android data tests, which compare against those files.
# The real iOS project is never touched. Needs Xcode (DEVELOPER_DIR), an iOS
# simulator runtime and Android Studio's JDK (JAVA_HOME). Set PARITY_SIMULATOR
# to a simulator UDID to choose one; otherwise a booted iPhone is used.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(cd "$HERE/../.." && pwd)"
REPO="$(cd "$ANDROID/.." && pwd)"
WORK="${TMPDIR:-/tmp}/ghostblue-app-parity"
OUT="$WORK/out"
GOLDEN="$ANDROID/data/src/test/resources/golden"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

rm -rf "$WORK" && mkdir -p "$OUT" "$GOLDEN"

echo "1/3 Writing Android's backup"
(cd "$ANDROID" && ./gradlew -q :data:testDebugUnitTest \
    --tests '*SwiftAppParityTest.writeAndroidBackupForTheIPhone' -PparityOut="$OUT")
test -s "$OUT/android-backup.ghostbluebackup"

echo "2/3 Running the iOS code on a simulator"
rsync -a --exclude android --exclude .git --exclude .build --exclude DerivedData --exclude xcuserdata \
    "$REPO/" "$WORK/repo/"
cp "$HERE/ios/AndroidParityGoldens.swift" "$WORK/repo/GhostBlueTests/"
SIMULATOR="${PARITY_SIMULATOR:-$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = [d for runtime, ds in json.load(sys.stdin)["devices"].items() if "iOS" in runtime for d in ds]
phones = [d for d in devices if d["name"].startswith("iPhone") and "Duo" not in d["name"]]
booted = [d for d in phones if d["state"] == "Booted"]
print((booted or phones)[0]["udid"])')}"
# TEST_RUNNER_ variables reach the test process without the prefix.
TEST_RUNNER_PARITY_DIR="$OUT" xcodebuild test \
    -project "$WORK/repo/GhostBlue.xcodeproj" -scheme GhostBlue \
    -destination "platform=iOS Simulator,id=$SIMULATOR" \
    -derivedDataPath "$WORK/DerivedData" \
    -only-testing:GhostBlueTests/AndroidParityGoldens \
    CODE_SIGNING_ALLOWED=NO > "$WORK/xcodebuild.log" 2>&1 || {
        grep -E "error:|failed|Executed" "$WORK/xcodebuild.log" | tail -30
        echo "xcodebuild failed; full log in $WORK/xcodebuild.log"
        exit 1
    }
grep -E "Executed [0-9]+ test" "$WORK/xcodebuild.log" | tail -1
for file in ios-backup.ghostbluebackup ios-backup-expectations.json android-backup-import.json \
    android-backup-via-ios.ghostbluebackup charge-scenarios.json sdge-schedule.json tariff-costs.json battery-catalog.json; do
    test -s "$OUT/$file" || { echo "The iOS run did not write $file"; exit 1; }
done
cp "$OUT"/*.json "$OUT"/*.ghostbluebackup "$GOLDEN/"

echo "3/3 Comparing on Android"
(cd "$ANDROID" && ./gradlew -q :data:testDebugUnitTest)
echo "Golden files refreshed in ${GOLDEN#$REPO/}; Android matches the iOS code."
