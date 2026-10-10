#!/usr/bin/env bash
# B6.5 · Autentic mutation testing gate.
#
# The contract the ROADMAP sets for B6.5, verbatim:
#
#   "mutación válida → COMPILA → test dirigido FALLA por la aserción esperada
#    → restore → test verde. Un fallo de compilación NO cuenta como detección
#    semántica."
#
# That last clause is the whole point. A mutation gate that counts a compile
# error as a kill is theatre: it would "pass" against a codebase so broken that
# nothing builds. So this script enforces three distinct outcomes per mutant
# and refuses to call anything a detection unless all three are observed:
#
#   1. COMPILES       — the mutant builds (semantic mutant, not a typo).
#   2. DIES BY ASSERT — the directed test fails AND the failure is an assertion
#                       failure, not an incidental crash. A StackOverflowError or
#                       a NoSuchElementException is NOT a detection.
#   3. RESTORES GREEN — with the source restored, the same test passes again.
#
# Every mutant is declared below as an exact old/new pair. Exact pairs matter:
# a line-number-based patcher silently no-ops when the source moves, and a
# mutation gate that no-ops is worse than no gate at all.
#
# Mutants are grouped by what they attack, so a coverage report can say which
# decision point is untested rather than just "score 40%".
set -uo pipefail

# The script lives in scripts/m6, so the repository root is two levels up.
# Getting this wrong is not cosmetic: every anchor lookup silently "fails" and
# the gate reports INVALID for all mutants while looking like it ran.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

# The shell JVM may be 25.0.4.1, which this Kotlin version cannot parse; all
# builds must run on JDK 21.
JAVA_RUN=(mise exec java@21 --)

RESET=0
KILLS=0
SURVIVED=0
INVALID=0
FAILED_CHECKS=()

reset_tree() {
  [[ $RESET -eq 1 ]] && git checkout -- "$1"
  return 0
}

# Un gate que borra trabajo sin avisar es peor que no tener gate.
#
# `reset_tree` restaura con `git checkout --`, que revierte el archivo a HEAD.
# Con un fix de producción sin commitear en ese archivo, el gate no lo muta y lo
# lo revierte: el run muere mutando código viejo y, peor, se lleva por delante el
# fix que el run pretendía certificar. Se observó exactamente eso con H1.1: el
# restore de D1/D2 borró el fix de `severity` y no quedó en ningún stash.
#
# La condición de salida es ahora explícita: si el archivo que el gate va a
# mutar tiene cambios sin commitear, se aborta ANTES de mutar nada.
# The abort must not leave a half-applied mutant behind. The guard is checked
# at the START of mutate(), but RESET is already 1 by then, so aborting in the
# middle of a run would exit with whatever mutant was last applied still on
# disk. Observed with A3: the guard fired on the next mutant and left
# `if (outcome || !outcome)` in Evaluator.kt, which then went on to be staged as
# if it were real work. The trap restores before exiting, so an abort leaves the
# tree exactly as clean as a successful run.
assert_mutable_files_are_committed() {
  local file="$1" dirty
  dirty="$(git status --porcelain -- "$file" | awk '{print $2}')"
  if [[ -n "$dirty" ]]; then
    echo "ABORT: $file has uncommitted changes; this gate would destroy them." >&2
    echo "  Its restore step uses 'git checkout --', which reverts to HEAD and" >&2
    echo "  silently discards the work. Commit first, then run the gate." >&2
    reset_tree "$file"
    trap - EXIT
    exit 3
  fi
}

# The directed tests live in different Gradle modules, and `--tests` applied to
# the WHOLE project fails every module that does not contain the class
# ("No tests found for given includes"). So each test pattern must be paired
# with the module that actually owns it. Guessing here produced a gate that
# failed for reasons unrelated to the mutant.
module_for_pattern() {
  case "$1" in
    *PropertyFuzzTest*)    echo ":pipelinek-policy-cli:" ;;
    *MutationGateTest*)    echo ":" ;;
    *RuleEvaluationTest*)  echo ":" ;;
    *CanonicalDigestTest*) echo ":" ;;
    *ValueNodeTest*)       echo ":" ;;
    *PolicyDiffTest*)      echo ":" ;;
    *PolicyIrSeverityRoundTripTest*) echo ":" ;;
    *H1SeverityBundlePathTest*) echo ":" ;;
    *H1GovernanceBusinessCaseTest*) echo ":" ;;
    *)                     echo ":" ;;
  esac
}

# Filter the test task to the owning module only, so a directed run does not
# fail on sibling modules that legitimately lack the class. The module token
# carries a leading AND trailing colon, so `:test` / `:module:test` both build
# correctly without a special case here.
run_tests_in() {
  local module="$1" pattern="$2" log="$3"
  "${JAVA_RUN[@]}" ./gradlew "${module}test" --tests "$pattern" --rerun-tasks >"$log" 2>&1
}

# compile_check <module> — the mutant must compile.
#
# A single `compileTestKotlin` target is not enough here: the mutants live in
# different modules (root kernel, policy-decoders-json) and the CLI module links
# against the mutated decoder. Targeting one module reported BUILD FAILED in 1s
# on an unrelated daemon/ordering issue while the same mutant compiled fine
# against the whole project. So the check compiles EVERYTHING, which is the
# only way to know the mutant is genuinely valid rather than half-valid.
compile_check() {
  if "${JAVA_RUN[@]}" ./gradlew compileTestKotlin --rerun-tasks -q >/tmp/m6_compile.log 2>&1; then
    return 0
  fi
  echo "    INVALID: mutant does not compile"
  grep -E '^e: ' /tmp/m6_compile.log | head -3 | sed 's/^/      /'
  if ! grep -qE '^e: ' /tmp/m6_compile.log; then
    echo "      (no compiler diagnostics captured; raw tail follows)"
    tail -6 /tmp/m6_compile.log | sed 's/^/      /'
  fi
  return 1
}

# run_directed <module> <test-pattern> — expect a RED caused by an assertion.
run_directed() {
  local module="$1" pattern="$2"
  run_tests_in "$module" "$pattern" /tmp/m6_test.log
  local rc=$?
  if grep -qE 'No tests found for given includes' /tmp/m6_test.log; then
    echo "    INVALID: pattern matched no tests ($pattern)"
    return 2
  fi
  if [[ $rc -eq 0 ]]; then
    echo "    SURVIVED: test still green"
    return 1
  fi
  # Classify the failure from the freshly written JUnit XML, NOT from Gradle's
  # console output. Gradle prints "N tests completed, 1 failed" and nothing
  # about the exception, so grepping the console for AssertionFailedError made
  # a real kill look INVALID. The XML carries the typed exception.
  local xml
  xml=$(find "$REPO_ROOT" -path '*/build/test-results/test/*.xml' -newer /tmp/m6_test.log -print -quit 2>/dev/null)
  if [[ -z "$xml" ]]; then
    xml=$(latest_test_xml "$module")
  fi
  if [[ -n "$xml" ]] && grep -qE 'AssertionError|AssertionFailedError' "$xml"; then
    echo "    KILLED: assertion failure in $(basename "$xml")"
    return 0
  fi
  echo "    INVALID: test failed but NOT by assertion (rc=$rc)"
  if [[ -n "$xml" ]]; then
    grep -oE '(java|kotlin|org)\.[A-Za-z0-9_.]*(Exception|Error)' "$xml" | sort -u | head -3 | sed 's/^/      /'
  else
    tail -6 /tmp/m6_test.log | sed 's/^/      /'
  fi
  return 2
}

# Latest JUnit XML for a module, by mtime. The module token is ":name:", so
# strip BOTH colons before joining the module path.
latest_test_xml() {
  local module="${1#:}"
  module="${module%:}"
  local dir="build/test-results/test"
  [[ -n "$module" ]] && dir="$module/build/test-results/test"
  find "$REPO_ROOT/$dir" -name '*.xml' -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-
}

# restore_and_verify <file> <module> <pattern> <label>
#
# The restore check must observe the tree as `git checkout` left it. Gradle's
# incremental state keys off file hashes, so a checkout normally invalidates it
# on its own; the residual risk is the DOWNSTREAM module (the CLI links against
# the mutated decoder jar) keeping a stale compiled dependency. `--rerun-tasks`
# on the owning module is what forces that relink.
#
# `--gradlew --stop` was tried first and is wrong: it tears the daemon down and
# the next invocation then fails task resolution, which reads exactly like a
# surviving mutant. Do not restart the daemon here.
restore_and_verify() {
  local file="$1" module="$2" pattern="$3" label="$4"
  reset_tree "$file"
  # Confirm the restore actually landed before spending a build on it: if the
  # file still differs from HEAD, the check is meaningless.
  if ! git diff --quiet -- "$file"; then
    echo "    RESTORE FAILED: $file still differs from HEAD after checkout"
    git diff --stat -- "$file" | sed 's/^/      /'
    return 1
  fi
  if run_tests_in "$module" "$pattern" /tmp/m6_green.log; then
    return 0
  fi
  # Report the actual Gradle error, not the trailing "Run gradlew tasks"
  # boilerplate, which is the same for every kind of failure and hid the real
  # cause (an unresolvable task) behind a generic wall of text.
  echo "    RESTORE FAILED: source restored but test still red"
  echo "      task: ${module}test --tests $pattern"
  awk '/What went wrong/,/^\* Try/' /tmp/m6_green.log | head -8 | sed 's/^/      /'
  return 1
}

# mutate <file> <old> <new> <label> <test-pattern>
mutate() {
  local file="$1" old="$2" new="$3" label="$4" pattern="$5"
  RESET=1
  # Never mutate a file the gate would later destroy. See the note on
  # assert_mutable_files_are_committed: `git checkout --` reverts to HEAD and
  # silently discards any uncommitted fix living in that same file.
  assert_mutable_files_are_committed "$file"
  local module
  module=$(module_for_pattern "$pattern")
  echo "== mutant: $label"
  echo "    module: $module · target: $pattern"

  # The anchor must match exactly once. The count is done by the same perl
  # matcher that applies the patch, not by grep -cF: grep counts matching
  # LINES, so a multi-line anchor spanning two lines reports 2 and produces a
  # false rejection. One checker, one truth.
  local count
  count=$(OLD="$old" perl -0777 -ne 'my $n = () = /\Q$ENV{OLD}\E/g; print $n' "$file")
  if [[ "$count" -ne 1 ]]; then
    echo "    INVALID: anchor matched $count times in $file (must be exactly 1)"
    INVALID=$((INVALID + 1))
    return 0
  fi

  # Apply with perl -0777 (slurp the whole file) so the anchor may span lines.
  # $_ holds the slurped content under -p; $0 is the filename. Using $0 here
  # made every match count zero, which the anchor guard below correctly
  # rejected as INVALID rather than silently "passing".
  OLD="$old" NEW="$new" perl -0777 -pi -e '
    my $old = $ENV{OLD}; my $new = $ENV{NEW};
    my $n = () = /\Q$old\E/g;
    if ($n != 1) { die "anchor matched $n times\n" }
    s/\Q$old\E/$new/;
  ' "$file" 2>/tmp/m6_mutate.log

  if [[ ! -s /tmp/m6_mutate.log ]]; then
    if ! compile_check "$module"; then
      INVALID=$((INVALID + 1))
      reset_tree "$file"
      return 0
    fi
    run_directed "$module" "$pattern"
    local rc=$?
    if [[ $rc -eq 0 ]]; then
      KILLS=$((KILLS + 1))
    elif [[ $rc -eq 1 ]]; then
      SURVIVED=$((SURVIVED + 1))
    else
      INVALID=$((INVALID + 1))
    fi
    # restore_and_verify restores the file itself; a second reset here would be
    # a no-op and hides which step actually restores the tree.
    if ! restore_and_verify "$file" "$module" "$pattern" "$label"; then
      FAILED_CHECKS+=("$label")
    fi
  else
    echo "    INVALID: mutation failed to apply"
    sed 's/^/      /' /tmp/m6_mutate.log
    INVALID=$((INVALID + 1))
    reset_tree "$file"
  fi
}

echo "== B6.5 mutation gate (authentic: compile → die by assertion → restore green)"
echo

# ---------------------------------------------------------------------------
# Group A — evaluator decision points. These are the places where a wrong
# answer is silent: the report still looks well-formed.
# ---------------------------------------------------------------------------

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/evaluator/Evaluator.kt \
  'AppliesWhenOutcome.FalseOrMissing -> RuleEvaluation.NotApplicable' \
  'AppliesWhenOutcome.FalseOrMissing -> RuleEvaluation.Passed' \
  'A1 appliesWhen false must be NotApplicable, not Passed' \
  '*MutationGateTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/evaluator/Evaluator.kt \
  'is AppliesWhenOutcome.TypedRefusal -> RuleEvaluation.Error(gateResult.violation)' \
  'is AppliesWhenOutcome.TypedRefusal -> RuleEvaluation.NotApplicable' \
  'A2 a malformed appliesWhen must be Error, not silently NotApplicable' \
  '*RuleEvaluationTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/evaluator/Evaluator.kt \
  'if (outcome) RuleEvaluation.Passed
            else RuleEvaluation.Violated(' \
  'if (outcome || !outcome) RuleEvaluation.Passed
            else RuleEvaluation.Violated(' \
  'A3 the main expression verdict must be honoured, not short-circuited' \
  '*MutationGateTest*'

# ---------------------------------------------------------------------------
# Group B — decoder precision. Numeric carrier choice is the classic place
# where a regression loses data without anyone noticing.
# ---------------------------------------------------------------------------

mutate \
  policy-decoders-json/src/main/kotlin/com/pipelinek/policy/decoders/json/JsonResourceDecoder.kt \
  'val number: Number = BigDecimal(text)' \
  'val number: Number = text.toDouble()' \
  'B1 JSON float must keep BigDecimal precision, not Double' \
  '*PropertyFuzzTest*'

mutate \
  policy-decoders-json/src/main/kotlin/com/pipelinek/policy/decoders/json/JsonResourceDecoder.kt \
  'if (!seen.add(name)) {
                    throw DuplicateKeyRefusal(' \
  'if (false && !seen.add(name)) {
                    throw DuplicateKeyRefusal(' \
  'B2 JSON duplicate keys must refuse, not last-wins' \
  '*PropertyFuzzTest*'

# ---------------------------------------------------------------------------
# Group C — canonical form. If the canonical string changes, every digest in
# the system changes with it, silently.
# ---------------------------------------------------------------------------

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/value/ValueNode.kt \
  'val sorted = node.entries.entries.sortedBy { it.key }' \
  'val sorted = node.entries.entries.toList()' \
  'C1 canonical form must sort mapping keys' \
  '*CanonicalDigestTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/value/ValueNode.kt \
  'is ValueNode.SequenceValue -> node.elements' \
  'is ValueNode.SequenceValue -> node.elements.reversed()' \
  'C2 canonical form must preserve sequence order' \
  '*CanonicalDigestTest*'

# ---------------------------------------------------------------------------
# Group D — declared data in transit. H1.1.
#
# `Rule.severity` is author-declared, not derived at evaluation time. Before
# H1.1 the canonical writer never emitted it and the decoder never read it, so
# a CRITICAL rule evaluated in the author's process and arrived at the plugin
# step as "not declared", with identical bytes for every severity. D1
# reintroduces exactly that loss in the writer and D2 reintroduces it in the
# decoder; both must die against the round-trip tests.
# ---------------------------------------------------------------------------

mutate \
  src/main/kotlin/com/pipelinek/policy/ir/CanonicalPolicyJsonWriter.kt \
  'rule.severity?.let {
                    append(",\"severity\":").append(CanonicalPolicyJsonNodeWriter.quote(it.name))
                }' \
  'rule.let {
                }' \
  'D1 declared severity must be encoded, not dropped in transit' \
  '*PolicyIrSeverityRoundTripTest*'

# D3 is the same production mutation as D1, aimed at the END-TO-END bundle path
# instead of the codec in isolation. D1 dying proves the round-trip test is
# sensitive to the writer; D3 dying proves UAT-H1-01 and UAT-H1-02 are sensitive
# to it too. Without D3 the release criterion could be satisfied by a bundle-path
# test that never actually looked at the packed bytes.
mutate \
  src/main/kotlin/com/pipelinek/policy/ir/CanonicalPolicyJsonWriter.kt \
  'rule.severity?.let {
                    append(",\"severity\":").append(CanonicalPolicyJsonNodeWriter.quote(it.name))
                }' \
  'rule.let {
                }' \
  'D3 severity must survive pack verifyPacked evaluate, not just the codec' \
  '*H1SeverityBundlePathTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/ir/CanonicalPolicyJson.kt \
  'return RuleSeverity.entries.firstOrNull { it.name == name }
            ?: throw IrRefusal.CorruptEncoding("unknown rule severity '"'"'$name'"'"'; expected one of " +
                RuleSeverity.entries.joinToString(",") { it.name })' \
  'return RuleSeverity.entries.firstOrNull { it.name == name }
            ?: null' \
  'D2 an unknown severity must be refused, never silently degraded to absent' \
  '*PolicyIrSeverityRoundTripTest*'

# ---------------------------------------------------------------------------
# Group E — H1 governance business cases.
#
# E1–E3 attack the three UAT rows that were PARTIAL/NOT_MEASURED before
# `H1GovernanceBusinessCaseTest`. Each mutates the production decision that the
# corresponding business case exists to hold, and each must die by assertion.
#
# E1 drops the `authorizes` check, so ANY non-blank authority string weakens an
# upper-layer rule again — the exact B4-T2 defect ADR-0014 was written for. The
# business case that dies is the ungranted weakening; the granted one must still
# compose, which is why both directions are in that test class.
#
# E2 drops `subjectMatches`, so `firstOrNull` answers with the first waiver whose
# POLICY AND RULE match. With one waiver per call nothing distinguishes that from
# correct routing, which is precisely why every pre-existing waiver case passed a
# single waiver. The death proves UAT-H1-04 is sensitive to the subject path.
#
# E3 reorders the precedence so a violation outranks an error, which under SHADOW
# degrades "could not evaluate" into "would have passed". `EnforcementInterpreterTest`
# asserts the hand-built tally matrix, but no test derived a tally from a REAL
# report before, so nothing was watching that step.
# ---------------------------------------------------------------------------

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/policy/Layers.kt \
  'if (authorities.authorizes(supersession.authority, layer)) {' \
  'if (true) {' \
  'E1 supersession must be covered by a host grant, never self-asserted' \
  '*H1GovernanceBusinessCaseTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/policy/Waivers.kt \
  'subjectMatches(w, subjectResolver) &&' \
  'true &&' \
  'E2 waiver routing must select on the subject path, not on list order' \
  '*H1GovernanceBusinessCaseTest*'

mutate \
  src/main/kotlin/com/pipelinek/policy/kernel/governance/EnforcementInterpreter.kt \
  'tally.errors > 0 -> GovernanceVerdict.ERRORED
        tally.violations > 0 -> GovernanceVerdict.VIOLATED' \
  'tally.violations > 0 -> GovernanceVerdict.VIOLATED
        tally.errors > 0 -> GovernanceVerdict.ERRORED' \
  'E3 an evaluation error must outrank a violation under SHADOW' \
  '*H1GovernanceBusinessCaseTest*'

echo
echo "== B6.5 mutation summary"
echo "   killed:     $KILLS"
echo "   survived:   $SURVIVED"
echo "   invalid:    $INVALID"
if [[ ${#FAILED_CHECKS[@]} -gt 0 ]]; then
  echo "   RESTORE CHECKS FAILED: ${FAILED_CHECKS[*]}"
  exit 1
fi
if [[ $INVALID -gt 0 ]]; then
  echo "   FAIL: $INVALID mutant(s) were not authentic semantic mutants"
  exit 1
fi
if [[ $SURVIVED -gt 0 ]]; then
  echo "   FAIL: $SURVIVED mutant(s) survived — the targeted test does not detect them"
  exit 1
fi
echo "   PASS: every mutant compiled, died by assertion, and restored green"