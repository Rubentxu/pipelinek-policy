#!/usr/bin/env bash
# gradle/live-mutation-probe.sh — M2 mutation gate live probe.
#
# For each of the three pre-registered mutation gate items (RESOURCE_DECODER_SPI
# §11), this script:
#
#   1. Snapshots the source file (cp to $JCODE_SCRATCH_DIR).
#   2. Applies a one-line source mutation that flips the locked invariant.
#   3. Runs the targeted test.
#   4. Confirms the test FAILS (because the invariant is now broken).
#   5. Restores the original source from the snapshot.
#   6. Runs the test again to confirm it PASSES (sanity).
#
# Exit code: 0 if every probe confirms the lock (FAIL under mutation, PASS
# after restore). Non-zero otherwise.
#
# IMPORTANT: this script operates in $JCODE_SCRATCH_DIR (or /tmp as a
# fallback) and never leaves the working tree dirty.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JDK21="$REPO_ROOT/gradle-jdk21.sh"
SCRATCH="${JCODE_SCRATCH_DIR:-/tmp}"
mkdir -p "$SCRATCH/m2-mutation-probes"

# sha_before_source_snap snapshots the file to a unique scratch path
# and prints the path. Returns the absolute path.
snapshot() {
  local rel="$1"
  local label="$2"
  local snap="$SCRATCH/m2-mutation-probes/${label}.snap"
  cp "$REPO_ROOT/$rel" "$snap"
  echo "$snap"
}

# restore copies the snap back into the working tree.
restore() {
  local rel="$1"
  local snap="$2"
  cp "$snap" "$REPO_ROOT/$rel"
}

# sha256 of original
record_sha() {
  sha256sum "$REPO_ROOT/$1" | awk '{print $1}'
}

# Module path lookup for a test class fqn.
module_of() {
  case "$1" in
    com.pipelinek.policy.decoders.json.*) echo "policy-decoders-json";;
    com.pipelinek.policy.decoders.yaml.*) echo "policy-decoders-yaml";;
    com.pipelinek.policy.decoders.csv.*) echo "policy-decoders-csv";;
    com.pipelinek.policy.decoders.map.*) echo "policy-decoders-map";;
    *) echo "";;
  esac
}

# run_test_rerun runs the targeted test with --rerun-tasks and prints
# the trailing summary. Exits 0 on PASS, non-zero on FAIL.
run_test_rerun() {
  local target="$1"
  local module
  module="$(module_of "$target")"
  "$JDK21" --offline --no-daemon ":${module}:test" --tests "${target}" --rerun-tasks >/tmp/probe_out.log 2>&1
  local rc=$?
  tail -5 /tmp/probe_out.log
  return $rc
}

# probe runs a single mutation. Args: label, file_rel, mutation_snippet_file,
# test_target. The mutation snippet is written to a tmpfile by the caller
# so we don't fight bash heredoc scoping inside the function.
probe() {
  local label="$1" file_rel="$2" mut_file="$3" test_target="$4"
  local file_abs="$REPO_ROOT/$file_rel"
  local sha_before
  sha_before=$(record_sha "$file_rel")
  local snap
  snap=$(snapshot "$file_rel" "$label")
  echo "── probe: $label ──"
  echo "  file:        $file_rel"
  echo "  sha_before:  $sha_before"
  echo "  test_target: $test_target"
  echo "  mutation:    $(cat "$mut_file")"

  # Step 1: sanity PASS check (original source).
  if run_test_rerun "$test_target" >/dev/null 2>&1; then
    echo "  ✔ sanity PASS (original source makes the test pass)"
  else
    echo "  ✘ sanity FAIL — original source does NOT make the test pass; aborting probe"
    return 1
  fi

  # Step 2: apply mutation by writing the snippet to a tmp file, then
  # reading it from python. This avoids fighting bash heredoc scoping.
  MUT_TMP="$SCRATCH/m2-mutation-probes/${label}.mut"
  cp "$mut_file" "$MUT_TMP"
  MUT_PATH="$MUT_TMP" FILE_PATH="$file_abs" python3 <<'PY'
import os, sys
path = os.environ["FILE_PATH"]
src = open(path).read()
mut = open(os.environ["MUT_PATH"]).read()
new_src = src
if mut.startswith("swap:"):
    a, b = mut[5:].split("||", 1)
    if a not in src:
        sys.stderr.write(f"  ✘ sentinel not found: {a!r}\n")
        sys.exit(2)
    new_src = src.replace(a, b, 1)
elif mut.startswith("delete:"):
    a = mut[7:]
    if a not in src:
        sys.stderr.write(f"  ✘ sentinel not found: {a!r}\n")
        sys.exit(2)
    new_src = src.replace(a, "", 1)
else:
    sys.stderr.write(f"  ✘ unknown mutation op: {mut[:20]!r}\n")
    sys.exit(2)
open(path, "w").write(new_src)
PY
  echo "  mutation applied"

  # Step 3: run the targeted test, expect FAIL.
  if run_test_rerun "$test_target" >/dev/null 2>&1; then
    echo "  ✘ MUTATION DOES NOT KILL THE TEST — gate item is broken"
    restore "$file_rel" "$snap"
    return 1
  fi
  echo "  ✔ mutation FAILS the targeted test (lock confirmed)"

  # Step 4: restore.
  restore "$file_rel" "$snap"
  local sha_after
  sha_after=$(record_sha "$file_rel")
  if [ "$sha_before" = "$sha_after" ]; then
    echo "  ✔ restore OK (sha_before == sha_after = $sha_before)"
  else
    echo "  ✘ RESTORE FAILED: sha_before=$sha_before sha_after=$sha_after"
    return 1
  fi

  # Step 5: post-restore PASS check.
  if run_test_rerun "$test_target" >/dev/null 2>&1; then
    echo "  ✔ post-restore PASS (lock is non-destructive)"
    return 0
  fi
  echo "  ✘ post-restore FAIL — source mutation was not fully reversed"
  return 1
}

FAIL=0

# Mutation snippets are passed via tmpfiles to avoid heredoc scoping issues.
MUT1="$SCRATCH/m2-mutation-probes/mut-csv-text-only.txt"
cat >"$MUT1" <<'EOF'
swap:ValueNode.TextValue(raw)||ValueNode.NumberValue(raw.toLongOrNull() ?: 0L)
EOF
probe "csv-text-only" \
  "policy-decoders-csv/src/main/kotlin/com/pipelinek/policy/decoders/csv/CsvResourceDecoder.kt" \
  "$MUT1" \
  "com.pipelinek.policy.decoders.csv.CsvDecoderTest"
[ $? -eq 0 ] || FAIL=$((FAIL + 1))

MUT2="$SCRATCH/m2-mutation-probes/mut-json-dup-key.txt"
cat >"$MUT2" <<'EOF'
delete:                if (!seen.add(name)) {
EOF
probe "json-dup-key" \
  "policy-decoders-json/src/main/kotlin/com/pipelinek/policy/decoders/json/JsonResourceDecoder.kt" \
  "$MUT2" \
  "com.pipelinek.policy.decoders.json.JsonDecoderTest"
[ $? -eq 0 ] || FAIL=$((FAIL + 1))

MUT3="$SCRATCH/m2-mutation-probes/mut-csv-schema-frozen.txt"
cat >"$MUT3" <<'EOF'
delete:            val violation = findSchemaViolation(header, dataRows, schema)
            if (violation != null) return DecodeResult.Refused(violation)
EOF
probe "csv-schema-frozen" \
  "policy-decoders-csv/src/main/kotlin/com/pipelinek/policy/decoders/csv/CsvResourceDecoder.kt" \
  "$MUT3" \
  "com.pipelinek.policy.decoders.csv.CsvDecoderTest"
[ $? -eq 0 ] || FAIL=$((FAIL + 1))

echo
if [ "$FAIL" -eq 0 ]; then
  echo "── ALL 3 LIVE MUTATIONS CONFIRMED ──"
  echo "Each item: original PASS, mutation FAIL, restored PASS."
  exit 0
else
  echo "── $FAIL of 3 LIVE MUTATIONS FAILED ──"
  exit 1
fi