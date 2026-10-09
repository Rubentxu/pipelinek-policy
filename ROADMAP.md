# ROADMAP — pipelinek-policy

**Autoridad:** este fichero es la única fuente de secuenciación del proyecto.

## Estado inicial

```text
M0  Grounding / bootstrap                    DONE · fixture-import DEFERRED (license/PII)
M1  Pure Value + Policy Kernel               DONE · managed-closure (vault 7e822b78)
M2  Decoder SPI + source-aware resources     DONE · managed-closure (vault 744b1242)
M3  Canonical Kotlin DSL                     DONE · managed-closure (vault a6300bf3)
M4  FIR Kotlin authoring                     FAIL documentado · Opción C adoptada · spike branch m4-fir-spike conservada
M5  Policy IR + reproducible bundle          DONE · managed-closure (vault 3c8b593c)
M6  PipelineK external plugin                DONE · SDK 0.47.0 mavenLocal desde s6-plugin-sdk (merge S6→main externo abierto) · UAT installDist s6 HEAD
M7  Layers / waivers / shadow / diff         DONE · managed-closure (vault 6ada0fb9) · commit b9b8f43 feat/m7
M8  Datasets / streaming / indexing          BLOCKED-BY entrada (casos reales medidos)
M9  Agent/CLI production surface             DONE · managed-closure (vault 0bdb853e) · commit e7020f2 feat/m9
M10 Production certification                 DONE · managed-closure (vault 3e3c3589) · commit 09a1266 feat/m10 · matrix LTS 273/273 · RC ledger
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

---

# CONTINUACIÓN CORRECTIVA — BLOQUES B0–B6 (2026-10-09)

**Origen:** auditoría sobre HEAD `90c3d0e` (obsoleto; baseline real: ver
`docs/history/B0_BASELINE.md`, HEAD `a90e726`). Esta sección es la única
autoridad de secuenciación a partir de ahora y sustituye cualquier lectura
de cierre M0–M10 como "production ready".

**Estado global:** M0–M7, M9 DONE (con deuda). M4 FAIL documentado (Opción C,
línea DX separada). M8 en build (WU-1..6 verdes, ciclo SIN cerrar: su
remediación vive ahora en B5). M10 = certificación histórica; su estado DONE
se re-evalúa en B6 sobre el SHA exacto de release.

**Certificación de producción: BLOQUEADA** mientras existan defectos P0/P1
de B0/B1/B2/B4 sin resolver.

## B0 — Admisión segura y baseline (P0) — DONE (2026-10-09)
- B0.4 Baseline de verdad → `docs/history/B0_BASELINE.md` (DONE)
- B0.1 Error→PASSED fail-closed en plugin y CLI (DONE; fortress y mutación en `docs/history/B0_RECEIPT.md`)
- B0.2 Resultados tipados de evaluación/refusal en plugin y CLI (DONE); el wrapper genérico de error adapter/engine queda como D-B0-1 P2 en B4.3, sin rebajar un defecto P0/P1
- B0.3 Regression fortress (8 escenarios del plugin + falsificación CLI de Error/Violation/refusal) DONE
- Gate B0: PASS; gate global fresco en `docs/history/B0_RECEIPT.md` (337 tests, 0 failures/errors/skips; architecture fitness y detekt verdes)

## B1 — Cierre semántico kernel/DSL (P0/P1) — DONE (2026-10-09)
RuleKey contextual; numérico exacto (sin Double); forbid/negación declarativa;
COUNT tipado (fuera CountAsLongSignal); Missing/Null/opcionalidad DSL→IR→evaluador;
sustitución de params en appliesWhen; determinismo con digest.

Evidencia trazable por requisito y gates: `docs/history/B1_RECEIPT.md`.

## B2 — PolicyIR canónico y bundles íntegros (P0/P1) — DONE (2026-10-09)
decode(encode(IR)) ≡ IR; campos semánticos completos; selectores estructurales
(sin toString); DatasetRef simétrico (reader); canonicalización; integridad
PKB1 (semanticDigest vs artifactDigest); admission budgets; compatibilidad
versionada (fixture IR v1 y digest fijo; pack PKB1 verificado por la API pública).

Evidencia trazable por requisito y gates: `docs/history/B2_RECEIPT.md`.

## B3 — Fidelidad de recursos, source maps y CLI (P1) — DONE (2026-10-09)
Identidad estructural de nodos; JSON/YAML/CSV con spans reales; finding
estructurado completo; dedup sin pérdida; CLI para agentes; ADR exit codes;
test/explain sin falsos éxitos.

Evidencia trazable por criterio y gate: `docs/history/B3_RECEIPT.md`.

## B4 — Gobierno de políticas y plugin completo (P0/P1) — PENDIENTE
Layers con autoridad verificada; waivers aislados; enforcement como
responsabilidad diferenciada; shadow fiel; PolicyDiff semántico; policy.check
con plan coherente; ingress acotado; replay/fingerprint completo; UAT real
instalada (20 recursos).

## B5 — Streaming real, datasets y presupuestos (P1) — PARCIAL (M8 WU-1..6)
Pendiente: streaming por chunks (hoy ByteArray), una sola semántica de parsing
CSV, RuleKey en datasets, Sum exacto, presupuesto tipado (no excepción
incidental), medición reproducible (RSS/heap/throughput), límites de
integración documentados. Ya cubierto: planner/shapes, accumulators con cap,
CLI stream, UAT 64 MiB plana, GLOBAL rechazado.

## B6 — Certificación real y release (Release gate) — PENDIENTE
Certificación del SHA exacto (no marker estático); matriz JDK+Kotlin real;
CsvParityTest sobre salida real de decoders; fuzz ampliado; mutation testing
auténtico (mutación compila → test falla → restore); adversarial limits;
plugin instalado con provenance; replay/memoización reevaluado;
reconciliación documental; release admission con checksums.

## Reglas transversales (vinculantes)
Arquitectura emergente (extraer módulos solo con necesidad demostrada);
compatibilidad con ADR en cambios de contrato; evidencia por bloque en
`docs/history/B*.md`; commits atómicos; falsificación por semántica;
PASS/FAIL/PARTIAL/NOT_MEASURED/N-A sin conversions; no rebajar DEFERRED
defectos P0/P1; trabajo excluido (FIR completo, UI, MCP, etc.) permanece
excluido.
