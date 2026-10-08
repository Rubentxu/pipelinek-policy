# M9 Debt Report — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** verify (debt)

Sin deuda P0/P1/P2 nueva. Tres follow-ups informativos del verify-report
más el estado heredado.

## Deuda nueva

| ID | Descripción | Severidad | Origen |
|---|---|---|---|
| D-M9-1 | explain sin traza por nodo del evaluator: v1 usa el árbol estático del IR + locations de violación. Follow-up: exponer evaluación por nodo cuando el evaluator lo permita. | P3 | verify W1 (DOCUMENTED design §4) |
| D-M9-2 | compile v1 solo IR canónico JSON (no .kts): dependiente del retorno del authoring FIR (M4 FAIL/Opción C). Contrato estable; rama .kts futura. | P3 | verify W2 (DOCUMENTED M4) |
| D-M9-3 | diff CLI single-resource (PolicyDiff.of es single-report). Agregación multi-recurso es follow-up de diseño, no exigida por spec M9. | P3 | verify W3 (STRUCTURAL PolicyDiff) |

## Deuda heredada

| INC/Deuda | Estado |
|---|---|
| INC-005 (P2) mutation tests M6 | DEFERRED (backfill pendiente; M9 añadió mutation contracts propios: 01c/02b/02c/03b/04b/05b/06b/07c/08d/09a) |
| D-M7-1/D-M7-2 (P3) | abiertas (severity diff transitor; authority registry) |

## Veredicto

Severidades y prioridades asignadas a todo lo detectado. Nada bloquea
release/archive.
