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
