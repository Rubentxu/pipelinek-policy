# M8 Implementation Plan — 6 WUs

**Cycle:** `p-dd1a1c7a7d448b0c/m8-datasets-streaming` · Phase: plan

- **WU-1** `kernel/dataset`: DatasetSpec (digest canónico), DatasetShape,
  DatasetShapeAnalyzer, `Expression.DatasetRef` (aditivo). Tests 01a/01b,
  02a/02c.
- **WU-2** Accumulators (Count/Sum con cap), AccumulatorRefusal
  (BUDGET_EXCEEDED/TYPE_MISMATCH), BudgetMetrics. Tests 04a/04b/04c, 06a/06b.
- **WU-3** `policy-decoders-csv`: LazyCsvTokenizer + CsvRowSource
  (Sequence por fila, O(1) filas). Test 03a (heap delta) + unidad.
- **WU-4** `policy-decoders-json`: JsonlSource (Sequence por línea,
  refusal en línea vacía/inválida). Tests unidad.
- **WU-5** DatasetPlanner + IndexPlan/InMemoryIndex + DatasetPlan +
  PlanRefusal + StreamingEvaluator. Tests 02b, 05a/05b/05c, 03b/03c
  (paridad digest streaming vs materializado).
- **WU-6** CLI StreamCmd + registry + dispatch/json-help + matriz exit
  codes (07a-07d) + UAT 64 MiB streaming (heap acotado) + docs.

Done por WU: tests locales + `check` verde.
Done del plan: 20/20 escenarios + paridad + UAT receipt.
