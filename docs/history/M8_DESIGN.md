# M8 Design — Datasets, streaming e índices

**Cycle:** `p-dd1a1c7a7d448b0c/m8-datasets-streaming` · Phase: design

## Principio rector

El presupuesto de docs/07 §5 ES el contrato:
`LOCAL O(1)/fila · AGGREGATE bounded · GLOBAL explícito`. El planner
clasifica y RECHAZA lo no declarado (ley 12); no optimiza (STOP del
ROADMAP).

## D1 — Capa pura `kernel/dataset` (nuevo paquete, ley 5 intacta)

```
DatasetSpec(id, format, resourceKind)        // REQ 01, digest canónico
DatasetShape { LOCAL, AGGREGATE, GLOBAL }    // REQ 02
DatasetShapeAnalyzer(set: PolicySet)         // clasifica por regla
Accumulator (sealed): Count(cap), Sum(cap)   // REQ 04, refusal tipado
AccumulatorRefusal(code: BUDGET_EXCEEDED | TYPE_MISMATCH)
IndexPlan (sealed): InMemoryIndex(field)     // REQ 05
DatasetPlanner.plan(set, spec, indexPlan?)   // DatasetPlan | PlanRefusal
DatasetPlan(shapesByRule, accumulators, indexes, declaredCost)
BudgetMetrics(rowsSeen, rowsMatched, accumulatorPeak, indexEntries)
StreamingEvaluator                             // REQ 03: secuencia perezosa
```

Clasificación de shape (v1, conservadora y simple):
- Referencia a `dataset` (nuevo nodo `Expression.DatasetRef(name)` —
  aditivo, ley "additive-only" del ADT M3) ⇒ clasificar el predicate:
  ALL/ANY/NONE sobre filas = AGGREGATE (bounded, single-pass);
  COUNT/sumas = AGGREGATE con cap.
- Acceso cross-fila (v1: cualquier DatasetRef usado en Comparison con
  Campo de OTRA fila / patrón "join") = GLOBAL. v1 no añade sintaxis
  de join: GLOBAL se declara explícitamente vía `DatasetSpec.requires`
  o `IndexPlan` — sin plan ⇒ PlanRefusal(GLOBAL_WITHOUT_INDEX) (02b).
- Todo lo demás (FieldRef sobre la fila) = LOCAL.

Nota honesta: v1 NO añade sugar DSL para dataset; el IR explícito
(`DatasetRef`) es la superficie canónica (ley 3: sugar lower, pero el
azúcar puede esperar a demanda real; el IR manda).

## D2 — Streaming en decoders (bytes fuera del kernel)

- `policy-decoders-csv`: `CsvRowSource(bytes): Sequence<ResourceDocument>`
  reusa el CsvTokenizer POR FILA vía un tokenizador perezoso nuevo
  (`LazyCsvTokenizer`: itera el ByteArray sin construir List<List>).
  Cada fila ⇒ MappingValue TextValue (contrato M2 sin inferencia).
- `policy-decoders-json`: `JsonlSource(bytes): Sequence<ResourceDocument>`
  línea a línea (JSONL = un doc por línea; línea vacía ⇒ refusal).
  Jackson por línea; memoria O(línea).

## D3 — StreamingEvaluator (kernel, puro)

`StreamingEvaluator.evaluate(set, rows: Sequence<ValueNode>): Sequence<RowVerdict>`
- Requiere DatasetPlan válido (planner ya corrió; sin plan ⇒ error de
  contrato del caller, no runtime sorpresa).
- LOCAL: map perezoso fila→PolicyReport (reusar `Evaluator.evaluate`
  por fila: paridad gratis con la versión materializada, 03b).
- AGGREGATE: single-pass con Accumulator; el verdict del aggregate se
  emite al final (Sequence de verdicts de fila + verdict final).
- GLOBAL: solo alcanzable si el plan declaró InMemoryIndex; v1 el
  índice se construye DURANTE el primer pass y las reglas GLOBAL se
  evalúan en el verdict final (coste reportado en BudgetMetrics:
  indexEntries). No hay segundo pass oculto.

## D4 — CLI `stream` (M9 patterns)

`StreamCmd(bundlePath, datasetPath, format)`:
- lee bundle (BundleVerifier), planner.plan ⇒ PlanRefusal ⇒ exit 2
  con remediation "declare InMemoryIndex" (07c).
- CsvRowSource/JsonlSource según extensión (patrón CheckCmd).
- Findings via emitters M9 (mismo Finding ADT: resourceId = fila/celda).
- BudgetMetrics a stderr (07b); exit 0/1 por violaciones.
- CommandRegistry: alta del comando ⇒ `--json-help` lo incluye solo
  (07d); DispatchTest/HelpAndExitCodesTest actualizados (matrix 06).

## D5 — Paridad y presupuesto (tests)

- 03b: digest concatenado de `StreamingEvaluator` vs `Evaluator.evaluate`
  sobre el MISMO corpus (64 KiB) — igualdad bit a bit.
- 03a: test de memoria con límite real: el test JVM del módulo csv
  corre 64 MiB streaming; el check CI ya usa maxHeapSize 4g para
  characterization; el nuevo test usa un subproceso con -Xmx256m NO:
  más simple y honesto — usar `Runtime.totalMemory` delta assertion
  (pico de heap < X MiB mientras consume 64 MiB de filas) OBSERVABLE
  sin fork. Fallback: documentar límite del enfoque.
- UAT medido: mismo SyntheticCsv de M10 (cert/), stream vs materialize.

## D6 — Mutaciones/falsificación por REQ

01b digest distinto · 02b GLOBAL sin plan ⇒ refusal · 03c fila mutada
⇒ digest cambia · 04b cap ⇒ BUDGET_EXCEEDED · 04c texto ⇒
TYPE_MISMATCH · 05c plan reporta coste del índice · 06b metrics
difieren · 07c exit 2 con remediation.

## WUs (preview)

1. WU-1 kernel/dataset: Spec, Shape, Analyzer, DatasetRef ADT.
2. WU-2 Accumulators + refusals + BudgetMetrics.
3. WU-3 LazyCsvTokenizer + CsvRowSource (csv module).
4. WU-4 JsonlSource (json module).
5. WU-5 Planner + DatasetPlan + StreamingEvaluator + paridad.
6. WU-6 CLI stream + registry + tests matriz exit codes.
