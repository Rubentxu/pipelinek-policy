# Especificación del DSL Kotlin de policies

## 1. Objetivo de sintaxis

### Open shape

```kotlin
policySet("acme.generic") {
    policy("deployment-baseline") {
        rule("minimum-replicas") {
            appliesWhen {
                root.kind?.text() eq "Deployment"
            }

            require {
                root.spec?.replicas?.number() gte 3
            }

            violation {
                severity = Severity.ERROR
                enforcement = Enforcement.MANDATORY
                at(root.spec?.replicas)
                message = "Debe haber al menos 3 réplicas"
                remediation = "Incrementa spec.replicas a 3 o más."
            }
        }
    }
}
```

### Closed/known shape

Si el shape demuestra `replicas: Int?`:

```kotlin
require {
    root.spec.replicas gte 3
}
```

## 2. Regla de navegación

- `root.foo` significa symbolic field selection.
- no ejecuta acceso al recurso en compile time;
- cada property añade `PathSegment.Key("foo")` al selector;
- open shape: unknown key válida -> `UnknownExpr`;
- closed shape: unknown key -> diagnostic.

## 3. Narrowing explícito

Sobre `UnknownExpr`:

```kotlin
.number()
.text()
.boolean()
.objectValue()
.arrayValue()
```

El narrowing genera constraint y nodo IR.

No coerción:

```text
Text("3").number() -> TYPE_MISMATCH en runtime
```

No parse implícito. Si se desea parsear:

```kotlin
root.version?.text()?.semver()
```

## 4. Operadores

No secuestrar operadores Kotlin que deben devolver `Boolean` real.

Usar infix/functions controladas:

```kotlin
expr eq value
expr neq value
expr gt value
expr gte value
expr lt value
expr lte value
expr oneOf values
expr matches regex
expr startsWith prefix
expr endsWith suffix
expr contains value
```

Composición:

```kotlin
allOf(a, b, c)
anyOf(a, b, c)
not(a)
```

## 5. Collections

```kotlin
root.items?.all { item -> ... }
root.items?.any { item -> ... }
root.items?.none { item -> ... }
root.items?.count()
root.items?.at(0)
```

Map/object:

```kotlin
root.dependencies?.entries()?.all { entry ->
    entry.value?.objectValue()?.version?.text()?.isNotBlank()
}
```

## 6. Applicability

```kotlin
appliesWhen {
    root.environment?.text() eq "production"
}
```

Resultado falso/missing según context produce `NotApplicable`, no violation.

Errores estructurales no gestionados no se convierten silenciosamente en `NotApplicable`.

## 7. Assertions

Dos formas semánticas:

```kotlin
require { expr }
forbid { expr }
```

`forbid { x }` compila a negación declarativa canónica; no es un evaluator distinto.

## 8. `forEach`

```kotlin
forEach(root.employees?.each()) { employee ->
    require {
        employee.age?.number() gte 18
    }
}
```

El IR expresa traversal + assertion template. No guarda lambda JVM.

## 9. Violation metadata

```kotlin
violation {
    severity = Severity.ERROR
    enforcement = Enforcement.MANDATORY
    message = "..."
    remediation = "..."
    docs = uri("https://...")
    at(root.spec?.replicas)
}
```

Mensajes no deben interpolar arbitrariamente objetos. Valores permitidos pasan por renderer seguro.

## 10. Parameters

Policies pueden declarar parámetros tipados:

```kotlin
val minimumReplicas by parameter.int(default = 3)

require {
    root.spec?.replicas?.number() gte minimumReplicas
}
```

Parameters forman parte del deployment config/fingerprint, no del bundle source mutable.

## 11. Pure macros

Permitidas:

```kotlin
policyPure fun TextExpr.isImmutableImage(): BoolExpr =
    not(endsWith(":latest"))
```

El checker debe demostrar que el body sólo llama a primitives/macros puras.

## 12. Datasets

```kotlin
policySet("ownership") {
    dataset("services")
    dataset("owners")

    rule("every-service-has-owner") {
        forEach(dataset("services")) { service ->
            val team = service.team?.text()

            require {
                dataset("owners").any { owner ->
                    owner.team?.text() eq team
                }
            }
        }
    }
}
```

M9, no MVP del evaluator local simple.

## 13. Prohibiciones de lenguaje dentro de policy blocks

Compile errors para:

```text
File/Path I/O
network
processes
System.getenv
current time
Random
reflection
classloading
thread/coroutine launch
mutable globals
synchronized
```

## 14. Diagnostics

Ejemplos deseados:

```text
POLC001 Unresolved policy field 'replicass' in CLOSED shape DeploymentShape.
        Did you mean 'replicas'?

POLC013 Numeric expression cannot use 'matches'.
        actual: NumberExpr
        expected receiver: TextExpr

POLC021 Impure call inside policy expression: java.nio.file.Files.readString.
        Acquire this value outside the policy and pass it as a fact.
```

## 15. M3 implementation status (kernel + DSL pure data-only)

This section anchors the spec to the **actually shipped** surface for the M3
milestone. Higher-level features above remain the long-term target and are
intentionally NOT in M3.

### 15.1 Public DSL surface (`com.pipelinek.policy.dsl.*`)

The M3 DSL is **pure data-only**: every DSL call lowers to a kernel
`Expression` data-class (`Literal`, `FieldRef`, `Comparison`,
`CollectionPredicate`). The macro `policy { }` is the only entry point that
hands a builder to author code; everything inside the block lowers at
**compile time** to canonical kernel IR — no author-supplied lambdas are
materialized into JVM bytecode at evaluation time (architectural law 4).

| Builder | Purpose | Lower-bound |
| --- | --- | --- |
| `inline fun policy(id, block)` | top-level entry point | `PolicySetBuilder` |
| `PolicySetBuilder.policy(id, block)` | one policy in the set | `PolicyBuilder` |
| `PolicyBuilder.rule(id, block)` | one rule in the policy | `RuleBuilder` |
| `BuilderCtx.root()` | start a path | `PathExpr` rooted at `DocumentPath.ROOT` |
| `PathExpr.field(name)` / `optionalField(name)` | descend into a key | `PathExpr` with segment `Key(name)` |
| `PathExpr.asNumber() / asText() / asBoolean()` | leaf narrowing | `TypedExpr` |
| `TypedExpr.eq(v) / neq(v) / gt(v) / gte(v) / lt(v) / lte(v)` | numeric comparisons | `Comparison(NUMBER, op, Literal(NumberValue))` |
| `TypedExpr.eqText(v) / eqBool(v)` | text/boolean comparisons | `Comparison(TEXT/BOOLEAN, op, Literal)` |
| `number(v) / text(v) / boolean(v)` | literal builders | `Literal(ValueNode.*)` |
| `RuleBuilder.require { expr }` / `forbid { expr }` | assertion combinator | `Rule(expression, ...)` (forbid lowers to `eq(BooleanLiteral(false))` semantics) |
| `RuleBuilder.appliesWhen { expr }` | gate; falsy/Missing ⇒ `NotApplicable` | `Rule(appliesWhen = expr, ...)` |
| `RuleBuilder.params { ... }` / `.code(...)` / `.expected(...)` / `.actual(...)` | metadata | `Rule(params, code, expected, actual)` |

### 15.2 Author-facing example (M3)

```kotlin
val set: PolicySet = policy("acme.baseline") {
    policy("deployment-baseline") {
        rule("minimum-replicas") {
            appliesWhen {
                root().field("kind").asText().eqText(text("Deploy"))
            }
            require {
                root().field("spec").optionalField("replicas").asNumber()
                    .gte(number(3))
            }
            code("K8S_MIN_REPLICAS")
            message("spec.replicas must be >= 3")
            expected(">=3")
            actual("read from spec.replicas")
        }
        rule("all-containers-have-image") {
            require {
                root().field("spec")
                    .optionalField("containers")
                    .asNumber()
                    .gte(number(1))   // numeric count of elements
            }
        }
    }
}
```

### 15.3 Lowering guarantee

The DSL **MUST** lower to canonical `PolicySet`/`Policy`/`Rule`/`Expression`
data-classes. The parity contract is verified by
`com.pipelinek.policy.dsl.ParityTest`:

1. `target of reference lowers to canonical AST with identical digest` —
   the data-built reference and the DSL-built set have structurally equal
   ASTs and byte-equal `Evaluator.evaluate(...).digest`.
2. `DSL-produced class graph is restricted to kernel and dsl packages` —
   the runtime class graph reachable from the DSL-produced `PolicySet` only
   contains classes from `com.pipelinek.policy.kernel.*` and
   `com.pipelinek.policy.dsl.*`. The author block does NOT introduce any
   class from the caller's source tree at evaluation time.

### 15.4 Architectural invariants preserved by the M3 DSL

| Law | How the DSL satisfies it |
| --- | --- |
| 1 (`ValueTree` is runtime authority) | DSL reads from a `ValueNode` tree; no I/O. |
| 2 (`PolicyIR` is executable authority) | DSL produces a `PolicySet` data-class tree. |
| 3 (FIR/Kotlin sugar lowers to canonical) | The macro is `inline`; closure classes are NOT materialized at evaluation. |
| 4 (no arbitrary author JVM bytecode) | `Selector`-based predicates; no captured lambdas in IR. |
| 5 (no FS/network/clock/random in evaluator) | DSL does not introduce any such call. |
| 7 (no `Map<String, Any?>` leak) | `params: Map<String, ParamValue>` (sealed `DslParamValue`). |
| 8 (Missing ≠ Null ≠ TypeMismatch) | `Selector.optional(path)` preserves `Missing`; no Null coercion. |
| 9 (no silent coercion) | `asNumber() / asText() / asBoolean()` are explicit; numeric compare on Text ⇒ `TYPE_MISMATCH`. |
| 11 (PipelineK seam only) | The DSL is pure data-only; no PipelineK-specific branch. |
| 12 (lower layers do not silently weaken mandatory upper-layer rules) | `appliesWhen` short-circuit is preserved verbatim; `forbid` lowers to canonical `EQ(Literal(false))` semantics. |

### 15.5 Mutation gate (M3)

`com.pipelinek.policy.kernel.evaluator.MutationGateTest` covers 8 semantic
mutations against the canonical evaluator (all PASS on the unmutated code
and FAIL under the named mutation):

| ID | Mutation |
| --- | --- |
| M1 | `numericCompare` swaps `Operator.LT` and `Operator.GT` |
| M2 | `evalCollectionPredicate` swaps `CollectionOp.ALL` and `CollectionOp.ANY` |
| M3 | `evaluateRule` ignores `appliesWhen` (gate no-op) |
| M4 | `evaluateMainExpression` maps `COLLECTION_PREDICATE_FAILED` to `Error` |
| M6 | `numericCompare` silently coerces `TextValue` to `NumberValue` |
| M7 | `tryAppliesWhen` swallows `MissingValueException` to `True` |
| M8 | `Selector.optional(path)` coerces `Missing` to `NullValue` |

The gate is satisfied when all 9 tests pass on the unmutated evaluator and
the named mutation causes the corresponding test to fail.
