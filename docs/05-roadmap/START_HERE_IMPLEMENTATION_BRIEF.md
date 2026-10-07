# Brief de arranque para el agente de implementación

## Objetivo inmediato

Cerrar M0 y entrar en M1. No empezar por el compiler plugin.

## Instrucciones

1. Leer `README.md`, `ROADMAP.md`, ADR-0001..0009 y `AGENTS.md`.
2. Inicializar SDDK para el proyecto usando el directorio de usuario; no crear estado SDDK en el repo.
3. Crear workspace Gradle mínimo conforme a `REPOSITORY_BOOTSTRAP.md`.
4. Importar como **fixtures de test** los casos relevantes de `framework_modular`; no copiar el engine Groovy.
5. Caracterizar equivalencia YAML/JSON, CSV y las semantics de precondition/breach que queremos preservar.
6. Implementar primero `ValueNode`, canonicalization y digest.
7. Añadir selectors mínimos y las tres distinciones `Missing/Null/TypeMismatch`.
8. Implementar evaluator puro y RuleEvaluation ADT.
9. Añadir mutantes dirigidos antes de declarar M1 cerrado.
10. Sólo después abrir M2.

## Prohibido en M0/M1

- FIR/compiler plugin;
- PipelineK dependency;
- YAML/JSON parser en `policy-core`;
- Arrow por conveniencia sin spike;
- global registries;
- exception-driven semantics;
- `Any?` fuera del host adapter fixture;
- full suite repetitiva durante cada microcambio.

## Primer checkpoint de valor

Debe existir una demo/test donde un `ValueTree` in-memory con `spec.replicas=2` produce una violation `>=3`, con path y expected/actual deterministas.

## Exit M1

No avanzar si missing/null/type mismatch siguen ambiguos o si el report no es determinista.
