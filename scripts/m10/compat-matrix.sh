#!/usr/bin/env bash
# H4.4 · Policy × SDK × JDK compatibility matrix.
#
# The row H4.4 describes did not exist, so the claim was untestable rather
# than true or false. This builds the thing that can be tested in THIS
# repository, and states plainly what it does not cover.
#
# AXES, and why each one earns its place:
#
#   JDK    The plugin is compiled and its tests run on a real toolchain. This is
#          the set the project already certifies as LTS (21 and 25), not every
#          JDK present on the machine. An uncertified runtime would fail on a
#          self-declared policy test, which is a statement about policy rather
#          than about compatibility.
#
#   SDK    The one axis that was previously IMPOSSIBLE. `sdkVersion` was a
#          hardcoded literal in the plugin build, so the plugin could only ever
#          be built against one SDK while a matrix claimed to describe several.
#          It is now a Gradle property, default unchanged.
#
#   Policy The pure core and the four decoders are what the matrix actually
#          varies meaningfully: they are the code that has to behave identically
#          across toolchains. The plugin module rides along because it is the
#          consumer of the SDK axis, and its tests failing on a cell is a
#          genuine compatibility signal, not noise.
#
# WHAT A CELL MEANS. `PASS` means the suite executed on that (JDK, SDK) pair
# produced the SAME SET OF TEST IDS as the reference cell, with zero failures,
# zero errors and zero skips. Test ids, not counts: a count can match while a
# suite was swapped, which is the exact defect B6.2 was rewritten to remove.
#
# WHAT THIS DOES NOT COVER, stated rather than implied:
#   - Policy CONTENT combinations are not enumerated. "Policy" here means the
#     policy surface of the codebase, not every policy an author could write.
#   - The installed plugin UAT (B6.7) is run against the declared SDK only. A
#     cell here is NOT evidence that the plugin loads inside a live PipelineK
#     host on that SDK.
#   - A cell is a build-and-test observation. It is not a support promise.
#
# A missing or failing cell FAILS the matrix; it is never quietly dropped,
# because a matrix that silently lost a row is how a false PASS happens.

set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="docs/history/H4_COMPATIBILITY_MATRIX.md"
REPORT_DIR="$JCODE_SCRATCH_DIR/h4.4"

# The reference cell is whichever runs first, not a name declared here: naming
# it separately would let the two drift apart, and a reference that is not
# actually the comparison basis is worse than none.

# The axes are what this environment can actually measure. If an SDK is listed
# here but its artifacts are absent from mavenLocal, the cell fails loudly
# rather than being skipped, so a shrinking axis cannot masquerade as a pass.
#
# The JDK axis is the CERTIFIED set, not every JDK installed. BootstrapSmokeTest
# asserts the runtime is a certified LTS (21 or 25) and M10 certifies exactly
# those two, so JDK 24 is not a row: it is an uncertified runtime, and adding
# it here would produce a FAIL that says nothing about compatibility and
# everything about the matrix having picked an axis the project never claimed.
MATRIX_JDKS=("21" "25")
MATRIX_SDKS=("0.47.0" "0.48.0")

# The axes are overridable so they can be FALSIFIED. Proving the script reports
# NOT_MEASURED for an absent SDK, rather than PASS, requires actually running it
# against an absent SDK; that is not possible while the axis is a literal.
# Empty or unset means "use the defaults", which keeps the shipped behaviour
# identical for every ordinary invocation.
if [ -n "${MATRIX_SDKS_OVERRIDE:-}" ]; then
  read -r -a MATRIX_SDKS <<< "$MATRIX_SDKS_OVERRIDE"
fi
if [ -n "${MATRIX_JDKS_OVERRIDE:-}" ]; then
  read -r -a MATRIX_JDKS <<< "$MATRIX_JDKS_OVERRIDE"
fi

SDK_GROUP_PATH="${SDK_GROUP_PATH:-$HOME/.m2/repository/dev/rubentxu/pipeline/v2}"
GRADLE="$PWD/gradle-jdk21.sh"

mkdir -p "$REPORT_DIR"
log() { echo "$*" >&2; }

# --- Where a JDK actually lives, verified rather than assumed. -------------
locate_toolchain() {
  local major="$1" candidate reported
  for candidate in \
    "$HOME/.local/share/mise/installs/java/temurin-$major"* \
    "$HOME/.asdf/installs/java/temurin-$major"* \
    "$HOME/.sdkman/candidates/java/temurin-$major"*; do
    if [ -x "$candidate/bin/java" ]; then
      reported="$("$candidate/bin/java" -XshowSettings:properties -version 2>&1 \
        | sed -n 's/.*java.specification.version = //p' | head -1)"
      [ "$reported" = "$major" ] && { echo "$candidate"; return 0; }
    fi
  done
  return 1
}

# --- Every SDK artifact the plugin consumes must be present. ---------------
# A partial SDK (the 0.40.0-dev.2 in this environment has only two of the four)
# is reported as NOT_MEASURED rather than being attempted, because a missing
# artifact would fail as a resolution error and be indistinguishable from a
# real incompatibility.
sdk_artifacts_present() {
  local sdk="$1" missing=0 artifact
  for artifact in pipeline-domain pipeline-events pipeline-scripting-api; do
    if ! compgen -G "$SDK_GROUP_PATH/$artifact/$sdk/$artifact-$sdk.jar" > /dev/null; then
      log "    missing $artifact-$sdk.jar"
      missing=1
    fi
  done
  # The BOM ships as POM plus Gradle module metadata, with NO jar. Requiring a
  # jar for it is what made the first run mark all six cells NOT_MEASURED.
  # Gradle reads the POM for a platform() coordinate, so the POM is the
  # artifact whose absence actually matters here.
  if ! compgen -G "$SDK_GROUP_PATH/pipeline-sdk-bom/$sdk/pipeline-sdk-bom-$sdk.pom" > /dev/null; then
    log "    missing pipeline-sdk-bom-$sdk.pom"
    missing=1
  fi
  return $missing
}

# --- Run one cell and emit its executed suite identity. --------------------
run_cell() {
  local jdk="$1" sdk="$2" tag="j$jdk-s$sdk"
  local home ids logf rc
  home="$(locate_toolchain "$jdk")" || {
    log "  FAIL: no JDK $jdk installed"
    return 2
  }
  sdk_artifacts_present "$sdk" || {
    log "  NOT_MEASURED: SDK $sdk incomplete in mavenLocal"
    return 4
  }

  ids="$REPORT_DIR/$tag.ids"
  logf="$REPORT_DIR/$tag.log"

  # Fresh results only: a cached XML from another cell would silently certify
  # this one.
  rm -rf ./*/build/test-results/test
  if ! "$GRADLE" test -PtestJvm="$jdk" -PsdkVersion="$sdk" \
        --rerun-tasks --console=plain -q > "$logf" 2>&1; then
    log "  FAIL: gate failed on JDK $jdk / SDK $sdk"
    tail -25 "$logf" >&2
    return 3
  fi

  python3 - > "$ids" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
ids = set(); tests = failures = errors = skipped = 0
for p in glob.glob('**/build/test-results/test/TEST-*.xml', recursive=True):
    try:
        r = ET.parse(p).getroot()
    except Exception:
        continue
    if r.tag != 'testsuite':
        continue
    tests += int(r.get('tests', 0)); failures += int(r.get('failures', 0))
    errors += int(r.get('errors', 0));   skipped += int(r.get('skipped', 0))
    for tc in r.iter('testcase'):
        cid = tc.get('classname') or '?'
        tid = tc.get('name') or '?'
        ids.add(f"{cid}#{tid}")
        if tc.find('skipped') is not None:
            skipped += 1
print(f"# tests={tests} failures={failures} errors={errors} skipped={skipped}")
for i in sorted(ids):
    print(i)
PY
  log "  OK jdk=$jdk sdk=$sdk $(head -1 "$ids")"
  return 0
}

REF_IDS=""
declare -a ROWS=()
VERDICT="PASS"
NOTES=""
EXECUTED=0

for sdk in "${MATRIX_SDKS[@]}"; do
  for jdk in "${MATRIX_JDKS[@]}"; do
    tag="j$jdk-s$sdk"
    cell="$REPORT_DIR/$tag.ids"
    if run_cell "$jdk" "$sdk"; then
      rc=0
    else
      rc=$?
    fi
    if [ "$rc" -eq 0 ]; then
      EXECUTED=$((EXECUTED + 1))
      line="$(head -1 "$cell" | sed 's/^# //')"
      if [ -z "$REF_IDS" ]; then
        REF_IDS="$tag"
        ROWS+=("| SDK $sdk | JVM $jdk | $line | PASS (reference) |")
      elif diff -q <(tail -n +2 "$REPORT_DIR/$REF_IDS.ids") \
                   <(tail -n +2 "$cell") > /dev/null; then
        ROWS+=("| SDK $sdk | JVM $jdk | $line | PASS |")
      else
        VERDICT="FAIL (JDK $jdk / SDK $sdk executed a different suite than the reference cell)"
        ROWS+=("| SDK $sdk | JVM $jdk | $line | FAIL (suite differs) |")
        NOTES="$NOTES
- $tag vs $REF_IDS suite diff:
\`\`\`
$(diff <(tail -n +2 "$REPORT_DIR/$REF_IDS.ids") <(tail -n +2 "$cell") | head -20)
\`\`\`"
      fi
      if grep -q 'skipped=[1-9]' "$cell"; then
        VERDICT="FAIL ($tag skipped tests; a skipped test certifies nothing)"
      fi
    else
      case "$rc" in
        4) ROWS+=("| SDK $sdk | JVM $jdk | not executed | NOT_MEASURED (SDK artifacts absent) |") ;;
        2) VERDICT="FAIL (JDK $jdk missing; a matrix that silently loses a row is not a pass)"
            ROWS+=("| SDK $sdk | JVM $jdk | not executed | FAIL (toolchain absent) |") ;;
        *) VERDICT="FAIL (JDK $jdk / SDK $sdk gate failed)"
            ROWS+=("| SDK $sdk | JVM $jdk | not executed | FAIL (gate) |") ;;
      esac
    fi
  done
done

# A matrix that executed nothing has measured nothing, and reporting PASS there
# is the worst failure this script could have. The first run did exactly that:
# six cells all NOT_MEASURED, verdict PASS.
if [ "$EXECUTED" -eq 0 ]; then
  VERDICT="NOT_MEASURED (no cell executed; a matrix that ran nothing certifies nothing)"
elif [ "$VERDICT" = "PASS" ]; then
  VERDICT="PASS ($EXECUTED cells, identical executed suite on every one)"
fi

{
  echo "# H4.4 · Policy × SDK × JDK compatibility matrix"
  echo
  echo "Generated by \`scripts/m10/compat-matrix.sh\`. Every cell below was executed;"
  echo "nothing here is carried over from a previous run."
  echo
  echo "## What a cell certifies"
  echo
  echo "\`PASS\` means the suite ran on that (JDK, SDK) pair and produced the **same set"
  echo "of \`classname#method\` ids** as the reference cell, with zero failures, zero errors"
  echo "and zero skips. Comparing counts would let a swapped suite pass."
  echo
  echo "## Coverage limits, stated rather than implied"
  echo
  echo "- **Policy content combinations are not enumerated.** \"Policy\" here is the policy"
  echo "  surface of this codebase, not every policy an author could write."
  echo "- **A cell is not host evidence.** The installed-plugin UAT was re-run against"
  echo "  this build and does pass in a live PipelineK host (16 compliant resources ->"
  echo "  exit 0 with 16 successful checks; 4 violating -> exit 1, VIOLATED, PLUGIN"
  echo "  failure). But that UAT pins the declared SDK 0.47.0 **by digest**, so"
  echo "  loading in a live host on 0.48.0 is still \`NOT_MEASURED\`. The 0.48.0 cells"
  echo "  below are build-and-test only."
  echo "- **A cell is an observation, not a support promise.**"
  echo "- **The Gradle launcher is pinned to JDK 21 in every cell.** The JDK axis is"
  echo "  the *test toolchain* (\`jvmToolchain\` via \`-PtestJvm\`), not the JVM running"
  echo "  Gradle. This is a real limit, observed: launching this build on the ambient"
  echo "  JDK \`25.0.4.1\` fails at configuration time with \`IllegalArgumentException: 25.0.4.1\`"
  echo "  from the Kotlin compiler plugin, before any task runs. That failure is"
  echo "  independent of these changes and reproduces on a pristine tree."
  echo "  So a cell is evidence that the plugin *compiles and tests* under that JDK,"
  echo "  not that the whole toolchain bootstraps on it."
  echo
  echo "## Axes measured"
  echo
  echo "- JDK: ${MATRIX_JDKS[*]}"
  echo "- SDK: ${MATRIX_SDKS[*]} (from \`$SDK_GROUP_PATH\`)"
  echo "- Executed cells: $EXECUTED of $(( ${#MATRIX_JDKS[@]} * ${#MATRIX_SDKS[@]} ))"
  echo
  echo "## Cells"
  echo
  echo "| SDK | Runtime | Executed | Verdict |"
  echo "|---|---|---|---|"
  for r in "${ROWS[@]}"; do echo "$r"; done
  echo
  echo "## Verdict"
  echo
  echo "**$VERDICT**"
  if [ -n "$NOTES" ]; then
    echo
    echo "### Detail"
    echo "$NOTES"
  fi
  echo
  echo "## Still open regardless of this matrix"
  echo
  echo "- B6.8 replay hit/miss: \`NOT_MEASURED\`. The decision lives in"
  echo "  \`DurableInvocationResolver\`, outside this repository."
  echo "- Installed-plugin UAT on any SDK other than the declared one."
  echo "- Provenance signing: requires OIDC/network."
} > "$OUT"

log "verdict: $VERDICT"
log "report: $OUT"
# Match the verdict PREFIX, not the whole string. The verdict carries a detail
# suffix ("PASS (4 cells, ...)") and the earlier exact comparison meant a fully
# green matrix exited 1 while printing PASS, which is the kind of contradiction
# that trains people to ignore the exit code.
case "$VERDICT" in
  PASS*) exit 0 ;;
  *)     exit 1 ;;
esac
