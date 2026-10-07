# ADR-0002 — Kotlin + FIR como superficie de autoría

**Status:** ACCEPTED WITH FALSIFIABLE SAFE-CALL SPIKE

## Contexto

Se desea sintaxis Kotlin natural sobre mapas: `root.spec?.replicas?.number()`.

## Decisión

Crear un compiler plugin pequeño que:

1. sintetice/resuelva properties simbólicas sobre `PolicyObjectExpr`;
2. refine shapes/types;
3. ejecute purity/allowed-call diagnostics.

El plugin no implementa evaluator.

## Autoridad

La API explícita `field("...")` es oracle semántico. FIR sugar debe generar IR idéntico.

## Riesgo

Kotlin declara inestable la API de compiler plugins. Se mitiga pinneando versiones y aislando la magia.

## Safe-call

`?.` se certifica sólo si FIR/IDE/incremental/scripts son coherentes; si no, fallback documentado `root.spec.replicas` sin abandonar property syntax.
