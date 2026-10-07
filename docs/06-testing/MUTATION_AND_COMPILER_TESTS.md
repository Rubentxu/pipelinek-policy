# Falsificación, mutaciones y compiler tests

## 1. Filosofía

Una prueba útil debe morir ante una mutación semántica plausible.

No usar mutation score global como KPI aislado.

## 2. Mutantes obligatorios por milestone

### M1

- GTE→GT;
- AND→OR;
- Missing→False;
- Null→Missing;
- TypeMismatch→Missing.

### M2

- source line off-by-one;
- JSON duplicate last-wins;
- YAML alias explosion limit disabled;
- CSV column shifted.

### M4

- purity checker allows File;
- closed shape treats unknown as open;
- `.number()` returns TextExpr;
- symbolic property drops one path segment.

### M5

- digest excludes parameter;
- canonical map uses insertion order;
- bundle verification skipped.

### M7

- expired waiver accepted;
- lower layer overrides platform;
- shadow blocks execution.

## 3. Compiler negative corpus

Mantener `testData/diagnostics` con casos como:

```text
unknown property in CLOSED
valid property in OPEN
wrong operator type
impure call
ambiguous context
forbidden reflection
invalid pure macro
shape conflict
```

## 4. Golden IR

Policies pequeñas con IR esperado. Cambios en golden requieren explicación de semántica/versionado, no actualizar snapshot automáticamente.
