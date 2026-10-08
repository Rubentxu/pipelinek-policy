#!/usr/bin/env bash
# M6 REQ-08 · UAT against an INSTALLED PipelineK distribution via --plugin-jar.
#
# Preconditions (documented in docs/history/M6_UAT_EVIDENCE.md):
#   1. pipeline-kotlin checkout on branch s6-plugin-sdk (HEAD with the events
#      registry; the published v0.47.0 tag does NOT contain it).
#   2. SDK 0.47.0 published to mavenLocal (publishToMavenLocal from that branch).
#   3. installDist run for :pipeline-application (this script rebuilds it if the
#      install dir is missing).
#
# Criteria (both must hold):
#   PASS    : replicas=4 >= 3  -> exit 0, event policy.check.reported PASSED
#   VIOLATE : replicas=2 < 3   -> exit 1, FailureKind PLUGIN, event VIOLATED
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINEK_REPO="${PIPELINEK_REPO:-/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin}"
WORK="$(mktemp -d /tmp/m6-uat.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "== 1. plugin fat jar (with manifest + provenance)"
"$REPO_ROOT/gradle-jdk21.sh" -p "$REPO_ROOT" :pipelinek-policy-plugin:pluginFatJar --console=plain -q
PLUGIN_JAR="$REPO_ROOT/pipelinek-policy-plugin/build/libs/pipelinek-policy-plugin-all.jar"

echo "== 2. distribution (installDist from s6-plugin-sdk HEAD)"
PIPELINEK_BIN="$PIPELINEK_REPO/v2/pipeline-application/build/install/pipelinek/bin/pipelinek"
if [ ! -x "$PIPELINEK_BIN" ]; then
  (cd "$PIPELINEK_REPO/v2" && ./gradlew :pipeline-application:installDist --console=plain -q)
fi

echo "== 3. packed bundle fixture (spec.replicas >= 3)"
"$REPO_ROOT/gradle-jdk21.sh" -p "$REPO_ROOT" packUatBundle --console=plain -q > "$WORK/bundle.b64"
base64 -d "$WORK/bundle.b64" > "$WORK/policy.bundle.bin"
echo '{"spec": {"replicas": 4}}' > "$WORK/service.json"
echo '{"spec": {"replicas": 2}}' > "$WORK/service-bad.json"

rc_total=0

echo "== 4. UAT PASS (expect exit 0, PASSED)"
# The DSL façade resolves resource/bundle paths against the process CWD;
# run from $WORK where the fixtures live.
set +e
( cd "$WORK" && "$PIPELINEK_BIN" run --plugin-jar "$PLUGIN_JAR" \
    "$REPO_ROOT/scripts/m6/uat-pass.pipeline.kts" > "$WORK/pass.out" 2>&1 )
rc_pass=$?
set -e
grep -q "PASSED" "$WORK/pass.out" && grep -q "Pipeline finished with SUCCESS" "$WORK/pass.out"
if [ "$rc_pass" -eq 0 ]; then echo "PASS ok (exit 0, PASSED event)"; else echo "PASS FAILED rc=$rc_pass"; tail -5 "$WORK/pass.out"; rc_total=1; fi

echo "== 5. UAT VIOLATE (expect exit 1, VIOLATED, FailureKind PLUGIN)"
set +e
( cd "$WORK" && "$PIPELINEK_BIN" run --plugin-jar "$PLUGIN_JAR" \
    "$REPO_ROOT/scripts/m6/uat-violate.pipeline.kts" > "$WORK/violate.out" 2>&1 )
rc_violate=$?
set -e
grep -q "VIOLATED" "$WORK/violate.out" && grep -q "failureKind\":\"PLUGIN" "$WORK/violate.out"
if [ "$rc_violate" -ne 0 ]; then echo "VIOLATE ok (exit $rc_violate, VIOLATED event, PLUGIN failure)"; else echo "VIOLATE FAILED rc=$rc_violate (expected non-zero)"; rc_total=1; fi

exit $rc_total
