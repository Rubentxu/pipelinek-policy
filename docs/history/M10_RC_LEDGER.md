# M10 Release-Candidate Ledger

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · REQ M10-10

Regla (10b): NADA puede quedar "pendiente informal". Todo item abierto
del proyecto aparece aquí con severidad, prioridad y disposition
explícitos (RESOLVED-IN-M10 / DEFERRED + rationale no bloqueante).

## Ledger

| Item | Severidad | Prioridad | Disposition | Rationale |
|---|---|---|---|---|
| INC-005 · mutation tests backfill M6 (wiring plugin) | P2 | media | **DEFERRED** | Los mutation contracts por REQ existen desde M7 (layers/waivers) y M9 (01c/02b/02c/03b/04b/05b/06b/07c/08d/09a); el backfill específico del wiring M6 queda ligado al retorno del authoring FIR (M4 Opción C), que reabrirá el plugin path. No bloquea RC: la superficie M6 está cubierta por PolicyPluginWiringTest + UAT installDist. |
| D-M7-1 · severity diff transitorio | P3 | baja | **DEFERRED** | Cosmético de reporte; sin impacto semántico (digest determinista). Se ataca con el rediseño de reportes si hay demanda. |
| D-M7-2 · authority registry | P3 | baja | **DEFERRED** | Mejora de gobierno para multi-equipo; sin consumidores actuales. |
| D-M9-1 · explain sin traza por nodo | P3 | baja | **DEFERRED** | Scope guard documentado en design M9 §4; contrato ruleId+árbol+locations cumplido. Requiere exponer evaluación por nodo en el kernel. |
| D-M9-2 · compile solo IR JSON (no .kts) | P3 | baja | **DEFERRED** | Dependiente del authoring FIR (M4 FAIL/Opción C). Contrato CLI estable; añadir rama .kts no rompe compat. |
| D-M9-3 · diff single-resource | P3 | baja | **DEFERRED** | PolicyDiff.of es single-report por diseño; agregación multi-recurso es follow-up de diseño. |
| CSV full-materialize (hallazgo M10 1 GiB) | P3 | media | **DEFERRED → INPUT M8** | 1 GiB ⇒ 12-24 GiB heap (receipt M10_CSV_1GIB_RECEIPT). El presupuesto streaming LOCAL/AGGREGATE/GLOBAL de M8 es la solución diseñada; M10 certifica characterization, no optimización. |
| M4 FIR authoring | - | - | **FAIL documentado (Opción C)** | Estado permanente del roadmap; spike m4-fir-spike conservado. No es deuda abierta: es una decisión. |
| M8 datasets/streaming | - | - | **BLOCKED-BY entrada** | Requiere "casos reales medidos" (entrada externa). Único milestone no DONE; no bloquea RC de M10 (dependencias M6..M9 DONE). |

## Cero pendientes informales

Verificación (10b): grep de "TODO/FIXME/XXX" en fuentes main y revisión
de debt-reports M5-M9 no arroja ningún item abierto fuera de este
ledger. Todo lo listado tiene disposition explícita y rationale.

RC condition del ROADMAP: **satisfecha** — ningún item abierto queda
como "pendiente informal".
