# M1 CLOSED — Value + Policy Kernel

- Cycle `p-dd1a1c7a7d448b0c/m1-value-kernel` (path **A-min**) CLOSED via
  `archive.vault.complete` (managed closure, ADR-0075) at 2026-10-07T20:19Z,
  event `evt-8b0c81c3`.
- Published subject: `main = 9578edc662ad09b4037460d8aadd5067cb13917f` on
  `origin` (`Rubentxu/pipelinek-policy`), verified by `git ls-remote`.
  No tag, no GitHub release (policy: none while the fixture-license
  deferral from M0 stays active without waiver).
- vault-receipt sha256
  `7e822b78e715d966d989965224ecab0981e0573352f7d3ef9290f395b2dd8399`.
- Scope delivered (6 commits, `128cfc0..9578edc`): ValueNode, DocumentPath,
  Selector, Expression, PolicySet/Policy/Rule IR, Evaluator con digest
  SHA-256 canónico. 57/57 tests, detekt 0, mutation gate 3/3 vivo
  (2+4+5 kills), verify 15/15 escenarios.
- Debt carried forward:
  - `INC-M1-VK-003` (P0, heredada de M0): fixture-license/PII deferral —
    bloquea tags hasta relicense o clearance per-file.
  - FIND-743081 (P3): smell ramas duplicadas en `canonicalEvaluation`.
  - FIND-658371 (P3): coupling fuga de FQN RuleId.
  Ambos P3 a backlog; no blockers.

## ROADMAP effect

`ROADMAP.md` estado block updates to:

- `M1 = DONE · managed-closure (vault 7e822b78)`
- `M2 = NEXT`
