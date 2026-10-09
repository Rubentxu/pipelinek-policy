# M8 Exploration Report — Datasets, streaming e índices

**Cycle:** `p-dd1a1c7a7d448b0c/m8-datasets-streaming` · Phase: explore

## Entrada del milestone (VALIDADA)

ROADMAP exige "casos reales medidos que necesiten más que
local-resource evaluation". Evidencia (OBSERVED, producida por M10):

- `M10_CSV_1GIB_RECEIPT.md`: CSV 1 GiB ⇒ 12,741,111 filas ⇒ 12-24 GiB
  heap (OOM a 6g y 12g), evaluate 450s. El materialize completo es el
  cuello de botella medido.
- D-M10-1 ya clasificada "DEFERRED → INPUT M8" en el RC ledger.
- Caso 2 (STRUCTURAL, derivado del primero): el corpus de
  caracterización CI (64 MiB) necesita heap 4g solo para decodificar;
  el mismo dataset en modo streaming debería caber en memoria constante.

## Inventario técnico (OBSERVED)

| Superficie | Estado | Gap para M8 |
|---|---|---|
| CSV decoder | `CsvTokenizer(bytes): List<List<String>>` full-materialize; WHOLE_DOCUMENT/EACH_ROW | Streaming row source (Sequence por fila, memoria O(1) filas) |
| JSON decoder | documento único Jackson | JSONL: un recurso por línea, streaming |
| Expression ADT | `CollectionPredicate(ALL/ANY/NONE/COUNT)` INTRA-recurso | Clasificación LOCAL/AGGREGATE/GLOBAL derivada del IR; agregación cross-fila |
| Evaluator | puro, por recurso, `PolicyReport` | Streaming evaluation perezosa sin materializar corpus |
| Shape/diff/CLI | shape = conteos del IR; CLI check materializa | Dataset plan + budget metrics expuestos |
| Presupuesto streaming (docs/07 §5) | DOCUMENTED: LOCAL O(1)/fila, AGGREGATE bounded, GLOBAL explícito | Es el contrato a implementar |

## Restricciones duras (leyes)

- Ley 5: evaluator core sin I/O ⇒ el streaming de BYTES vive en
  decoders; el kernel recibe Sequences de ValueNode (pure).
- Ley 9/8: sin coerción; Missing/Null/TypeMismatch distintos ⇒ los
  accumulators respetan tipos (COUNT sobre número, no sobre texto).
- Ley 12: GLOBAL no puede disfrazarse de streaming-safe (docs §5):
  el planner RECHAZA GLOBAL sin index plan explícito.
- STOP del ROADMAP: nada de query optimizer genérico. El planner es
  clasificación declarativa, no optimización de costes.

## Decisiones de alcance (DERIVED, a confirmar en specify)

1. Paquete nuevo puro `kernel/dataset`: `DatasetSpec` (named), forma
   `DatasetShape { LOCAL, AGGREGATE, GLOBAL }` derivada de las
   expresiones, `DatasetPlanner` (plan explícito), `Accumulator` ADT
   con cap declarado, `BudgetMetrics`.
2. Streaming en decoders: `CsvRowSource` (Sequence<MappingValue> por
   fila) en policy-decoders-csv; `JsonlSource` en policy-decoders-json.
3. `StreamingEvaluator` en kernel: evalúa reglas LOCAL por fila
   (perezoso), AGGREGATE vía accumulators single-pass; GLOBAL ⇒
   refusal del planner salvo index plan explícito (v1: InMemoryIndex
   declarado, que ES materializar, pero explícito y medido).
4. CLI `stream` (nuevo subcomando): bundle + dataset JSONL/CSV →
   findings streaming con exit codes M9 y emitters existentes.
5. UAT medido: reusar SyntheticCsv de M10: 64 MiB streaming con heap
   acotado (p.ej. -Xmx256m en el test) y mismo digest de flujo de
   reportes que la versión materializada (paridad semántica).

## Next executable task

Specify: 7 REQ (uno por entregable del ROADMAP) + escenarios con
falsificación (incluida la de GLOBAL-sin-plan).
