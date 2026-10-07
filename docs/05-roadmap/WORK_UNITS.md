# Work Units — primeros bloques ejecutables

Estos work units refinan `ROADMAP.md`; no cambian su secuencia.

## WU-001 — Characterization corpus

Importar fixtures equivalentes de YAML/JSON y CSV desde la referencia. Congelar expected semantic trees en tests, no copiar implementation.

## WU-002 — ValueNode + canonical digest

Implementar ADT y digest determinista. Property-based tests de ordering/maps/numbers.

## WU-003 — Selector algebra

Root/field/index/each/entries y leyes de composición.

## WU-004 — Evaluation triad

Missing/Null/TypeMismatch y anti-coercion tests.

## WU-005 — Rules/evaluator

Aplicability + assertion + violations; deterministic order.

## WU-006 — Resource decoder seam

API + refusals + SourceAnchor.

## WU-007 — JSON/YAML differential parser

Semantic parity corpus.

## WU-008 — Explicit Kotlin DSL

Context parameters + AST construction; no FIR todavía.

## WU-009 — FIR property spike

Property synthesis + parity oracle.

## WU-010 — Purity checker

Diagnostics para side effects.

Cada WU termina con:

- focused tests;
- mutation/falsification relevante;
- Conventional Commit;
- SDDK checkpoint;
- no full suite salvo integración/release según política del repo.
