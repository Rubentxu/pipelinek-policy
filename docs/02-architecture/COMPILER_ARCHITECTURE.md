# Arquitectura del compilador de policies Kotlin

## 1. Objetivo

Ofrecer una superficie Kotlin natural:

```kotlin
root.spec?.replicas?.number() gte 3
```

sin ejecutar Kotlin arbitrario en runtime y sin exigir schema previo.

## 2. Dos APIs, una semántica

### API canónica explícita

```kotlin
root.field("spec")
    .optionalField("replicas")
    .asNumber()
    .gte(number(3))
```

Siempre disponible y testeable sin compiler plugin.

### Surface FIR

```kotlin
root.spec?.replicas?.number() gte 3
```

Baja a la API canónica.

**Ley:** mismo `PolicyIR` canonical digest.

## 3. Responsabilidades máximas del plugin

### FIR-P1 — Symbolic properties

Si:

- receiver es `PolicyObjectExpr<Shape>`;
- estamos en contexto de policy;
- Kotlin normal no resuelve la property;
- shape permite unknown keys;

entonces sintetizar una property simbólica cuyo key es el nombre solicitado.

Con closed shape, una property inexistente produce diagnostic.

### FIR-P2 — Shape/type refinement

Acumular constraints derivados de:

- property access;
- `.number()/.text()/.boolean()/.object()/.array()`;
- operaciones (`gte`, `matches`, etc.);
- external schema si existe.

### FIR-P3 — Purity checker

Dentro de bloques de policy, sólo se admiten:

- stdlib segura explícitamente catalogada;
- policy DSL;
- macros/helpers certificados como policy-pure;
- constantes deterministas.

Rechazar I/O/nondeterminismo/reflection/threading.

## 4. Lo que NO hará

- no implementará evaluator;
- no generará un bytecode alternativo del IR;
- no alterará `==`, `>=` o `&&` con semántica invisible;
- no ejecutará resources;
- no leerá red;
- no decidirá enforcement;
- no contendrá parser YAML/JSON.

## 5. Context parameters

Se usarán para capability-scoping del builder:

```kotlin
context(ctx: ExprBuildContext)
infix fun NumberExpr.gte(other: Number): BoolExpr =
    ctx.gte(this, ctx.number(other))
```

Y para semántica contextual:

```text
ApplicabilityBuildContext
AssertionBuildContext
ViolationBuildContext
```

Evitar un mega `PolicyContext`.

## 6. Safe-call `?.`

### Target

Usar nullability simbólica para expresar posible ausencia de field.

### Gate técnico

Debe probarse en:

- Kotlin JVM normal;
- Kotlin scripts usados por PipelineK;
- IntelliJ completion/highlighting;
- incremental compilation;
- compiler daemon;
- clean build;
- safe-call chains con lambdas/collections.

### Fallback ya decidido

Si safe-call requiere un backend hack no comprendido por FIR/IDE, la sintaxis oficial será:

```kotlin
root.spec.replicas.number()
```

con missing semantics en AST. Se mantiene `.` y propiedades simbólicas; no se sacrifica solidez por `?.`.

## 7. Matriz de versiones

Compiler plugin debe publicar:

```text
policy-compiler version
supported Kotlin exact/minor range
supported IntelliJ baseline
IR version
DSL API version
```

No prometer compatibilidad “cualquier Kotlin”.

## 8. Pruebas del compiler plugin

- diagnostic tests;
- FIR dumps;
- IR dumps si existe backend component;
- box/codegen;
- completion smoke;
- incremental compilation;
- syntax parity;
- mutation tests.

## 9. Exit strategy

La API explícita garantiza que el producto puede seguir funcionando aunque una futura Kotlin release rompa la integración FIR. El compiler plugin es una feature DX, no la única representación del lenguaje.
