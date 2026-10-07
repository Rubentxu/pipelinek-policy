# ADR-0004 — Shapes graduales OPEN / OBSERVED / CLOSED

**Status:** ACCEPTED

## Decisión

No exigir schema para authoring.

- `OPEN`: cualquier key válida; devuelve `UnknownExpr`.
- `OBSERVED`: samples/policy constraints aportan hints/refinement.
- `CLOSED`: schema formal permite diagnostics de propiedades inexistentes y tipos conocidos.

## Regla

El evaluator es el mismo en los tres modos.

## Consecuencia

Se conserva utilidad “cualquier diccionario” y se obtiene type safety creciente cuando existe conocimiento adicional.
