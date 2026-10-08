# M7 UAT Evidence — Layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **CLOSED**
**Commit:** b9b8f431fb0af3cf6f60ccc83bc683f57af87def (feat/m7-layers-waivers-shadow-diff)
**Suite:** `M7UatCorpusTest` (4/4 PASSED, OBSERVED vía `gradle-jdk21.sh test`)

## UAT principal del roadmap (ROADMAP.md §M7)

> Una nueva mandatory rule en shadow detecta 20 would-deny sobre corpus
> histórico, después se activa; un waiver válido salva exactamente un
> subject y no otro.

| Escenario | UAT del roadmap | Resultado | Test |
|---|---|---|---|
| 07a | Shadow mode: mandatory rule nueva detecta 20 would-deny sobre corpus de 20 subjects | **PASSED** — exactamente 20 wouldDeny, outcome Success en todos (no bloquea) | `shadow mode reports would deny for all violating subjects` |
| 07b | Activación: la misma rule en ENFORCED produce 20 violations | **PASSED** — 20 violations, outcome Failure en todos | `enforced mode denies the same corpus` |
| 07c | Waiver válido salva exactamente 1 subject | **PASSED** — waiver team-3: 1 Waived, 19 Active; ningún otro subject afectado | `a single valid waiver saves exactly one subject` |
| 07d | Falsificación: waiver over-matching (mutación) | **PASSED** — el test detecta >1 waived como fallo | `FALSIFICATION over-matching waiver must be caught` |

## UAT de composition (layers) y diff

- LayersTest 6/6 PASSED (compose platform→org→project, supersession
  DuplicateRuleId/UnknownSupersessionTarget, last-wins falsificado en 01c/02c).
- WaiversTest 6/6 PASSED (fingerprint estable, expiry/not-yet-valid,
  subject selectors).
- PolicyDiffTest 6/6 PASSED (digest canónico determinista, CorpusMismatch,
  waiver-aware diff con dedup).
- EnforcementShadowTest 6/6 PASSED (SHADOW+VIOLATED ⇒ Success+wouldDeny;
  REFUSED fail-closed; codec evento v1 aditivo 5/7 campos).

## Regresión

`gradle-jdk21.sh check`: BUILD SUCCESSFUL — **219 tests, 0 failures**
(root + submódulos), detekt clean. Plugin 21/21 (wiring M6 intacto).

## Notas

- Corpus histórico: 20 subjects sintéticos con mandatory rule
  team-must-be-platform (sintético, sin datos reales).
- Todos los runs ejecutados en local; sin publicación remota (P0
  fixture-license deferral, managed-closure ADR-0075).
