# M7 Verify Report — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** verify
**Executed by:** orchestrator (inline, patrón M3/M5/M6: spawns caídos por cuota)
**Verdict: PASS** (sin warnings bloqueantes)

## Matriz REQ → evidencia (OBSERVED)

| REQ | Suite | Resultado |
|---|---|---|
| M7-01 layers (compose/authority) | LayersTest 01a-c | 3/3 PASSED |
| M7-02 supersession | LayersTest 02a-c | 3/3 PASSED |
| M7-03 waivers (expiry/subjects/fingerprint) | WaiversTest 03a-f | 6/6 PASSED |
| M7-04 shadow plugin | EnforcementShadowTest 04a-c | 3/3 PASSED |
| M7-05 diff determinista | PolicyDiffTest 05a-e + waiver-aware | 6/6 PASSED |
| M7-06 IR v1 back-compat | EnforcementShadowTest 06b + PolicyPluginWiringTest (M6 suite intacta) | PASSED |
| M7-07 UAT roadmap | M7UatCorpusTest 07a-d | 4/4 PASSED |

## Regresión

- `./gradle-jdk21.sh check`: BUILD SUCCESSFUL — **219 tests, 0 failures** (root+submódulos), detekt 0 issues.
- Plugin module rerun: 21/21 PASSED (incl. M6 wiring/manifest/ServiceLoader intactos).
- M5 bundle golden tests (Bundle*): BUILD SUCCESSFUL.

## Falsificación (mutation contracts)

Cada REQ semántico tiene su test de mutación: 01c (last-wins), 02c (target
ignorado), 03b/03f (expiry/fingerprint ignorados), 04a (shadow que falla),
05e (digest no canónico), 07d (waiver over-matching). Evidencia de que el
sistema caza mutaciones: durante el build, un refactor anti-detekt eliminó
accidentalmente el alta de rules nuevas y los tests 01a/02a fallaron de
inmediato (detectado y corregido en el propio ciclo).

## Hallazgos

- W1 (informativo): `ENFORCEMENT_INCREASED/DECREASED` y `SEVERITY_CHANGED`
  no tienen transitor de entrada todavía (la semántica de "severity" no
  existe en el ADT M1-M7); las categorías están definidas y el diff las
  emitirá cuando el ADT gane severity. No bloquea: ninguna spec M7 exige
  emitirlas hoy.
- W2 (informativo): la verificación de authority de supersession es
  presence-only (design.md §2.1, follow-up declarado: registry de
  autoridades no definido por la spec).

## Veredicto

PASS. Los 7 REQ tienen evidencia observada; la regresión M1-M6 está intacta.
