# Investigación comparativa — motores de policy y Kotlin moderno

## 1. OPA/Rego

Aporta:

- políticas declarativas sobre datos estructurados;
- separación input/data/policy;
- bundles versionables;
- decision logs con bundle revision;
- buen modelo mental para JSON-like data.

No copiar:

- Rego como lenguaje de usuario;
- dependencia de un segundo runtime OPA;
- necesidad de serializar todo a un protocolo externo para evaluación local.

Lección adoptada: **policy sobre datos estructurados + bundle/decision provenance**.

## 2. Cedar

Aporta:

- scope claro: autorización;
- schema validation;
- ausencia de I/O en policies;
- terminación y aislamiento entre policies;
- tipos de extensión como datetime/decimal/duration/ip.

No copiar:

- forzar todo al modelo principal/action/resource/context para compliance arbitrario.

Lección adoptada: **closed pure evaluator, schema como aumento de seguridad, domain functions explícitas**.

## 3. Sentinel

Aporta:

- separación entre lógica y enforcement level;
- advisory / soft-mandatory / hard-mandatory;
- overrides auditables.

No copiar:

- permitir imports externos dentro del motor como mecanismo normal.

Lección adoptada: `Severity`, `Enforcement` y `Rollout` independientes.

## 4. Kyverno

Aporta:

- match/preconditions;
- policy exceptions;
- reports;
- UX muy orientada a findings.

No copiar:

- Kubernetes como ontología del engine;
- YAML como superficie principal de policy;
- mutate/generate en v1.

Lección adoptada: exceptions/waivers como objetos gobernados y reporting de primera clase.

## 5. Conftest

Aporta:

- validar formatos heterogéneos;
- integración CLI/CI;
- source metadata útil.

Lección adoptada: format adapters + source-aware findings.

## 6. CUE

Aporta:

- constraints desacoplados del formato físico;
- aplicación de constraints a JSON/YAML;
- composición/unificación.

Lección adoptada: `ShapeModel` y policy constraints no deben depender del parser.

## 7. CEL

Aporta una arquitectura especialmente relevante:

- expressions embebibles;
- compile/check antes de evaluate;
- AST verificado reutilizable;
- maps/lists dinámicos junto a tipos más fuertes;
- host functions controladas.

Lección adoptada: **Kotlin DSL → checked AST/IR → evaluate many**.

## 8. Kotlin DataFrame Compiler Plugin

Es el referente técnico más cercano para la UX:

- propiedades sintetizadas (`df.name` en lugar de `df["name"]`);
- type-safe access;
- IDE completion;
- schema tracking/refinement;
- compiler plugin oficial de Kotlin.

Adaptación propuesta:

```text
DataFrame<T>                 PolicyObjectExpr<S>
df.temperature               root.spec
column                       map key
DataColumn<Int>              NumberExpr
schema                       ShapeModel
compiler refinement          policy shape refinement
```

## 9. Kotlin 2.4+

### Context parameters

Son estables en Kotlin 2.4 salvo áreas concretas como argumentos explícitos/callable refs. Son adecuados para expresar capacidades de DSL sin service locator ni cascadas de receivers.

Ejemplo conceptual:

```kotlin
context(ctx: ExprBuildContext)
infix fun NumberExpr.gte(other: Number): BoolExpr =
    ctx.gte(this, ctx.const(other))
```

### FIR compiler extensions

Usos previstos:

- `FirDeclarationGenerationExtension`: propiedades simbólicas;
- `FirAdditionalCheckersExtension`: purity/allowed-call checks;
- session component: shape/type constraint state.

Riesgo: API inestable. Mitigación: plugin pequeño, versión Kotlin pinneada, paridad con API explícita y matriz de compatibilidad.

### KSP

Adecuado para:

- generar accessors desde modelos Kotlin anotados;
- schemas conocidos declarativamente.

No sirve para analizar bodies/expressions, por lo que no sustituye el checker FIR.

### Sealed ADTs / value classes

Adecuados para:

- errores exhaustivos;
- estados de evaluación;
- ids sin wrappers pesados;
- IR cerrado.

### Builder inference

Útil para lambdas estructurales como `all { item -> ... }`, evitando genéricos explícitos.

## 10. Diferenciación del producto

La combinación diferenciadora es:

```text
Kotlin-native authoring
+ arbitrary structured data
+ open-to-closed gradual shapes
+ pure checked IR
+ source mapping
+ policy diff/shadow/waivers
+ PipelineK capability acquisition
+ agent-grade remediation output
```

El producto no debe presentarse como “otro OPA”. Es un **Kotlin policy compiler + pure evaluator para structured facts**, con PipelineK como integración de ejecución.
