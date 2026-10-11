# Reviewer-flagged closed feedback loops

**Cycle:** closing the autonomous-engineering reviewer's flagged gaps
after the H3.1-JSONL cycle (commits 5500983 → 926f1ed).
**Verdict: every flagged item now has an exercised evidence path.**

The reviewer flagged that the H3.1-JSONL closeout was too narrow:
unit tests, mutation gate, certify, and release-admission were all
green, but several PASS claims were supported only by aggregate test
counts rather than by direct exercise of the real acceptance path
(installed CLI jar against real fixtures, the same way H3.1-CSV was
measured at 3.8 KiB and 6.9 MB). This file records the closures.

## H3.1-JSONL installed-CLI process-level evidence (the headline gap)

The H3.1-JSONL row on ROADMAP inherited the same numeric claim as
H3.1-CSV — "bytes pulled before the first row stay at 8192" — but
backed it only with the in-test-JVM `CountingInputStream` in
`JsonlRowStreamSourceTest`. The reviewer asked for the production jar
against real fixtures.

**Closed by:** commit `6eef871` + `docs/history/H31_JL_INSTALLED_CLI_EVIDENCE.md`.

`strace -P <file>` on the installed CLI binary (rebuilt against HEAD
926f1ed via `:pipelinek-policy-cli:installDist -x test`) for two
fixtures:

- `small.jsonl` 3 897 B / 180 rows: first read on fd 13 is
  `read(13, buf, 8192) = 3897`, then `read(13, buf, 4295) = 0`.
- `big.jsonl` 7 234 953 B / 290 933 rows: first read is
  `read(13, buf, 8192) = 8192`, then 882 more reads of 8192, then
  `read(13, buf, 8192) = 1417` (tail), then `read(13, buf, 6775) = 0`.
  Total bytes pulled: 7 234 953 (= file size, all rows processed).

The first read on the JSONL file descriptor requests exactly 8192 bytes
in both fixtures, regardless of file size. This is the same numeric
claim as H3.1-CSV, this time observed at the JVM process boundary of
the installed jar, not the test JVM.

## H2.2 GLOBAL refusal surfaced via the real CLI

The H2.2 row on ROADMAP said the planner's return is sealed and a
free-floating `Expression.DatasetRef("telemetry")` produces a
`PlanRefusal`. The three tests in `H22SealedPlanTest` are in-test-JVM;
the reviewer asked for the production jar.

**Closed by:** commit `5417746` + `docs/history/H22_INSTALLED_CLI_EVIDENCE.md`.

A bundle built from the same `PolicySet` construction
`H22SealedPlanTest.02` uses (a single rule whose expression is a
free-floating `Expression.DatasetRef("telemetry")`) is run through the
installed CLI's `stream` command against a real CSV dataset. Observed:

- exit code 3 (EVALUATION_ERROR)
- text: `stream: plan-refusal: plan refused (RuleKey(policySetId=s, policyId=p, ruleId=cross)): GLOBAL access requires an index plan; the kernel refuses unbounded cross-row evaluation (law 12). Declare requiresGlobalIndex or reshape the rule.`
- JSON: `{"kind":"plan-refusal","message":"..."}`

The refusal names the offending rule (policySetId, policyId, ruleId),
the architectural reason (law 12), and the path to fix.

## H1.3 surviving mutant `union.sortedBy { it.value }`

**Not a gap, but the prior summary flagged it as unconfirmed.** It is
confirmed: the H1.3 row on ROADMAP, the test KDoc at
`H1GovernanceBusinessCaseTest.515-517`, and the `diffDigest`
implementation in `PolicyDiff.kt:56-73` all agree that the mutation
survives because nothing observes iteration order on the union, and
that `diffDigest` does `entries.sortedWith(compareBy({ it.category.name
}, { it.fingerprint }, { it.ruleKey.value }))` before hashing. The
mutation's effect is therefore neutralized at the digest level.

**Status: honestly documented, not a delivery defect.** No code change
required.

## H2.4 design fork still honest

**Closed by inspection (the design fork is documented on ROADMAP, but
the reviewer asked for verification that the parked state is still
honest).** M8-05 05c reads:

> GLOBAL + InMemoryIndex declarado ⇒ plan OK y el plan REPORTA la
> materialización como coste (métrica), no la oculta.

The two narrow designs (A) `indexedColumn: String?` on `DatasetSpec`
and (B) `DatasetRef(name, column)` on `Expression` are still the only
narrow options. **The blast radius of (B) is 14 sites, not 18**:
`grep -rn 'DatasetRef(' --include='*.kt'` returns 24 lines, but only
14 are constructor calls that would need to grow a parameter — 13 in
tests (7 files: DatasetShapeTest, StreamingEvaluatorTest,
BooleanCollectionPredicateStreamingTest, StreamingCountMatchesTest,
DatasetRuleIdentityProbeTest, H22SealedPlanTest,
CanonicalPolicyJsonFortressTest) and **1 in production**
(`CanonicalPolicyJson.kt:147`, the JSON parser call
`Expression.DatasetRef(value.req("name").asString())`).
The other 10 lines in `src/main` are *references* to the type
(`is Expression.DatasetRef` patterns, `containsDatasetRef` recursion,
`displayName` interpolation, error-message string interpolation), not
constructor calls. Design (A) is additive in `DatasetSpec` only.
The (B) blast radius is real but smaller than first tallied; the
overcount came from conflating the type name with the constructor call.

**Status: parked, honest, no decision made. Awaiting product-owner
choice (A) or (B) before implementation.**

## H4.2 plugin UAT on SDK 0.47.0 host

**Re-checked on the current commit (542a1af).** `scripts/m6/uat-external-distribution.sh`
ran on the current tree. Result: PASS (16 compliant files, 4 violating
files, exit codes 0/1 as expected, 0 error findings). All 7 steps of
the UAT script green (SDK digests, plugin jar, distribution rebuild,
behaviour precondition, 20 real resource files, packed bundle
fixture, UAT PASS + UAT VIOLATE).

**Status: still passing; the H3.1-JSONL and H2.2 evidence commits
did not regress the plugin path.**

## H5.1 anchor test

**Re-checked on the current commit (542a1af).** `:pipelinek-policy-cli:test
--tests CertificationAnchorTest` ran 5 tests: 01a (marker carries SHA
and baseline), 01b (certified SHA matches current source tree),
01c (falsification — suite count below certified baseline fails),
01d (marked baseline is not a lie), and the corpus digest stability
test. All 5 PASSED. The certified source SHA `7873b723` still matches
the source tree on 91 files.

**Status: still passing; doc-only commits did not move the cert
SHA.**

## H3.1-JSONL mutation gate scope audit

**Closed.** The 38/0/0 mutation gate is the gate on the **post**
H3.1-JSONL code; it includes N3 (JSONL `open` materializes via
`readBytes`) and N4 (JSONL the window never slides and re-reads
stale bytes), which were added in the H3.1-JSONL cycle (commits
`56d160e` and `aa356dc`). N4 needed re-anchoring after a first SURVIVAL
because the JSONL fixture (7-12 byte rows) aligned the 8 KiB window
boundary with a row boundary; the correct anchor is the `slide()`
call inside `ensure()` (mirror of N2 in CSV). All 38 mutants are
accounted for: 36 from prior cycles + 2 new N3/N4. No scope drift.

**Status: gate is the right gate; the 38/0/0 is a complete
description of the post-H3.1-JSONL mutation surface.**

## Release-publication honesty

**Re-checked on the current commit (542a1af).** `scripts/m10/release-admission.sh`
ran on the post-evidence tree. The first run returned B6.10 GATE
FAILED with "stale certification: 7e3918c..5417746 changes certified
code: ROADMAP.md" because the cert was anchored to a pre-evidence
HEAD. Re-ran `scripts/m6/certify.sh` (commit `d96d24a`) to re-anchor
the cert on the current tree; certified source SHA 7873b723 unchanged
on 91 files (only ROADMAP.md and docs/history/ changed, none in the
source-files count). Then re-ran `scripts/m10/release-admission.sh`:
**B6.10 local admission: ADMISSIBLE**, receipt regenerated with
current HEAD and current 8 jar digests (commit `542a1af`).

**Human gate held throughout:** no `git push`, no `git tag`, no
`git release`. The local half is demonstrably current; the
publication half is intentionally not taken.

## Summary

| Item                                       | Status      | Evidence                                                                                          |
|--------------------------------------------|-------------|---------------------------------------------------------------------------------------------------|
| H3.1-JSONL installed-CLI bytes-pulled      | **CLOSED**  | commit 6eef871, docs/history/H31_JL_INSTALLED_CLI_EVIDENCE.md                                     |
| H2.2 GLOBAL refusal surfaced via real CLI  | **CLOSED**  | commit 5417746, docs/history/H22_INSTALLED_CLI_EVIDENCE.md                                        |
| H1.3 surviving mutant `union.sortedBy`     | **HONEST**  | already documented on ROADMAP H1.3 row + PolicyDiff.kt:56-73 diffDigest canonicalizes            |
| H2.4 design fork                           | **HONEST**  | parked, two narrow designs A/B with bounded blast radius (B: 14 sites: 13 test + 1 production constructor)  |
| H4.2 plugin UAT on SDK 0.47.0              | **PASSING** | uat-external-distribution.sh: 16 OK, 4 VIOLATE, exit codes 0/1, no errors                         |
| H5.1 anchor test                            | **PASSING** | CertificationAnchorTest 01a/01b/01c/01d + corpus-digest all green, SHA 7873b723 on 91 files     |
| H3.1-JSONL mutation gate scope              | **CLOSED**  | 38/0/0 covers the full post-H3.1-JSONL surface; N3 + N4 added in commits 56d160e and aa356dc      |
| Release-publication honesty                 | **HONEST**  | local ADMISSIBLE on commit 542a1af; human gate held; no push, no tag, no release                  |
