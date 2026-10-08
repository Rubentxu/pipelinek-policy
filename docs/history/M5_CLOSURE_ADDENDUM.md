# M5 Closure Addendum — PolicyIR + reproducible PolicyBundle

Fecha: 2026-10-08 · Ciclo: p-dd1a1c7a7d448b0c/m5-policy-bundle (A-lite) · Cierre: managed-closure-delivery (ADR-0075), vault-receipt sha256 `3c8b593c12ddbb211761da641b74cad3bc1619d6b6577fc80a647cb33688bcfa`.

## Entregado (commits 05986fd, 1a33740 — main publicado)

- **IR v1** versionando el ADT del kernel: `PolicyIrDocument`, `PolicyIrLowerer` (validación + shape constraints).
- **Canonical JSON codec**: `CanonicalPolicyJson` + `CanonicalPolicyJsonWriter` + parser propio; números serializados como string léxico canónico (Long/Double preservado por `numberOfLexical`).
- **Digests**: `semanticDigest` = sha256(bytes canónicos); `artifactDigest` = sha256(bytes IR + metadata determinista).
- **Pack determinista PKB1**: magic + 4 entradas ordenadas (manifest/policy.ir.json/source-map/metadata), longitudes BE.
- **Admission fail-closed**: `BundleVerifier.verifyPacked` (magic, estructura, digests recalculados, capabilities; corrupto/opcode/función -> refuse).
- **Source map** checkout-independiente + **runtime adapter** al Evaluator del kernel.
- **UAT 6/6** (M5UatAcceptanceTest), 176 tests verdes, detekt 0.

## Proceso con incidentes (honestidad)

1. **Cierre prematuro de build** por sddk-tasks (tasks.md como implementation-receipt sin implementar). Remediation round abierta por el orquestador con gate honesto.
2. **Apply ronda 1 parcial** (dromedary): core WU-1..6, 4 WUs deferred. Verify (hog) FAIL honesto: 0/6 UAT.
3. **Apply ronda 2 bloqueado** (kitten): codec/pack avanzados pero 2 tests rojos por un bug real (decode `toDouble()` alteraba el digest; `verifyPacked` fallaba en cascada). Sin commit.
4. **Orquestador cerró la implementación inline**: fix del bug, detekt 73→0 (writer extraído, constantes, causas propagadas), tests UAT1-6 + source map + reproducibilidad, docs ejecutables (POLICY_IR.md §11, BUNDLE PKB1).
5. **Verify ronda 2** (dragon): PASS_WITH_WARNINGS (W-M5-001 mutation M5 diferidos). 4 receipts + fase cerrada.
6. **Debt-verify inline** (orquestador): rutas de spawn agotadas (MiniMax 429, OpenAI usage-limit). PASS_WITH_WARNINGS: 1 P2 + 4 P3.
7. **Release managed-closure**: recover → block (gate block-condition-met) → delivery_kind → vault → archive.vault.complete (3 gates). Ciclo CLOSED.

## Deuda persistida

- INC-M5-001 (P3): overload deprecated `verifyPacked(bundle,…)` — borrar en M6.
- INC-M5-002 (P3): JSON parser hand-rolled — track para módulo decoder.
- INC-M5-003 (P2): mutation tests M5 dedicados DEFERRED.

## Trampas técnicas nuevas (para M6+)

- `phase.verify.remediate` NO revierte a build: se remedia y se re-emite `phase.verify.complete` (patrón M4).
- Tras `release.recover` el ciclo cae a Open/Build; `cycle.block` sólo es legal desde ahí y exige gate `block-condition-met`; `archive.vault.complete` exige 3 gates (vault-receipt-verified, vault-index-current, release-bypass-declared) + artifacts vault-receipt y archive-manifest.
- `sddk release` subcomandos: plan/apply piden `--tag`; sin permissions.yaml falla (evidencia para recover).

## Siguiente

M6 PipelineK external plugin (M5 desbloqueado; requiere además PipelineK S6 certified).
