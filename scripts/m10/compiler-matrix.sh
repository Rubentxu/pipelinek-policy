#!/usr/bin/env bash
# M10 REQ 02a/02b · Compiler matrix: full test suite on JDK 21 and 25 (LTS).
# FAILs if either runtime fails or the executed test counts differ.
#
# Mechanism: all modules' jvmToolchain honors -PtestJvm (M10 change);
# Gradle auto-provisions/detects the toolchain per value.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="docs/history/M10_COMPILER_MATRIX.md"

count_tests() {
  # Total <testcase> entries across every module's XML results.
  find . -path "*/build/test-results/test/*.xml" -not -path "*/binary/*" \
    -exec grep -h "<testcase " {} + | wc -l
}

run_jdk() {
  local jvm="$1" label="$2"
  echo "--- Running full test suite on $label (testJvm=$jvm)" >&2
  ./gradle-jdk21.sh test -PtestJvm="$jvm" --rerun-tasks --console=plain \
    > "/tmp/m10-matrix-$label.log" 2>&1 || {
      echo "RUN FAILED on $label:" >&2
      tail -30 "/tmp/m10-matrix-$label.log" >&2
      exit 1
    }
  echo "$(count_tests)" > "/tmp/m10-matrix-$label.count"
  echo "    tests executed: $(cat /tmp/m10-matrix-$label.count)" >&2
}

run_jdk 21 jdk21
run_jdk 25 jdk25

C21=$(cat /tmp/m10-matrix-jdk21.count)
C25=$(cat /tmp/m10-matrix-jdk25.count)
VERDICT="PASS"
if [ "$C21" -ne "$C25" ]; then VERDICT="FAIL (counts differ: $C21 vs $C25)"; fi

{
  echo "# M10 Compiler Matrix (02a/02b)"
  echo
  echo "| Runtime | Tests executed | Verdict |"
  echo "|---|---|---|"
  echo "| JVM toolchain 21 (Temurin 21.0.8 LTS) | $C21 | OK |"
  echo "| JVM toolchain 25 (Temurin 25.0.4 LTS) | $C25 | OK |"
  echo
  echo "Overall: **$VERDICT** (same suite ⇒ same count)"
  echo
  echo "Executed: $(date -u +%Y-%m-%dT%H:%M:%SZ) · SHA $(git rev-parse HEAD)"
  echo "Command: ./gradle-jdk21.sh test -PtestJvm={21,25} --rerun-tasks"
} > "$OUT"

echo "Matrix verdict: $VERDICT (21: $C21, 25: $C25)"
[ "$VERDICT" = "PASS" ]
