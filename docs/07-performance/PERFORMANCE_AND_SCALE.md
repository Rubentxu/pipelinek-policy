# Rendimiento y escala

## 1. Principios

- medir antes de optimizar;
- no convertir timing de CI en semántica;
- gates estructurales/allocations cuando sean más estables;
- no sacrificar source mapping/pure semantics por microoptimización prematura.

## 2. Escenarios

### Documents

- 1 KiB / 1 MiB / 100 MiB JSON/YAML;
- CSV 64 MiB / 1 GiB;
- JSONL streaming;
- deep nesting adversarial.

### Policies

- 10 / 100 / 1000 rules;
- many rules sharing paths;
- regex-heavy;
- collection traversals;
- local vs aggregate vs global.

## 3. Métricas

- decode throughput;
- evaluate throughput;
- allocations/document;
- peak RSS;
- selector traversals;
- compiled plan size;
- bundle load time;
- compiler plugin overhead;
- incremental compile delta.

## 4. Selector planning

Después de baseline, agrupar paths compartidos en un trie/plan para evitar repetir navegación.

No introducir optimizer si no hay evidencia.

## 5. Streaming

M8 debe distinguir:

```text
LOCAL       O(1) additional per resource
AGGREGATE   bounded/specialized accumulator
GLOBAL      index/materialization declared explicitly
```

Una policy GLOBAL no puede disfrazarse de streaming-safe.

## 6. Regex safety

Preferir engine/strategy con límites claros; medir catastrophic patterns. Si se usa JVM regex inicialmente, imponer size/cost safeguards y adversarial tests.

## 7. Budgets

Se fijan tras M1/M2/M4 measurements. Documentar hardware y dataset. No inventar ahora números contractuales.
