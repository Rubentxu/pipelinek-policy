# M7 Debt Report — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** verify (debt)

Deuda nueva M7: ninguna bloqueante. Se registran dos follow-ups informativos
derivados del verify-report y el estado del INC-005 heredado.

## Deuda nueva

| ID | Descripción | Severidad | Origen |
|---|---|---|---|
| D-M7-1 | Categorías diff `ENFORCEMENT_INCREASED/DECREASED` y `SEVERITY_CHANGED` definidas pero sin transitor de entrada: el ADT de regla no tiene concepto de severity hasta que un milestone lo introduzca. Emisión diferida por diseño, no por deuda de implementación. | P3 | verify W1 (DERIVED de design.md §4) |
| D-M7-2 | Authority check de supersession es presence-only (el target existe en la capa). Registry de autoridades no definido por la spec M7; follow-up declarado en design. | P3 | verify W2 (DOCUMENTED en design.md §2.1) |

## Deuda heredada

| INC | Estado | Nota |
|---|---|---|
| INC-005 (P2) | DEFERRED | Mutation tests M6. M7 ya incluyó mutation contracts por semántica nueva (01c/02c/03b/03f/04a/05e/07d); el backfill sigue pendiente de planificación. M7 no tocó `PolicyPluginDeclaration`/build del plugin, por lo que no surge aquí el hueco de INC-005. |

## Veredicto

Sin deuda P0/P1/P2 nueva. Severidades asignadas a todo lo detectado.
