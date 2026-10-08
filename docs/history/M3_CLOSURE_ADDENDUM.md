# M3 CLOSED — Canonical Kotlin DSL

- Cycle `p-dd1a1c7a7d448b0c/m3-kotlin-dsl` (path **A-lite**) CLOSED via
  `archive.vault.complete` (managed closure, ADR-0075) at 2026-10-08T01:21Z,
  event `evt-187288ad`.
- Published subject: `main = f7d6d30da3dd45af4354fa15cdae75aa1e45cf34` on
  `origin` (`Rubentxu/pipelinek-policy`), verified. No tag, no GitHub release
  (fixture-license deferral active: INC-M3-DSL-001).
- vault-receipt sha256
  `a6300bf353647a6a41716caac4e1cd20142cc263592024c0cfa2159addbd67df`;
  archive-manifest sha256 `d2d83fef…`; ledger 72 eventos.
- Scope delivered (5 commits, `ad96009..f7d6d30`): kernel ADT aditivo
  (CollectionPredicate, TextEquals, BooleanEquals, appliesWhen→NotApplicable,
  ViolationCode append-only), DSL data-only en core
  (`dsl/{BuilderCtx,DslParamValue,Symbols,Combinators,DslScope}`), macro
  `policy{}` helper estático sin FIR, ParamSubstitutor con walk real
  (`${name}`), mutation gate 8 mutaciones (M1..M8), docs
  `KOTLIN_POLICY_DSL.md` §15. 135/135 tests, detekt 0, fitness dual OK,
  back-compat M1 byte-igual.
- Remediation round: 1 blocking defect (params-substitution no-op) + 4
  warnings cerrados en `f7d6d30`; re-verify PASS_WITH_WARNINGS.
- Debt carried forward:
  - `INC-M3-DSL-001` (P0, heredada): fixture-license/PII deferral — tags
    bloqueados.
  - `INC-M3-DSL-002` (P2 MED): M6 mutation test-strength gap (necesita test
    con Literal(TextValue) en RHS para bypassar Selector).
  - `INC-M3-DSL-003..007` (P3 LOW): Evaluator 616 LOC, exception-flow,
    kotlin-reflect testImpl, Rule.params unused, DslParamValue duplicación,
    DSL→kernel coupling (esperado).

## ROADMAP effect

`ROADMAP.md` estado block updates to:

- `M3 = DONE · managed-closure (vault a6300bf3)`
- `M4 = NEXT`
