# M0 Closure Addendum — 2026-10-07

## M0 CLOSED — fixture import DEFERRED

M0 closes with the fixture import from the legacy host repository
(`framework_modular`) recorded as **DEFERRED**, not BLOCKED, under the
project's own release-candidate rule (ROADMAP §M10: "todo item abierto
debe quedar explícitamente DEFERRED con rationale no bloqueante o BLOCK
release").

### Rationale (non-blocking)

- The legacy host repo has no `LICENSE` file and ships
  `inputs/personal.csv` (potential PII, content unclassified). Importing
  it wholesale is a legal/privacy decision, not an engineering one.
- M0's engineering purpose (repo bootstrap, build skeleton, fitness
  guard, honest history receipts) is complete and verified
  independently (fresh isolated clone, `BUILD SUCCESSFUL`, 5/5 REQs).
- The fixture import is replaceable: equivalent synthetic fixtures
  (Kubernetes YAML/JSON, plain CSV) are authored as M1+ test inputs
  where characterization requires them. The legacy corpus remains
  available on the developer machine for future, licensed import.
- Cash cost of waiting: zero. Nothing in M1..M5 consumes legacy
  fixtures; M2's decoder UAT uses fresh real-world-shaped inputs.

### Scope of the deferral

| Item | Status | Owner | Re-entry condition |
|---|---|---|---|
| Legacy fixture import (K8s YAML/JSON, CSV, DrawIO adapter fixture) | DEFERRED | user (legal/privacy) | relicensed legacy repo OR explicit per-file clearance |

### Cycle closure record

- Cycle `p-dd1a1c7a7d448b0c/m0-bootstrap` CLOSED via
  `archive.vault.complete` (managed closure, ADR-0075) at
  2026-10-07T18:54:00Z, event `evt-e846f067`.
- Published subject: `main = 1044ec374aff69ca1ac805d689d8507bf3d31708`
  on `origin` (`Rubentxu/pipelinek-policy`), verified by `git
  ls-remote`. No tag, no GitHub release (policy: none while the
  fixture-license decision is deferred; tags may be created by any
  later cycle once the deferral is resolved or explicitly waived).
- vault-receipt sha256
  `d544b1cb04a10ffe01f23e050cd70be8f4b56ff4e89986cd92f884e26bb8a4f5`.
- Follow-up debt carried in the vault as INC-001..INC-004
  (receipt `baseline_sha` divergence, unused `org.jetbrains:annotations`,
  Kotlin 2.4.20 Maven-Central friction, lint tooling at M1 preflight).

### ROADMAP effect

`ROADMAP.md` estado block updates to:

- `M0 = DONE · fixture-import DEFERRED (license/PII)`
- `M1 = NEXT` (unblocked)

Recorded by the SDDK orchestrator; evidence chain lives in the project
ledger (16 events) and `cycle-artifacts/m0-bootstrap/archive/`.
