# Migración conceptual desde `framework_modular/policies`

## 1. Objetivo

Reutilizar casos, no código legacy.

## 2. Fixture migration

Importar como corpus:

- Kubernetes deployment YAML;
- equivalente JSON;
- policies de replicas/cpu/memory convertidas a Kotlin;
- CSV personnel;
- DrawIO topology como adapter posterior.

## 3. Rule conversion example

Legacy:

```yaml
fields:
  replicas: Deployment.spec.replicas
predicate: replicas >= 5
```

Nuevo OPEN DSL:

```kotlin
rule("minimum-replicas") {
    appliesWhen { root.kind?.text() eq "Deployment" }
    require { root.spec?.replicas?.number() gte 5 }
}
```

## 4. Preconditions

Legacy `fieldsPrecondition + precondition` se mapea a `appliesWhen`.

## 5. Error messages

Interpolación Groovy arbitraria desaparece. Violation template utiliza safe value references.

## 6. Source location

Legacy `FieldPathNormalizer + locate by value` se sustituye por `SourceMap` generado en decode.

## 7. Admission history

Legacy `admittedResources/rejectedResources` mutable no migra al evaluator. Si se requiere historial, una aplicación/adapter almacena `PolicyReport`.

## 8. DrawIO

No meter en M2 inicial. Cuando se implemente:

```text
DrawIO XML -> normalized ValueTree + Element SourceAnchor
```

Las policies no conocen XML.

## 9. Differential parity

Por cada legacy fixture migrado:

- registrar legacy expected verdict;
- crear nueva policy;
- comprobar parity donde la semántica antigua era correcta;
- documentar divergencias deliberadas (p.ej. quantity string comparison corregida).

No prometer byte/bug compatibility.
