#!/bin/bash
# Checks the Kotlin port against Swift BetterBlueKit:
#  1. builds Swift bbcli from ../../BetterBlueKit and Kotlin bbcli,
#  2. runs every Hyundai USA fixture through both parsers and compares,
#  3. regenerates the golden files the Kotlin tests read.
# Needs Xcode (DEVELOPER_DIR) and Android Studio's JDK (JAVA_HOME).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(cd "$HERE/../.." && pwd)"
REPO="$(cd "$ANDROID/.." && pwd)"
WORK="${TMPDIR:-/tmp}/ghostblue-parity"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
GOLDEN="$ANDROID/betterbluekit/src/test/resources/golden"
FIXTURES="$ANDROID/betterbluekit/src/test/resources/fixtures/hyundai-usa"

rm -rf "$WORK" && mkdir -p "$WORK/kit" "$WORK/redact" "$WORK/fmt"
cp -R "$REPO/BetterBlueKit/Package.swift" "$REPO/BetterBlueKit/Sources" "$REPO/BetterBlueKit/Tests" "$WORK/kit/"
(cd "$WORK/kit" && swift build --product bbcli -q)
(cd "$ANDROID" && ./gradlew -q :bbcli:installDist)

python3 "$HERE/compare_parsers.py" "$WORK/kit/.build/debug/bbcli" "$ANDROID/bbcli/build/install/bbcli/bin/bbcli" "$FIXTURES"

cp "$REPO/BetterBlueKit/Sources/BetterBlueKit/API/SensitiveDataRedactor.swift" "$WORK/redact/Redactor.swift"
cp "$HERE/RedactionGolden.swift" "$WORK/redact/main.swift"
(cd "$WORK/redact" && xcrun swiftc -O Redactor.swift main.swift -o redact && ./redact > "$GOLDEN/redaction.json")
cp "$HERE/FormattingGolden.swift" "$WORK/fmt/main.swift"
(cd "$WORK/fmt" && xcrun swiftc -O main.swift -o fmt && ./fmt > "$GOLDEN/swift-formatting.json")
"$WORK/kit/.build/debug/bbcli" parse -t status --electric "$FIXTURES/status-ev.json" \
    | python3 -c "import sys; t = sys.stdin.read(); print(t[t.index('{'):].strip())" > "$GOLDEN/swift-vehicle-status.json"
echo "Golden files refreshed; run ./gradlew :betterbluekit:test"
