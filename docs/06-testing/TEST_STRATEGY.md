# Estrategia de testing

## 1. Pirámide orientada a propiedades

```text
             installed UAT / external plugin
           architecture acceptance tests
        integration / differential fixtures
      property-based + mutation / fuzz
   unit laws sobre ADTs y funciones puras
```

## 2. Unit laws

### ValueTree

- canonical equality;
- digest independent of map insertion order;
- integer/decimal distinction;
- null preserved.

### Selector

- composition associative donde aplica;
- field path deterministic;
- `each` never reorders arrays.

### Evaluator

- purity;
- same input -> same output;
- no mutation of input;
- missing/null/type mismatch laws.

## 3. Property-based tests

Generar árboles arbitrarios y comprobar:

- encode/decode round trip;
- canonical digest stability;
- explicit path select equivalence;
- renderer no altera semantics;
- order invariants.

## 4. Differential tests

### Format differential

Mismo semantic object serializado a JSON/YAML produce mismo ValueTree/report.

### DSL differential

Explicit API vs FIR sugar produce mismo IR digest.

### Evaluator differential

Reference simple interpreter vs optimized interpreter si aparece una optimización futura.

## 5. Mutation testing

Targeted mutants por propiedad crítica. No convertir porcentaje global en requisito de producto.

Ejemplos:

- GTE→GT;
- Missing→False;
- drop violation;
- waiver expiry ignored;
- closed shape accepts typo;
- purity checker permits File I/O;
- digest ignores parameter.

## 6. Compiler tests

- diagnostics golden;
- FIR dump;
- IR dump sólo si backend extension existe;
- box tests;
- incremental compile;
- daemon/no-daemon;
- supported Kotlin version matrix;
- supported IDE smoke.

## 7. Parser fuzzing

Especialmente:

- YAML anchors/aliases;
- duplicate keys;
- huge depth;
- weird unicode keys;
- malformed CSV quotes;
- decimals/exponents;
- surrogate/UTF-8 boundaries.

## 8. Testing cadence

Durante desarrollo:

- focused module/test class;
- nearest architecture tests.

Integración/release:

- full `./gradlew check` exact SHA;
- installed UAT;
- external PipelineK plugin consumer cuando M6+.
