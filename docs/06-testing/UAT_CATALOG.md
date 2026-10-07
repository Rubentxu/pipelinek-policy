# UAT Catalog

## UAT-001 — JSON/YAML semantic parity

**Given** dos documentos equivalentes.

**When** se decodifican y evalúan con la misma policy.

**Then**:

- mismo semantic ValueTree digest;
- mismo RuleEvaluation;
- mismo actual/expected;
- SourceAnchor diferente y correcto.

## UAT-002 — Arbitrary open map

Sin schema previo:

```kotlin
root.cualquier?.estructura?.valor?.number() gte 40
```

compila y evalúa un `Map<String, Any?>` equivalente.

## UAT-003 — Closed shape typo

Con schema cerrado `replicas`:

```kotlin
root.spec.replicass
```

no compila y sugiere `replicas`.

## UAT-004 — Type safety

```kotlin
root.spec?.replicas?.number() matches "x"
```

no compila.

## UAT-005 — Missing/Null/Type mismatch

Tres documents:

```text
{a:{b:null}}
{a:{}}
{a:{b:"3"}}
```

producen estados distintos ante assertion numérica.

## UAT-006 — CSV rows

CSV con 1000 rows en `EACH_ROW`:

- cada violation apunta a row+column;
- no se materializa obligatoriamente un array semántico único;
- order stable.

## UAT-007 — Pure authoring rejection

Dentro de policy:

```kotlin
Files.readString(...)
Instant.now()
Random.nextInt()
```

fallan compilación con diagnostics propios.

## UAT-008 — FIR/explicit parity

Dos policies equivalentes, una sugar y otra explicit API, producen `semanticDigest` idéntico.

## UAT-009 — Bundle no necesita source classes

Compilar bundle, arrancar evaluator con sólo runtime + bundle y evaluar correctamente.

## UAT-010 — Corrupt bundle

Un byte alterado o opcode desconocido -> `BundleAdmissionRefused`, nunca best-effort evaluation.

## UAT-011 — Source location

Violation sobre YAML/JSON/CSV señala exactamente field/cell responsable; no búsqueda ambigua por valor.

## UAT-012 — Shadow

Mandatory rule en `SHADOW` genera would-reject pero execution decision `Proceed`.

## UAT-013 — Waiver scope

Waiver para `service-a` no afecta `service-b`; expired waiver no aplica.

## UAT-014 — Layer monotonicity

Project policy no puede eliminar mandatory platform rule sin supersession grant.

## UAT-015 — Policy diff

Bundle A/B sobre corpus fijo clasifica correctamente new/resolved violations y enforcement changes.

## UAT-016 — PipelineK external plugin

Installed PipelineK + external JAR:

- `.pipeline.kts` compila;
- Step se descubre sin core edits;
- policy real evalúa;
- typed result correcto;
- bounded plugin events;
- rejection produce expected StepOutcome.

## UAT-017 — Agent remediation

JSONL finding contiene suficiente información para identificar fichero/path/expected/remediation sin consultar logs humanos.

## UAT-018 — Reproducible compile

Mismo checkout/toolchain/input compilado dos veces -> mismo semantic digest.

## UAT-019 — 1 GiB CSV characterization (M8/M10)

Streaming mode mantiene memoria dentro de budget medido; no gatear por wall-clock frágil.

## UAT-020 — Compiler upgrade canary

Actualizar Kotlin minor en branch de compatibilidad y ejecutar diagnostics/FIR/parity suite. Si falla, release de compiler plugin queda bloqueada pero runtime explícito sigue funcional.
