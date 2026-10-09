# M8 Specification — Datasets, streaming e índices

**Cycle:** `p-dd1a1c7a7d448b0c/m8-datasets-streaming` · Phase: specify

Formato heredado: REQ por entregable del ROADMAP, escenarios `NNx`
(a positivo, b/c falsificación), criterios de salida.

## REQ M8-01 — Named datasets

`DatasetSpec(id, format, resourceKind)` nombra un dataset; el IR puede
referirse a datasets por nombre en `appliesWhen` (v1: dataset por CLI
arg; el nombre viaja en el plan).

- 01a: un DatasetSpec con id/formato válido se construye y su digest
  canónico es estable.
- 01b (falsificación): dos DatasetSpec con distinto formato producen
  distinto digest (el nombre no es decorativo).

## REQ M8-02 — Shape LOCAL/AGGREGATE/GLOBAL derivada del IR

`DatasetShapeAnalyzer` clasifica cada regla del PolicySet:
- LOCAL: expresiones que solo referencian la fila actual.
- AGGREGATE: CollectionPredicate/COUNT sobre el dataset completo ⇒
  acumulador single-pass con cap.
- GLOBAL: requiere acceso aleatorio/cross-referencias ⇒ SOLO válido
  con index plan explícito (ley 12).

- 02a: política con reglas por-fila ⇒ shape LOCAL para todas.
- 02b (falsificación/ley 12): regla GLOBAL sin index plan ⇒ el planner
  RECHAZA (refusal tipado), nunca la degradar a streaming silencioso.
- 02c: regla COUNT sobre dataset ⇒ AGGREGATE con cap declarado.

## REQ M8-03 — Streaming row/record evaluation

- 03a: `CsvRowSource` (decoders-csv) y `JsonlSource` (decoders-json)
  emiten `Sequence<ResourceDocument>` fila a fila sin materializar el
  corpus completo (test de memoria: 64 MiB con heap 256m, OBSERVADO
  en el propio CI como límite del test JVM).
- 03b: paridad semántica — el flujo de reportes del streaming produce
  el MISMO digest concatenado que la evaluación materializada (para
  reglas LOCAL).
- 03c (falsificación): una fila mutada cambia el digest del stream
  (el streaming no es trivial-all-green).

## REQ M8-04 — Bounded accumulators

`Accumulator` ADT: `CountAccumulator(cap)`, `SumAccumulator(cap)`…
single-pass, rechaza desbordar el cap con refusal tipado
(NO_MISMATCH de tipos: COUNT solo sobre NumberValue; texto ⇒
TYPE_MISMATCH, ley 9).

- 04a: COUNT de 100k filas con cap 200k ⇒ resultado exacto.
- 04b (falsificación): cap 10 con 11 matches ⇒ refusal BUDGET_EXCEEDED,
  no número truncado ni excepción sin tipar.
- 04c: acumular sobre TextValue ⇒ TYPE_MISMATCH (sin coerción).

## REQ M8-05 — Index requirements derivadas del IR + plan explícito

`DatasetPlanner.plan(set, datasetSpec, indexPlan?)` produce un
`DatasetPlan` (shape por regla, accumulators, índices requeridos).
GLOBAL exige `IndexPlan` declarado (v1: `InMemoryIndex(field)`).

- 05a: plan de política LOCAL ⇒ cero accumulators, cero índices.
- 05b: plan con AGGREGATE ⇒ accumulator listado con cap.
- 05c (falsificación): GLOBAL + InMemoryIndex declarado ⇒ plan OK y
  el plan REPORTA la materialización como coste (métrica), no la oculta.

## REQ M8-06 — Resource budget metrics

`BudgetMetrics(rowsSeen, rowsMatched, accumulatorPeak, indexEntries)`
emitidas al terminar un stream; deterministas.

- 06a: dataset de N filas ⇒ rowsSeen == N, rowsMatched == violaciones.
- 06b (falsificación): metrics de un stream con mutación de una fila
  difieren del baseline (las métricas miden de verdad).

## REQ M8-07 — CLI stream (DX agente)

Subcomando `stream`: `policy-cli stream <bundle> <dataset> [--format
jsonl]` consume CSV/JSONL en streaming, emite findings por los emitters
M9 y respeta exit codes 0/1/2/3.

- 07a: dataset CSV con violaciones ⇒ exit 1, findings JSONL accionables
  (mismos campos M9).
- 07b: dataset sin violaciones ⇒ exit 0, métricas en stderr.
- 07c (falsificación): política GLOBAL sin índice en el bundle ⇒ exit 2
  (error de uso/entrada) con mensaje de plan requerido, NO evaluación
  degradada.
- 07d: `--json-help` del registry incluye `stream` (derivado, no
  hardcodeado).

## Criterios de salida

- check verde con las suites nuevas (kernel/dataset + decoders + CLI).
- Test de paridad streaming↔materializado (03b) verde.
- UAT: 64 MiB streaming en CI con heap acotado y receipt de métricas.
