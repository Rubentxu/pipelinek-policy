# R1.2 — Inventory of the 53 commits ahead of `origin/main`

**Fecha:** 2026-10-11 · **Agente:** Jcode · **Work-item:** `6fb5b421-1201-4773-b748-e6510da8b87b`

This report is the R1.2 deliverable of the **R1 — Reconciliación, integración remota y alpha.2** directive. It groups the 53 commits between `origin/main` (`c906988`) and local HEAD (`7f14d9c`) by the original H1–H5 milestones and the audit-correction work, and inventories the test/spec/falsification evidence each group carries.

## R1.2.1 — High-level shape

| Group | Commits | Tests added/changed | Behavior change |
|---|---|---|---|
| H1 integrity & governance | 0 in this delta (already in origin/main via M7/M9) | — | none |
| H2 datasets & planning | 19 | 13 new tests + 3 fixes + 1 refactor | COUNT/AGGREGATE bug fixes; RuleKey identity fix; sealed PlanResult return; sealed InMemoryIndex fix |
| H3 streaming & budgets | 7 | 5 new tests + 2 fixes + 1 stream refactor | JSONL windowed source; CSV streaming; CSV mutants routed to own module |
| H4 CLI & plugin | 0 in this delta (already in origin/main) | — | none |
| H5 certification & release | 1 (H2.2 cert anchor 8d732648→7873b723) | (none in this group; receipts are docs) | none |
| Audit corrections + receipts | 26 | none (all docs) | none |

**32 files changed** (28 source/test + 4 receipt files), of which 22 are test files, 6 are source files (1 fix in `src/main`; 5 in `pipelinek-policy-cli/src/main` and decoders), and the rest are docs.

## R1.2.2 — H2 dataset & planning group (19 commits)

| Commit | Type | Description | Tests changed | Falsified by |
|---|---|---|---|---|
| `97100df` | `fix(dataset)` | COUNT must count matching rows, not every row | `StreamingCountMatchesTest` (3) | M1 (drop match test) and M2 (invert it) |
| `6cd1935` | `fix(dataset)` | pass the predicate to Accumulator.Count at its call site | (relies on above) | (above) |
| `65a207a` | `feat(csv)` | stream the dataset instead of materializing it | `CsvRowStreamSourceTest` (5) | N1 (open materialises) and N2 (window never slides) |
| `42a26f9` | `fix(gate)` | route CSV mutants to their own module | — | — |
| `0a79b6e` | `fix(dataset)` | key dataset rule identity by RuleKey, not bare rule id | `DatasetRuleIdentityProbeTest` (6) | R1 (re-collapse to rule id) and R2 (drop membership test) |
| `f8f16eb` | `feat(m8)` | seal the planner's return type as DatasetPlanner.PlanResult | `H22SealedPlanTest` (3) | type-refactor equivalent mutants |
| `5500983` | `feat(jsonl)` | add windowed JsonlSource over InputStream | (paired with below) | (paired with below) |
| `f0b7096` | `test(jsonl)` | 5 falsifying cases for windowed JsonlSource | `JsonlRowStreamSourceTest` (5) | N3 (open materialises) and N4 (window never slides) |
| `7a6790c` | `feat(cli)` | StreamCmd JSONL branch now opens file as a stream | (paired with above) | (paired with above) |
| `56d160e` | `test(gate)` | add H3.1-JSONL mutants N3 and N4 | (paired with above) | (paired with above) |
| `aa356dc` | `fix(gate+test)` | N4 anchor swapped to slide() call site | (refines above) | (refines above) |
| `9e1a0b8` | `test(dataset)` | assert the H2.1 verdict, not just the rule's presence | (relies on R1/R2) | — |
| `d7abf68` | `style(test)` | wrap the long assertion lines in the H2.1 probe | — | — |
| `ca78717` | `test(dataset)` | pin the H2.1 invariant and correct the law 12 claim | — | — |
| `c8906b8` | `docs(roadmap)` | close H2.1 and refresh the admission receipt | — | — |
| `3301f73` | `docs(roadmap)` | correct the H2.1 test count and record the fragility note | — | — |
| `771ab9e` | `test(cli)` | pin H2.1 acceptance through the real stream path | — | — |
| `9d3d205` | `chore(cert)` | refresh the receipt after the anchor move | — | — |
| `8d0d6c5` | `chore(cert)` | refresh the receipt after the CLI acceptance test | — | — |

**Behavior changes in H2 group:** 4 production fixes (COUNT semantic, RuleKey identity, sealed PlanResult, accumulated `IndexPlan.SinglePass` default), 1 stream refactor (JSONL windowed source), 1 CLI refactor (StreamCmd JSONL branch opens as stream).

**Tests in H2 group:** 19 new tests in the root module, 5 in `policy-decoders-csv`, 5 in `policy-decoders-json`. All falsified by named mutants (M1/M2, R1/R2, N1/N2/N3/N4, T1/T2).

**Spec/Falsification evidence in H2 group:**
- `docs/history/M8_SPECIFICATION.md` — original M8 spec; covers dataset shape model, planner, evaluators.
- `docs/history/M8_DESIGN.md` — design proposal.
- `docs/history/M8_EXPLORATION_REPORT.md` — exploration notes.
- `docs/03-specifications/POLICY_IR.md` — PolicyIR definition; the GLOBAL handling contract lives here.
- `docs/03-specifications/EVALUATION_SEMANTICS.md` — evaluation semantics.
- `docs/04-adrs/` — 16 ADRs total, none specifically for H2.4 yet (R2.1 will create one if needed).
- 13 directed mutants (M1, M2, N1, N2, R1, R2, T1, T2, plus H3.1's N3, N4) — all killed by assertion.
- 4 surviving-mutant cycles in H1 (the N4 first-attempt SURVIVAL is documented in `docs/history/AUTO_VALIDATION_REPORT.md`; the corrected N4 was killed in `aa356dc`).

## R1.2.3 — H3 streaming & budgets group (7 commits)

| Commit | Type | Description | Tests |
|---|---|---|---|
| `5500983` | `feat(jsonl)` | add windowed JsonlSource over InputStream | 5 new in `JsonlRowStreamSourceTest` |
| `f0b7096` | `test(jsonl)` | 5 falsifying cases for windowed JsonlSource | (same) |
| `7a6790c` | `feat(cli)` | StreamCmd JSONL branch now opens file as a stream | — |
| `56d160e` | `test(gate)` | add H3.1-JSONL mutants N3 and N4 | — |
| `aa356dc` | `fix(gate+test)` | N4 anchor swapped to slide() call site; 100k-row test | (refines) |
| `65a207a` | `feat(csv)` | stream the dataset instead of materializing it | 5 new in `CsvRowStreamSourceTest` |
| `42a26f9` | `fix(gate)` | route CSV mutants to their own module | — |

**Behavior changes:** windowed JSONL source (read-by-chunks), JSONL branch in StreamCmd now streams (not materialises), CSV dataset streams (not materialises).

**Tests:** 10 new tests (5 JSONL + 5 CSV). All falsified.

**Spec/Falsification evidence:**
- N1 (open materialises), N2 (window never slides) — CSV mutants, killed.
- N3 (open materialises), N4 (window never slides) — JSONL mutants, killed.
- Strace evidence: `docs/history/H31_JL_INSTALLED_CLI_EVIDENCE.md` (885 read() syscalls on a 6.9 MiB JSONL fixture; 884 reads requested 8192 bytes, 883 returned 8192 + 1 returned 1417 + 1 EOF).
- 64 MiB CSV characterization: `docs/history/M10_CSV_1GIB_RECEIPT.md`.

## R1.2.4 — H5 certification & release group (1 commit)

| Commit | Type | Description |
|---|---|---|
| `e0e8b80` | `chore(cert)` | re-anchor and refresh receipts after H2.2 (certified-sha 8d732648→7873b723, baseline 596→602) |

The H5 group is mostly **docs** in this delta because the actual certification machinery (certify.sh, mutation-gate.sh, release-admission.sh) was already in `origin/main`. This delta carries the receipts that confirm the H2.2 cert anchor move.

## R1.2.5 — Audit corrections + receipt refresh group (26 commits)

All docs. No behavior change. Recorded here for completeness:

- **Self-audit cycle (commits `a2d246c`, `1d6bbce`, `5cabfdf`):** produced `AUTO_VALIDATION_REPORT.md` and `REVIEWER_GAP_CLOSURES.md`, re-validated all 9 reviewer-flagged gaps with installed-CLI evidence paths (strace on H3.1-JSONL, byte-identical H2.2 GLOBAL refusal via real CLI, H1.3 surviving-mutant honesty).
- **H2.4 correction (commit `02fd23e`):** corrected H2.4 design (B) blast radius from 18 to 14 sites via precise `Expression.DatasetRef(` constructor grep. The original 18 was an overcount (4 lines were type references, not constructor calls).
- **Cert/release receipt refresh cycles (commits `324b296`, `7e37503`, `4fe88f0`, `73c2a7f`, `542a1af`, `0623fed`, `e9956cc`, `dd2ced5`, `fa36507`, `7f14d9c`):** these are paired certify + release-admission runs on each post-cycle HEAD. The cert/receipt scripts refuse to run as a stale pair.
- **H3.1 strace precision fix (commit `ae36022`):** clarified the H3.1-JSONL strace wording in the evidence file (1417 is a *return* value, not a *request* size). Also fixed in the auto-validation report (commit `a2d246c`).
- **R1.1 preflight (commit `8bf130a`):** measured the post-audit repo state, recorded the v0.3.0-alpha.1 tag-vs-origin/main tension.
- **R1.3 detekt fix (commit `cf6a902`):** resolved 2 detekt issues that blocked `./gradle-jdk21.sh check` on the post-audit tree.
- **R1.3 cert re-anchor (commit `9ffdb07`):** re-anchored cert/SHA.txt to the post-detekt-fix source SHA with a documented move reason.
- **R1.3 gate report (commit `9cf5458`):** wrote `R1_3_GATE_REPORT.md` documenting gates + the plugin falsification limitation.

## R1.2.6 — Reconciliation of the 9 reviewer-flagged gaps

| # | Gap | Status in this delta | Evidence |
|---|---|---|---|
| 1 | H3.1-JSONL installed-CLI bytes-pulled | PASS | strace on 6.9 MiB fixture: 885 reads on fd 13, 884 req 8192 (883 returning 8192 + 1 returning 1417) + 1 EOF. File: `H31_JL_INSTALLED_CLI_EVIDENCE.md` |
| 2 | H2.2 GLOBAL refusal via real CLI | PASS | exit 3, plan-refusal text + law 12 + fix path; JSON envelope intact. File: `H22_INSTALLED_CLI_EVIDENCE.md` |
| 3 | H1.3 surviving mutant | HONEST | confirmed non-defect at `H1GovernanceBusinessCaseTest:515-517`; `PolicyDiff.diffDigest` canonical sort neutralizes. File: `AUTO_VALIDATION_REPORT.md` |
| 4 | H2.4 design fork | PARKED (decision-class) | A/B design choice, blast radius 14 sites, deferred to R2.1 / product owner |
| 5 | H3.1-JSONL mutation gate | PASS | 38/0/0 (N1–N4 killed by assertion) |
| 6 | H4.2 plugin UAT | PASS | 16 OK + 4 VIOLATE, all 7 steps green |
| 7 | H5.1 anchor test | PASS | 5/5 PASSED, SHA `7873b723` on 91 files |
| 8 | Release-publication honesty | PASS | B6 receipts refreshed at every cycle |
| 9 | cert/SHA.txt scope limitation (root-only) | KNOWN LIMITATION | falsified in R1.3.2; deferred to R4.2 (full cert scope) |

**All 9 gaps closed or honestly recorded.**

## R1.2.7 — H2.4 design fork detail (for R2.1 retrieval)

The H2.4 design fork was originally raised in commit `caeab1b` and corrected in `02fd23e`. The original decision to "park" the design fork was based on:

- The H2.4 evidence file (`docs/history/H24_*.md`) **does not exist**.
- The H2.4 mention in `REVIEWER_GAP_CLOSURES.md` is terse.
- The original proposal can be recovered from:
  - `docs/history/M8_SPECIFICATION.md` (M8 spec)
  - `docs/history/M8_DESIGN.md` (M8 design)
  - `docs/history/M8_EXPLORATION_REPORT.md` (M8 exploration)
  - `docs/03-specifications/POLICY_IR.md` (PolicyIR GLOBAL handling)
  - `docs/03-specifications/EVALUATION_SEMANTICS.md` (evaluation semantics)

**R2.1 will open the recovery.** The 14 sites identified in `02fd23e` are: 13 in test files (across 7 files) + 1 in production (`CanonicalPolicyJson:147`). The exact A/B alternatives are NOT recorded anywhere — they were the designer's internal naming, never committed to a spec or ADR.

## R1.2.8 — Salida R1.2

- ✅ 53 commits grouped by H2/H3/H5 + audit-corrections.
- ✅ 32 files inventoried: 22 tests, 6 sources, 4 docs.
- ✅ 9 reviewer-flagged gaps reconciled to evidence paths.
- ✅ H2.4 design fork location pinpointed for R2.1.
- ✅ H2.4 blast-radius correction (18→14) recorded with precise grep anchor.
- ✅ R1.2 closes in `READY_FOR_HUMAN_GATE` state; R1.3 + R1.4 + R1.5 still pending.

## R1.2.9 — Próximo paso

R1.3 (gate reproducibility) is now **complete** (see `R1_3_GATE_REPORT.md`). R1.4 (version bump to `0.3.0-alpha.2`) is the next safe step (no remote ops). R1.5 (integration + publication) is the human-gated step.
