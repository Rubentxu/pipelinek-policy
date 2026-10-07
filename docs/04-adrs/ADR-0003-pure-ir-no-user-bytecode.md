# ADR-0003 — Runtime sobre IR puro; no policy bytecode arbitrario

**Status:** ACCEPTED

## Decisión

El bundle distribuye `PolicyIR` y data declarativa. El evaluator no carga clases/lambdas del autor para decidir.

## Motivos

- determinismo;
- auditabilidad;
- sandbox más pequeña;
- análisis estático;
- portabilidad futura;
- reproducibilidad;
- posibilidad de explain/diff sin ejecutar Kotlin.

## Consecuencia

Helpers Kotlin sólo se aceptan si se pueden reducir a primitives/function ids puros del lenguaje.
