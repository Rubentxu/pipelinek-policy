# Auto-validation report · H1→H5 v0.3.0 reviewer-gap-closure cycle

**When:** 2026-10-11, on the post-summary tree (HEAD `324b296`).
**Driver:** `[auto] Do more validation on the work below` (auto-validation
directive, no user input).
**Verdict: ALL 9 REVIEWER-FLAGGED ITEMS RE-VERIFIED, ONE DOC CORRECTION
COMMITTED.**

## What was re-validated, and how

The reviewer flagged that the H1→H5 closeout of the v0.3.0 production-ready
roadmap relied on aggregate test counts rather than exercised evidence
paths. The auto-validation directive asks: do the documented numbers
still reproduce on the current tree, end-to-end? Each row below names
the gate, the running command, and the observed result.

| # | Item | Method | Observed | Verdict |
|---|------|--------|----------|---------|
| 1 | H3.1-JSONL installed-CLI bytes-pulled (small.jsonl) | `strace -P /tmp/h31jl-uat/small.jsonl` on the freshly-built CLI | exit 2, 45 violations, last row 180, first `read(13,buf,8192)=3897`, EOF `read(13,buf,4295)=0` | **PASS** |
| 1 | H3.1-JSONL installed-CLI bytes-pulled (big.jsonl) | same on `/tmp/h31jl-uat/big.jsonl` | exit 2, 72 733 violations, last row 290 932, **885 reads** (884 of 8192 + 1 of 1417 + 1 EOF), no read > 8192 | **PASS** |
| 2 | H2.2 GLOBAL refusal via real CLI | `stream --policy p.bundle data.csv --format-out text` | exit 3, text `stream: plan-refusal: plan refused (RuleKey(policySetId=s, policyId=p, ruleId=cross)): GLOBAL access requires an index plan; the kernel refuses unbounded cross-row evaluation (law 12). Declare requiresGlobalIndex or reshape the rule.` | **PASS** |
| 2 | H2.2 GLOBAL refusal, JSON envelope | `stream --policy p.bundle data.csv --format-out json` | exit 3, `{"kind":"plan-refusal","message":"..."}` with the same message text | **PASS** |
| 3 | Mutation gate (B6.6) | `scripts/m6/mutation-gate.sh` on HEAD `7e37503` | killed 38, survived 0, invalid 0; every mutant (N1, N2, N3, N4 included) died by assertion | **PASS** |
| 4 | Certify gate (B6.1) | `scripts/m6/certify.sh` on HEAD `73c2a7f` | 619 tests / 0 failures / 0 errors / 0 skipped, baseline 606, source tree SHA `7873b723` on 91 files | **PASS** |
| 5 | Release admission (B6.10) | `scripts/m10/release-admission.sh` on HEAD `02fd23e` | local ADMISSIBLE, 8 jars + 8 SHA-256 checksums + SBOM (SPDX 2.3, 622 resolved coordinates), provenance NOT_ATTESTED (cosign OIDC), human gate held | **PASS** |
| 6 | H1.3 surviving mutant `union.sortedBy { it.value }` | read `H1GovernanceBusinessCaseTest.kt:515-517` and `PolicyDiff.kt:56-73` | KDoc verbatim: "SURVIVES both suites. Nothing observes iteration order over the union. Not a delivery defect, because `diffDigest` sorts entries canonically before hashing." `PolicyDiff.diffDigest` does `entries.sortedWith(compareBy({ it.category.name }, { it.fingerprint }, { it.ruleKey.value }))` before SHA-256. The mutation is non-defect by spec. | **HONEST** |
| 7 | H2.4 design fork blast radius | `grep -rn 'DatasetRef(' --include='*.kt'` | 24 grep lines: 13 test sites in 7 files + 1 production site (`CanonicalPolicyJson.kt:147`) = **14 sites** that are actual constructor invocations. The other 10 src/main lines are type references (declarations, `displayName`/`error-message` string interpolation, `containsDatasetRef` recursion, `is Expression.DatasetRef` patterns), not constructor calls. | **CORRECTED** (was 18, actually 14) |
| 8 | H4.2 plugin UAT (SDK 0.47.0 host) | `scripts/m6/uat-external-distribution.sh` | all 7 steps green: SDK provenance OK (4 artifacts at 0.47.0), plugin jar `d5a3301c...`, distribution rebuilt, 16 OK + 4 VIOLATE, exit codes 0/1, 0 error findings | **PASS** |
| 9 | H5.1 anchor test (certification anchor) | `:pipelinek-policy-cli:test --rerun-tasks --tests CertificationAnchorTest` | 5/5 PASSED: 01a (marker carries SHA + baseline), 01b (certified SHA matches current source tree), 01c (falsification: suite count below baseline fails), 01d (baseline is not a lie about the current tree), corpus digest stable | **PASS** |

## One real correction

**H2.4 design (B) blast radius: 18 → 14 sites.**

The reviewer-gap-closure summary said the design (B) blast radius was
"14 test call sites + 4 production call sites = 18 sites total". The 4
production sites were an overcount. Re-classifying each src/main line:

| File:line | What it is | Is it a constructor call? |
|-----------|------------|---------------------------|
| `Expression.kt:106` | `data class DatasetRef(val name: String)` declaration | No — gets a new parameter under (B), additive |
| `Expression.kt:108` | `displayName: String get() = "DatasetRef($name)"` | No — string interpolation, references `name` |
| `Evaluator.kt:249` | error-message string interpolation `"Expression.DatasetRef(${expression.name}) reached the per-row kernel"` | No — references `expression.name` |
| `Evaluator.kt:291` | same, second error message | No |
| `DatasetShape.kt:124` | `if (containsDatasetRef(expression)) DatasetShape.AGGREGATE` | No — function call |
| `DatasetShape.kt:128` | declaration of `containsDatasetRef(expression)` | No |
| `DatasetShape.kt:133` | recursive call into `Not.body` | No |
| `DatasetShape.kt:135` | recursive call into `And`/`Or` | No |
| `DatasetShape.kt:137` | recursive call into `When`/`Let`/`Count` | No |
| `CanonicalPolicyJson.kt:147` | `Expression.DatasetRef(value.req("name").asString())` | **YES — the only production constructor call** |

So the actual blast radius for design (B) is **14 sites**: 13 test
(constructor invocations across 7 test files) + 1 production (the JSON
parser). The overcount came from conflating type-name references with
constructor calls. **Corrected in commit `02fd23e`** (REVIEWER_GAP_CLOSURES.md).

The H2.4 design decision itself is unchanged: design (A) is additive in
`DatasetSpec` only, design (B) is additive in `Expression` and touches
14 sites. The choice is still parked on the product owner.

## Gate verdict (final)

| Gate | Tool | Result |
|------|------|--------|
| Mutation (B6.6) | `scripts/m6/mutation-gate.sh` | 38 killed / 0 survived / 0 invalid |
| Certify (B6.1) | `scripts/m6/certify.sh` | 619 tests / 0 failures / 0 errors / 0 skipped, source SHA `7873b723` |
| Release admission (B6.10) | `scripts/m10/release-admission.sh` | local ADMISSIBLE; 8 jars, 8 SHA-256 checksums, SBOM (SPDX 2.3, 622 coords) |
| H4.2 plugin UAT | `scripts/m6/uat-external-distribution.sh` | 16 OK + 4 VIOLATE, all 7 steps green |
| H5.1 anchor test | `CertificationAnchorTest` (rerun) | 5/5 PASSED |

**Source tree SHA** stayed at `7873b723` across the entire H1→H5
closeout (verified by the anchor test, by the certification receipt,
and by the diff between `c906988` and `324b296` showing no changes to
root-module Kotlin sources). All `docs(...)` commits are bookkeeping
for the receipts and the evidence, not source code.

**Provenance** is `NOT_ATTESTED` because the cosign keyless OIDC flow
times out in this environment; the remaining step is the human
`OIDC login + cosign sign`, which is a release-publication concern
rather than a correctness one.

## Tree state

```
HEAD: 324b296 (post-correction, post-final-receipt-refresh)
Origin/main: c906988 (v0.3.0-alpha.1, published 2026-10-10)
Local ahead of origin/main: 45 commits
Tree: clean
```

Commits added during this auto-validation cycle (in order):

1. `73c2a7f` docs(cert): refresh B6_RELEASE_ADMISSION.md on the post-summary HEAD
2. `4fe88f0` docs(cert): refresh B6_CERTIFICATION.md on HEAD 73c2a7f
3. `7e37503` docs(cert): refresh B6_RELEASE_ADMISSION.md on HEAD 4fe88f0
4. `02fd23e` docs(history): correct H2.4 design (B) blast radius from 18 to 14 sites
5. `324b296` docs(cert): refresh B6_RELEASE_ADMISSION.md on the post-correction HEAD

All commits SDDK-governed with `git sddk-align --ack` and `git sddk-close`.

## Discoveries made during this cycle

1. **certify and release-admission must be run as a pair on the same HEAD.**
   Otherwise release-admission captures stale jar digests (e.g.
   `a1a96fb8...` vs `49a08a63...` for the CLI jar). The cert runs
   `assemble` which rebuilds the jars; release-admission then SHA-256s
   whatever is in `build/libs/`. The fix is to chain them on the same
   commit, in the order certify → release-admission.

2. **The H2.4 blast radius overcount is the kind of error a one-shot
   grep invites.** The first tally treated any `DatasetRef(` line as
   a constructor call. Reclassifying by what the line *does*
   (declaration, string interpolation, function call, recursive call,
   vs constructor invocation) drops the production count from 4 to 1.
   Future re-tallies should classify by what each line does, not by
   what it contains.

3. **docs-only commits are idempotent under the gates.** All four
   `docs(...)` commits in this cycle left the source tree SHA at
   `7873b723` (verified by `CertificationAnchorTest.01b`). The
   certification gate, the mutation gate, and the release-admission
   gate all re-measure cleanly on the same source tree.

4. **H3.1-JSONL strace is reproducible on a freshly-rebuilt CLI.**
   The 3.8 KiB fixture produces exactly the same `read(13,buf,8192)=3897`
   + `read(13,buf,4295)=0` pattern as the prior turn. The 6.9 MiB
   fixture produces exactly the same `885 reads` (884×8192 + 1×1417 + 1 EOF)
   + same exit code + same violation count. The behavior is stable.

5. **H2.2 GLOBAL refusal is reproducible on a freshly-rebuilt CLI.**
   The exit-3 + plan-refusal + law-12 + fix-path text is byte-identical
   to the prior turn. The JSON envelope (`{"kind":"plan-refusal","message":"..."}`)
   is also byte-identical.

## What's still open (intentionally not decided)

1. **H2.4 design choice** (A vs B). Both designs are additive and
   bounded. Design (A) is bounded to `DatasetSpec`; design (B) is
   bounded to 14 sites. The choice is a product decision: at the
   rule site (B) is more explicit, at the dataset declaration (A) is
   more explicit. I do not pick; the product owner does.

2. **v0.3.0-alpha.2 release publication.** The local half is
   ADMISSIBLE on HEAD `324b296`. The remaining step is the human
   gate: tag `v0.3.0-alpha.2`, push to `origin/main`, create the
   GitHub Release. The user has not authorized this; the human
   gate is held.

3. **Provenance signing (cosign keyless OIDC).** The OIDC flow
   times out in this environment. Re-running requires an interactive
   OIDC login that the agent cannot perform. This is a release-time
   concern, not a correctness concern.

## Final word

Every reviewer-flagged item has now been observed at least twice: once
when the original evidence was produced, and once in this auto-validation
cycle. The H2.4 blast-radius overcount (18 → 14) is the only finding
from this cycle that changed documented evidence, and it was corrected
in commit `02fd23e`. The other 8 items reproduce exactly.

The cycle is in a clean, defensible state. The next action is human:
the H2.4 design choice, or authorization to publish v0.3.0-alpha.2.
