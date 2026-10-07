# Arquitectura de referencia

## 1. Vista general

```text
                           AUTHORING PLANE

  policy.kt
      │
      ▼
 Kotlin 2.4 compiler + policy FIR plugin
      │
      ├── symbolic property resolution (`root.foo`)
      ├── purity / allowed-call checks
      ├── gradual shape constraints
      └── diagnostics
      │
      ▼
 Canonical Policy AST
      │
      ▼
 type/shape validation + normalization
      │
      ▼
 PolicyIR
      │
      ▼
 reproducible PolicyBundle

────────────────────────────────────────────────────────────
                            RUNTIME PLANE

 YAML ─┐
 JSON ─┤
 CSV ──┤
 TOML ─┼── ResourceDecoder ──> ResourceDocument
 Map ──┤                          │
 XML ──┘                          ├── ValueTree
                                  └── SourceMap
                                        │
 PolicyBundle ───────────────────────────┤
                                        ▼
                                Pure PolicyEvaluator
                                        │
                                        ▼
                                  PolicyReport
                           ┌─────────────┼─────────────┐
                           ▼             ▼             ▼
                        human          agent        enforcement
                        render         JSONL        interpreter

────────────────────────────────────────────────────────────
                         PIPELINEK ADAPTER

 PipelineK facts/resources
      │
      ▼
 `policy.check` external Step
      │
      ▼
 same runtime plane
      │
      ├── typed Step result
      ├── bounded plugin events
      └── PipelineOutcome mapping according to enforcement
```

## 2. Bounded contexts

### Policy Authoring

Responsable de convertir Kotlin controlado a IR.

No evalúa recursos reales.

### Policy Domain

ADTs de policy, rule, expression, violation, waiver, layer y report.

Cero I/O.

### Resource Model

Representa `ValueTree`, paths, source anchors y datasets.

Cero policy semantics.

### Decoder

Convierte orígenes físicos en `ResourceDocument`.

Puede hacer I/O en adapters externos; el decoder puro opera sobre bytes/chars suministrados.

### Policy Evaluation

Interpreta `PolicyIR` sobre facts.

Puro y determinista.

### Bundle

Packaging, digest, compatibility, provenance y signatures.

### Enforcement

Transforma `PolicyReport + PolicyDeploymentConfig` en `Proceed/Warning/OverrideRequired/Reject`.

No cambia el significado de la policy.

### PipelineK Adapter

Step/event/DSL façade. No contiene semántica de evaluación propia.

## 3. Dependency rule

```text
pipelinek-plugin ──> application/use-cases ──> domain
bundle adapters ───> application/use-cases ──> domain
decoders ──────────> decoder-api ────────────> resource-domain
compiler-plugin ───> authoring-api ──────────> policy-ir/domain

policy-domain       -> NOTHING framework-specific
policy-evaluator    -> policy-domain + resource-domain
resource-domain     -> NOTHING format-specific
```

Prohibido:

```text
policy-evaluator -> PipelineK
policy-domain -> Jackson/SnakeYAML/CSV
policy-domain -> FIR/Kotlin compiler
resource-domain -> PolicyEvaluator
compiler-plugin -> pipeline-application
```

## 4. Autoridades

| Concepto | Autoridad |
|---|---|
| valor estructurado runtime | `ValueTree` |
| ubicación en origen | `SourceMap` |
| policy ejecutable | `PolicyIR` |
| bundle identity | digest canónico |
| sintaxis Kotlin | authoring surface, no autoridad runtime |
| decisión por regla | `RuleEvaluation` |
| enforcement | `EnforcementInterpreter` |
| policy deployment hierarchy | `PolicyDeploymentPlan` |
| excepción | `Waiver` |
| PipelineK outcome | PipelineK, no PolicyEvent |

## 5. No hay segunda semántica

La API explícita canónica:

```kotlin
root.field("spec")
    .field("replicas")
    .asNumber()
    .gte(number(3))
```

y el azúcar:

```kotlin
root.spec?.replicas?.number() gte 3
```

deben producir IR canónico idéntico.

Esta paridad se certifica en cada release del compiler plugin.

## 6. Arquitectura emergente deliberada

No se decide prematuramente:

- formato binario final de bundles;
- dependencia Arrow;
- persistent collections;
- storage de policy decision history;
- distribución remota de bundles;
- UI;
- mutation engine;
- compiler backend transforms.

Cada una requiere caso real + UAT. El roadmap fuerza primero el kernel puro y los seams.
