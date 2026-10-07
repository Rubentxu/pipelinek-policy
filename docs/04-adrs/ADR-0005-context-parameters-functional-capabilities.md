# ADR-0005 — Context parameters para capabilities funcionales

**Status:** ACCEPTED

## Decisión

Usar context parameters de Kotlin 2.4 para declarar dependencias pequeñas del builder/interpreters, evitando service locators y cascadas de implicit receivers.

Ejemplo:

```kotlin
context(ctx: ExprBuildContext)
infix fun NumberExpr.gte(other: Number): BoolExpr = ...
```

## Límites

- no `PolicyContext` omnipotente;
- evaluator context puro;
- I/O no se introduce mediante context capabilities en policy expressions.
