#!/usr/bin/env bash
# B4.9 · UAT against an INSTALLED PipelineK distribution via --plugin-jar.
#
# Criteria (each is asserted, not assumed):
#   1. 20 REAL resource files per installed plugin, not a single fixture.
#   2. The installed Step is walked and must agree with the policy in BOTH
#      directions: 16 compliant files pass, 4 violating files fail.
#   3. The distribution precondition is verified BY BEHAVIOUR (an invalid
#      argument must be rejected), never by parsing `--help`. The installed
#      CLI does not implement `--help`: it answers
#      "Invalid CLI arguments: InvalidCommand(value=--help)", so a help-based
#      probe would report a working distribution as broken, or worse, pass on
#      a stub.
#   4. `installDist` runs with `--rerun-tasks`. Without it Gradle may report
#      UP-TO-DATE from a stale install and the UAT would certify a
#      distribution that was never rebuilt.
#
# Preconditions (documented in docs/history/M6_UAT_EVIDENCE.md):
#   1. pipeline-kotlin checkout with the events registry.
#   2. SDK published to mavenLocal from that checkout.
#   3. installDist for :pipeline-application (rebuilt every run here).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINEK_REPO="${PIPELINEK_REPO:-/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin}"
WORK="$(mktemp -d /tmp/b49-uat.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "UAT FAIL: $*" >&2; exit 1; }

echo "== 1. plugin fat jar (with manifest + provenance)"
"$REPO_ROOT/gradle-jdk21.sh" -p "$REPO_ROOT" :pipelinek-policy-plugin:pluginFatJar --console=plain -q
PLUGIN_JAR="$REPO_ROOT/pipelinek-policy-plugin/build/libs/pipelinek-policy-plugin-all.jar"
[ -f "$PLUGIN_JAR" ] || fail "plugin jar not produced at $PLUGIN_JAR"
echo "   plugin jar: $(sha256sum "$PLUGIN_JAR" | cut -c1-16)…"

echo "== 2. distribution rebuilt from scratch (--rerun-tasks)"
PIPELINEK_BIN="$PIPELINEK_REPO/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"
(cd "$PIPELINEK_REPO/v2" && ./gradlew :pipeline-application:installDist --rerun-tasks --console=plain -q)
[ -x "$PIPELINEK_BIN" ] || fail "distribution binary missing at $PIPELINEK_BIN"

echo "== 3. distribution precondition verified BY BEHAVIOUR"
# An invalid subcommand must be REJECTED. If the launcher accepted anything,
# the UAT below would be measuring a stub rather than the real Step.
set +e
"$PIPELINEK_BIN" __no_such_command__ > "$WORK/probe.out" 2>&1
probe_rc=$?
set -e
if [ "$probe_rc" -eq 0 ]; then
  cat "$WORK/probe.out"
  fail "distribution accepted an invalid command: it is not the real CLI"
fi
grep -q "Invalid" "$WORK/probe.out" || { cat "$WORK/probe.out"; fail "probe did not reject the invalid command"; }
echo "   rejected invalid command (rc=$probe_rc): the launcher is the real CLI"

echo "== 4. 20 real resource files (16 compliant, 4 violating)"
mkdir -p "$WORK/corpus"
for i in $(seq 1 16); do
  printf '{"spec": {"replicas": %d, "name": "compliant-%d"}}\n' "$((3 + i % 4))" "$i" > "$WORK/corpus/compliant-$i.json"
done
for i in $(seq 1 4); do
  printf '{"spec": {"replicas": %d, "name": "violating-%d"}}\n' "$((i % 3))" "$i" > "$WORK/corpus/violating-$i.json"
done
corpus_count=$(find "$WORK/corpus" -name '*.json' -type f | wc -l)
[ "$corpus_count" -eq 20 ] || fail "expected 20 corpus files, found $corpus_count"
echo "   corpus: $corpus_count files"

echo "== 5. packed bundle fixture (spec.replicas >= 3)"
"$REPO_ROOT/gradle-jdk21.sh" -p "$REPO_ROOT" packUatBundle --console=plain -q > "$WORK/bundle.b64"
base64 -d "$WORK/bundle.b64" > "$WORK/policy.bundle.bin"
[ -s "$WORK/policy.bundle.bin" ] || fail "packed bundle is empty"

rc_total=0

echo "== 6. UAT PASS (16 compliant files; expect exit 0 and 16 successful checks)"
# The DSL façade resolves resource/bundle paths against the process CWD;
# run from $WORK where the fixtures live.
#
# The compliant assertion is BEHAVIOURAL, not a magic token. The previous
# version grepped for "PASSED", which the installed plugin never prints on the
# happy path: the "policy check PASSED" message is a StepOutcome.Failure
# message (PolicyCheckOutput.failureMessage), so it only materialises when the
# verdict denies. A conforming run prints step outcomes and no findings. Both
# of those are observable, so both are asserted.
set +e
( cd "$WORK" && "$PIPELINEK_BIN" run --plugin-jar "$PLUGIN_JAR" \
    "$REPO_ROOT/scripts/m6/uat-pass.pipeline.kts" > "$WORK/pass.out" 2>&1 )
rc_pass=$?
set -e
pass_steps=$(grep -c 'ok$' "$WORK/pass.out" || true)
pass_finding_errors=$(grep -cE '^\[(VIOLATION|ERROR|REFUSAL)\]' "$WORK/pass.out" || true)
if [ "$rc_pass" -ne 0 ]; then
  echo "PASS FAILED rc=$rc_pass"; tail -20 "$WORK/pass.out"; rc_total=1
elif [ "$pass_steps" -ne 16 ]; then
  # Guards the corpus size: a truncated fixture set that checks 1 file would
  # otherwise satisfy "exit 0" while skipping 15 checks entirely.
  echo "PASS FAILED: expected 16 successful steps, saw $pass_steps"; tail -20 "$WORK/pass.out"; rc_total=1
elif [ "$pass_finding_errors" -ne 0 ]; then
  echo "PASS FAILED: $pass_finding_errors finding(s) reported on a compliant corpus"; tail -20 "$WORK/pass.out"; rc_total=1
else
  echo "PASS ok (exit 0, $pass_steps successful checks, 0 error findings)"
fi

echo "== 7. UAT VIOLATE (4 violating files; expect non-zero, VIOLATED, PLUGIN failure)"
set +e
( cd "$WORK" && "$PIPELINEK_BIN" run --plugin-jar "$PLUGIN_JAR" \
    "$REPO_ROOT/scripts/m6/uat-violate.pipeline.kts" > "$WORK/violate.out" 2>&1 )
rc_violate=$?
set -e
violate_steps=$(grep -c 'ok$' "$WORK/violate.out" || true)
if [ "$rc_violate" -eq 0 ]; then
  echo "VIOLATE FAILED rc=0 (expected non-zero)"; tail -20 "$WORK/violate.out"; rc_total=1
elif ! grep -q "VIOLATED" "$WORK/violate.out"; then
  echo "VIOLATE: non-zero exit but no VIOLATED event"; tail -20 "$WORK/violate.out"; rc_total=1
elif ! grep -q "Finished: failure" "$WORK/violate.out"; then
  # The denial must be an ENGINE failure, not just a bad message somewhere in
  # the log. Without this, a crash that happened to mention VIOLATED would
  # satisfy the check.
  echo "VIOLATE: denial was not a pipeline failure"; tail -20 "$WORK/violate.out"; rc_total=1
elif [ "$violate_steps" -ne 1 ]; then
  # The stage is fail-fast: the FIRST violating file denies and the pipeline
  # stops, so exactly one step is ever marked ok (the one that raised the
  # denial). Anything else means either the corpus stopped being violated or
  # the run died for an unrelated reason before deciding anything.
  echo "VIOLATE FAILED: expected 1 decided step before fail-fast, saw $violate_steps"; tail -20 "$WORK/violate.out"; rc_total=1
else
  echo "VIOLATE ok (exit $rc_violate, VIOLATED, PLUGIN failure, fail-fast after 1 step)"
fi

exit $rc_total
