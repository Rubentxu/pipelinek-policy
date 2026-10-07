# Functional Core — estilo Haskell donde aporta valor

## 1. Objetivo

La parte semántica del producto debe poder razonarse como funciones matemáticas sobre valores inmutables.

Ideal conceptual:

```text
compilePolicy : AuthoringModel -> CompileResult<PolicyIR>
decode        : Bytes -> DecodeResult<ResourceDocument*>
evaluate      : PolicyIR -> EvaluationInput -> PolicyReport
composeLayers : PolicyLayer* -> ComposeResult<PolicyDeploymentPlan>
applyWaivers  : WaiverSet -> PolicyReport -> WaiverApplicationResult
```

Los efectos se mantienen en una cáscara imperativa estrecha.

## 2. Regla core/edge

```text
IMPURE EDGE                              PURE CORE
──────────                              ─────────
read file      ─┐
http fetch      ├─> bytes/facts ───────> decode/evaluate
clock           ┤
credential use  ┘

PURE RESULT ────────────────────────────> render/write/event
```

## 3. ADTs y total functions

### Nunca

```kotlin
fun evaluate(...): PolicyReport {
    throw IllegalStateException(...)
}
```

### Sí

```kotlin
sealed interface EvaluationResult {
    data class Success(val report: PolicyReport) : EvaluationResult
    data class Refused(val reasons: NonEmptyErrors) : EvaluationResult
}
```

Cada `when` sobre ADTs debe ser exhaustivo.

## 4. Errores acumulables vs fail-fast

### Compile/validation

Preferir acumulación:

```text
policy source
 -> error rule A
 -> error rule B
 -> error function C
```

se reportan juntos cuando son independientes.

### Runtime integrity

Fail closed:

- bundle digest inválido;
- IR version incompatible;
- unknown opcode;
- source corruption;
- type contradiction no gestionada.

## 5. Estado explícito

No:

```text
global mutable EvaluationContext
```

Sí:

```kotlin
data class EvalState(
    val env: EvaluationEnv,
    val accumulators: PersistentMap<AccumulatorId, AccumulatorState>,
    val findings: PersistentList<PolicyViolation>
)
```

Transición:

```text
(EvalState, EvalInstruction) -> EvalStepResult
```

## 6. Reader-like dependencies con context parameters

En vez de `ReaderT` visible al usuario, Kotlin puede expresar dependencias contextuales:

```kotlin
context(ctx: ExprBuildContext)
fun UnknownExpr.number(): NumberExpr = ctx.narrowNumber(this)
```

Context parameters actúan como capability passing; no como service locator.

Reglas:

- context interfaces pequeñas;
- sin `ApplicationContext` universal;
- un context no concede I/O al evaluator;
- si una función no declara capability, no puede usarla.

## 7. Optics conceptuales

La navegación es composicional:

```text
Root
 ∘ OptionalField("spec")
 ∘ OptionalField("containers")
 ∘ Traversal(Each)
 ∘ OptionalField("image")
```

No es necesario adoptar Arrow Optics como contrato, pero el modelo sigue `Lens/Optional/Traversal` conceptualmente.

## 8. Immutable data

Preferencias:

- `data class` inmutables;
- `val`;
- colecciones persistentes donde la medida demuestre valor;
- builders mutables sólo localmente durante parsing/compilación, nunca expuestos;
- normalizar a estructuras inmutables antes de entrar al core.

## 9. No `Any?` en dominio

El adapter puede recibir:

```kotlin
Map<String, Any?>
```

pero debe convertirlo inmediatamente a:

```kotlin
ValueNode
```

El engine no hace casts dispersos.

## 10. Determinismo

`evaluate(bundle, input)` no puede depender de:

- reloj;
- locale del host;
- timezone del host;
- orden de `HashMap`;
- random;
- thread scheduling;
- filesystem;
- network;
- env vars.

Si una policy necesita “ahora”, `now` entra explícitamente como fact:

```text
EvaluationInput.context.now = ...
```

y forma parte del fingerprint.

## 11. Canonicalization

Toda estructura que afecte al digest debe tener orden canónico:

- maps ordenados por clave canonical;
- rule order definido;
- set order canonical;
- decimal representation canonical;
- regex representation preservada;
- no JVM identity/hashCode.

## 12. Interpretadores separados

`PolicyIR` debe poder tener varios interpretadores sin cambiar su semántica:

- normal evaluator;
- explain evaluator;
- dependency/path analyzer;
- cost estimator;
- test mutation interpreter;
- future WASM/native compiler si aporta valor.

La primera release sólo necesita normal + explain/static analyzer.
