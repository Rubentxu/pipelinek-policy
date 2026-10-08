# ROADMAP — pipelinek-policy

**Autoridad:** este fichero es la única fuente de secuenciación del proyecto.

## Estado inicial

```text
M0  Grounding / bootstrap                    DONE · fixture-import DEFERRED (license/PII)
M1  Pure Value + Policy Kernel               DONE · managed-closure (vault 7e822b78)
M2  Decoder SPI + source-aware resources     DONE · managed-closure (vault 744b1242)
M3  Canonical Kotlin DSL                     DONE · managed-closure (vault a6300bf3)
M4  FIR Kotlin authoring                     FAIL documentado · Opción C adoptada · spike branch m4-fir-spike conservada
M5  Policy IR + reproducible bundle          BLOCKED-BY-M3/M4
M6  PipelineK external plugin                BLOCKED-BY-M5 + PipelineK S6 certified
M7  Layers / waivers / shadow / diff         BLOCKED-BY-M5
M8  Datasets / streaming / indexing          BLOCKED-BY-M2/M5
M9  Agent/CLI production surface             BLOCKED-BY-M5/M7
M10 Production certification                 BLOCKED-BY-M6..M9
```

---

# M0 — Grounding, repository bootstrap y compatibility fortress

## Valor

Crear un repo que pueda evolucionar sin volver a discutir qué producto se construye.

## Entregables

1. estructura Gradle Kotlin/JVM mínima;
2. Kotlin 2.4.x pinneado;
3. JDK 21;
4. SDDK wiring según reglas globales, estado fuera del repo;
5. `ROADMAP.md` autoridad única;
6. ADR-0001..0009 aceptados;
7. fixtures importados/copied-as-tests desde `framework_modular`:
   - Kubernetes YAML;
   - Kubernetes JSON equivalente;
   - CSV sencillo;
   - DrawIO sólo como future adapter fixture si no se implementa aún;
8. characterization document de los behaviors antiguos;
9. baseline build/test local sin GitHub Actions como autoridad de CI.

## Gate

- repo limpio;
- tests baseline ejecutables;
- ningún production package depende de PipelineK todavía;
- `./gradlew check` verde.

## Exit receipt

`docs/history/M0_BOOTSTRAP_RECEIPT.md` con SHA exacto y comandos.

---

# M1 — Pure Value + Policy Kernel

## Valor vertical

Poder construir y evaluar policies en memoria sobre `ValueTree`, sin parsers, Kotlin magic ni PipelineK.

## Implementar

- `ValueNode`;
- `DocumentPath`;
- `Selector` mínimo;
- expression ADTs;
- `PolicySet/Policy/Rule`;
- `RuleEvaluation`;
- `PolicyViolation`;
- pure evaluator;
- deterministic report;
- missing/null/type mismatch semantics;
- canonical value hashing.

## UAT

1. map in-memory convertido manualmente a ValueTree;
2. policy mínima `replicas >= 3`;
3. missing -> violation explícita;
4. null != missing;
5. `"3"` no se coacciona a 3;
6. same input/evaluator repeated 100x -> same report digest;
7. deliberately malformed IR/value type -> typed refusal.

## Mutation gate

- convertir missing a false debe tumbar tests;
- coercer Text→Number debe tumbar tests;
- cambiar GTE→GT debe tumbar tests.

## No hacer

- parser YAML/JSON;
- compiler plugin;
- bundles;
- PipelineK.

---

# M2 — Decoder SPI + source-aware resources

## Valor vertical

La misma policy del M1 funciona sobre JSON, YAML y CSV reales y reporta ubicación exacta.

## Bloque M2.A — Decoder contract

- `ResourceDecoder`;
- `ResourceDocument`;
- `NodeId`;
- `SourceMap`;
- `SourceAnchor`;
- decode refusals.

## Bloque M2.B — JSON + YAML parity

- JSON decoder;
- YAML decoder;
- duplicate-key fail-closed;
- multi-doc YAML;
- semantic equivalence UAT.

## Bloque M2.C — CSV

- `WHOLE_DOCUMENT`;
- `EACH_ROW`;
- `TEXT_ONLY` default;
- typed inference opt-in;
- cell anchors.

## Exit

Una policy única sobre deployment YAML/JSON obtiene mismo verdict/actual/expected; sólo cambia source anchor.

---

# M3 — Canonical Kotlin DSL sin magia FIR

## Valor vertical

Escribir policies Kotlin reales que compilan a AST/IR mediante una API segura y completamente testeable.

## Implementar

- DSL builders;
- context parameters;
- explicit symbolic API;
- numeric/text/boolean operators;
- collections `all/any/none/count`;
- `appliesWhen/require/forbid`;
- violation metadata;
- params;
- pure macro prototype.

## Target explícito de referencia

```kotlin
root.field("spec")
    .optionalField("replicas")
    .asNumber()
    .gte(number(3))
```

## Gate

- no lambda ejecutable almacenada en model/IR;
- DSL compile produces canonical AST;
- test snippets positivos/negativos;
- context capability ambiguity test.

---

# M4 — FIR Kotlin authoring: `root.foo?.bar`

## Valor vertical

Convertir el DSL en una experiencia Kotlin natural con autocomplete/diagnostics y type refinement.

## M4.A — Spike property synthesis

Probar:

```kotlin
root.anything?.whatever?.text()
```

sin schema.

### Gate PASS

- compila;
- IDE no muestra falso error en baseline soportado;
- incremental compile;
- `.kt` y script;
- canonical IR parity.

### Gate FAIL

Adoptar fallback `root.anything.whatever.text()` con missing en AST. No bloquear proyecto.

**Resultado M4.A (2026-10-08): FAIL documentado · Opción C adoptada · spike branch `m4-fir-spike` conservada.** Ver [`docs/history/M4_SPIKE_RECEIPT.md`](docs/history/M4_SPIKE_RECEIPT.md) y [`docs/history/M4_CLOSURE_ADDENDUM.md`](docs/history/M4_CLOSURE_ADDENDUM.md).

## M4.B — Purity checker

Rechazar I/O/clock/random/reflection.

## M4.C — Gradual shapes

OPEN → OBSERVED → CLOSED.

Tests:

- open unknown field compila;
- closed typo no compila;
- numeric/text conflict no compila;
- schema contradiction no compila.

## M4.D — compiler compatibility lane

Matriz de Kotlin exacta + IDE baseline. Release no se publica sin suite compiler específica.

---

# M5 — PolicyIR + reproducible PolicyBundle

## Valor vertical

Separar por completo authoring de runtime y producir un artefacto auditable/portable.

## Entregables

- IR version 1;
- canonical JSON codec;
- semantic digest;
- artifact digest;
- policy source map;
- bundle manifest;
- function registry manifest;
- shape constraints;
- deterministic pack;
- bundle verify.

## UAT

1. compile same policy twice -> same semantic digest;
2. explicit DSL y FIR sugar -> same IR digest;
3. bundle evaluable en proceso que no tiene clases source de policy;
4. corrupt byte -> admission refuse;
5. unknown opcode -> refuse;
6. unsupported function -> refuse.

---

# M6 — PipelineK external plugin

## Entrada

PipelineK S6 Plugin SDK certificado e integrado, no sólo branch experimental.

## Valor vertical

Ejecutar una policy real dentro de un pipeline mediante plugin externo, sin modificar core.

## Entregables

- plugin manifest;
- `policy.check` StepDefinition;
- codec/request/result;
- DSL façade;
- event definitions;
- ServiceLoader/contributor wiring;
- external build against published SDK;
- installed distribution fixture.

## UAT

```text
read resource -> policy.check -> report -> enforced outcome
```

más events y plugin identity.

## Hard gate

Search/fitness confirma 0 referencias `policy.check` en semantic coordinator/core dispatch.

---

# M7 — Layers, waivers, shadow y semantic diff

## Valor vertical

Permitir rollout empresarial seguro y actualización de policies sin romper equipos a ciegas.

## M7.A layers

Platform/org/project/pipeline composition.

## M7.B waivers

Scoped + reason + issuer + expiry.

## M7.C shadow

Policy calcula would-block pero PipelineOutcome no cambia.

## M7.D diff

Bundle A/B sobre mismo corpus.

## UAT principal

Una nueva mandatory rule en shadow detecta 20 would-deny sobre corpus histórico, después se activa; un waiver válido salva exactamente un subject y no otro.

---

# M8 — Datasets, streaming e índices

## Entrada

Casos reales medidos que necesiten más que local-resource evaluation.

## Valor

Escalar CSV/JSONL y policies correlacionales sin materializar todo ni caer en O(N²) accidental.

## Entregables

- named datasets;
- `LOCAL/AGGREGATE/GLOBAL` shape;
- streaming row/record evaluation;
- bounded accumulators;
- index requirements derivadas del IR;
- explicit index plan;
- resource budget metrics.

## STOP

No introducir query optimizer genérico estilo database antes de que benchmarks/cases lo exijan.

---

# M9 — CLI y Agent DX

## Valor

Herramienta usable directamente por humanos y agentes sin MCP.

## Entregables

- `compile/check/test/explain/inspect/diff/shape/bundle verify`;
- text/json/jsonl;
- `--json-help` autodescubrible;
- stable exit codes;
- remediation-rich violations;
- bounded output/tails where relevant.

## Agent UAT

Dado un finding JSONL, un agente puede identificar fichero/celda/path y propuesta de corrección sin parsear consola humana.

---

# M10 — Production certification

## Valor

Cerrar no sólo features sino propiedades operativas.

## Gates

- full `./gradlew check` exact SHA;
- compiler matrix;
- multi-format parity;
- bundle reproducibility;
- mutation score targeted (no vanity global target);
- fuzz/property tests decoders/evaluator;
- 1 GiB CSV characterization;
- adversarial regex/large policy limits;
- plugin external same-SHA install;
- restart/replay if PipelineK reuse path applies;
- security purity tests;
- dependency/architecture fitness;
- docs/examples fresh.

## Release candidate condition

No known semantic ambiguity puede quedar clasificada como pendiente informal; todo item abierto debe quedar explícitamente DEFERRED con rationale no bloqueante o BLOCK release.
