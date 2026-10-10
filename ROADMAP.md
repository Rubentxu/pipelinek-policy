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
- **B0.5 CERRADO (verificado 2026-10-10):** Error + Violated en SHADOW terminaba en
  `StepOutcome.Success` porque `PolicyCheckStepDefinition` comprobaba `violations`
  antes que `errors`, y `PolicyCheckOutput` mapeaba `VIOLATED + SHADOW` a `Success`.
  El comentario del código declaraba la precedencia correcta; la implementación no la
  seguía. **El defecto ya estaba corregido en `fa6c017`**, y la corrección además movió
  la precedencia al core compartido (`EnforcementInterpreter.verdict`) para que el
  adaptador del plugin no pueda discrepar del de la CLI.
  `EnforcementPrecedenceTest` cubre la matriz completa por `evaluate` público
  (8 casos: Error+Passed, Error+Violated, Passed+Violated+Error, SHADOW+Error,
  SHADOW+Violation sin Error, refusal bajo SHADOW, evidencia bajo ERRORED,
  ENFORCED+Violation sin Error).
  **Falsificado**: rankear VIOLATED por encima de ERRORED en el core → **RED 3/14**
  (`passed plus violated plus error`, `violated plus error`,
  `an ERRORED verdict still carries the violations as evidence`). Estado de B0: **DONE**.

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

- **B2.9 CERRADO (verificado 2026-10-10):** `BundleIrCanonicality.identify` calcula
  la codificación legacy **antes** de probar CURRENT, y `LegacyPolicyIrJsonV1.encode`
  devuelve `null` ante `Expression.CollectionPredicate`. Resultado: un bundle del
  formato vigente con predicado de colección se rechaza en `verifyPacked()`.
  **El defecto ya estaba corregido en `157c830`** y el ROADMAP quedó obsoleto. La
  aceptación del propio roadmap (`pack → verifyPacked → evaluate` por API pública)
  existe y pasa en `CurrentEncodingAdmissionTest`.
  **Falsifiqué el orden dos veces y las dos quedó VERDE**: la mutación
  legacy-first es un equivalente observational. La razón, medida con un probe
  desechable: los dos escritores son **disjuntos** — un literal produce 200/200 bytes
  idénticos, una comparison produce 322/316 distintos — así que a lo sumo una
  comparación puede tener éxito y el orden no puede cambiar el resultado. El test
  que ata la propiedad real es `the two encodings are disjoint`, y muere (RED 1/3)
  al mutar CURRENT para que devuelva LEGACY cuando ambos escriben el documento.
  Estado de B2: **DONE**.

## B3 — Fidelidad de recursos, source maps y CLI (P1) — DONE (2026-10-09)
Identidad estructural de nodos; JSON/YAML/CSV con spans reales; finding
estructurado completo; dedup sin pérdida; CLI para agentes; ADR exit codes;
test/explain sin falsos éxitos.

Evidencia trazable por criterio y gate: `docs/history/B3_RECEIPT.md`.

## B4 — Gobierno de políticas y plugin completo (P0/P1) — EN CURSO
Layers con autoridad verificada; waivers aislados; enforcement como
responsabilidad diferenciada; shadow fiel; PolicyDiff semántico; policy.check
con plan coherente; ingress acotado; replay/fingerprint completo; UAT real
instalada (20 recursos).

Descomposición (cada ítem cierra con su prueba de falsificación):

- B4.1 Layers con autoridad verificada. `Supersession.authority` pasa de `String`
  a `SupersessionAuthority(issuer, grantedLayers, grantDigest)`; `LayerComposer.compose`
  recibe un `AuthorityRegistry` **por parámetro** (ley 10) con default `EMPTY`
  = fail-closed. El bundle **reclama** el grant en `metadata.txt`; el **host**
  lo concede. Un bundle puede pedir, no otorgar. Falsificación: un `authority`
  no vacío sin grant compone hoy (verde) y debe refizarse (rojo).
  ADR-0014.
- B4.2 Waivers aislados. `datasetScope`/`projectScope` hoy están **declarados y
  nunca leídos**; el matching debe exigirlos. El instante se inyecta. Mismo
  `ruleId` en dos policies: solo la cubierta queda `Waived`. Expirado ⇒
  `ActiveWithDiagnostic(WaiverExpired)`, la violación sigue activa.
- B4.3 Enforcement como responsabilidad diferenciada.
  `PolicyEvaluation` / `WaiverApplication` / `EnforcementDecision` / `StepOutcome`
  separadas. El evaluador no decide el resultado operativo. (Cierra también
  `D-B0-1`, P2.)
- B4.4 Shadow y activación. SHADOW produce los mismos findings que ACTIVE con
  `wouldDeny` y localización. `ERRORED` y `REFUSED` **no** se degradan a éxito
  en SHADOW. `PolicyCheckInput.enforcement` ya existe con default `ENFORCED`;
  lo que falta es la exposición desde el DSL.
- B4.5 PolicyDiff semánticamente completo. `SEVERITY_CHANGED` deja de ser un
  `else` ciego: `Rule.severity: RuleSeverity?` explícito, emitida solo cuando
  ambos lados la declaran y difieren. Sin tabla de severidad inventada
  (origen normativo en `KOTLIN_POLICY_DSL.md:20,160` y
  `EVALUATION_SEMANTICS.md:94`). ADR-0015.
- B4.6 Integración en `policy.check`. `PolicyCheckPlan` **calculado** por el
  kernel, nunca serializado en el input (si viajara, el host afirmaría su propia
  validez). Reloj inyectado. Un plan no calculable es `REFUSED`, también en
  SHADOW. ADR-0016.
- B4.7 Resource ingress y controlador ligero. Presupuesto tipado
  `ResourceIngressLimits` (default 64 MiB, techo 256 MiB) con guarda O(1) sobre
  `String.length` **antes** de decodificar, y `Files.size` antes de leer en el
  host. El exceso es `REFUSED`, nunca `throw`. El paquete `ingress/` citado en
  el Propose **no existe**: va en `kernel/governance/`.
- B4.8 Replay, eventos y datos sensibles. Fingerprint con todos los inputs
  semánticos. **Hit/miss de `ReplayPolicy.MEMOIZED`: NOT_MEASURED** (dependencia
  del SDK); B4 demuestra solo la condición necesaria, el hit/miss se mide en B6.
- B4.9 UAT real instalada. 20 ficheros reales por plugin instalado
  (`--plugin-jar`). El harness verifica la precondición **por comportamiento**
  (ruta inválida ⇒ admisión de plugin, RC=2), nunca parseando `--help`; un
  `installDist` normal puede salir 0 sin reconstruir y hay que usar
  `--rerun-tasks`.

Guardarraíles que condicionan B4 (decididos, no opcionales):

- **Guarda de pureza:** `DomainIoPurityTest` selecciona por sufijo contra
  nombres sueltos, así que `kernel/governance` y el preexistente
  `kernel/dataset` quedan **fuera** de la ley 5 verificada. Un
  `import java.nio.file.Files` en `DatasetPlanner.kt` pasa verde hoy. Se corrigen
  ambos en el mismo commit; si no, la ley 5 de B4 sería decorativa.
- **`EnforcementMode` al core como enum PLANO, sin `@Serializable`.** El core
  solo admite `kotlin-stdlib`; meter la anotación rompería el bucket de
  ADR-0011. La anotación es innecesaria: el evento parsea con
  `EnforcementMode.valueOf(parts[5])` y kotlinx serializa enums por `name`.
- **`bundleVersion` NO se bumpea.** Bumpear movería el `artifactDigest` de
  bundles sin `supersession`. El rechazo del formato antiguo ya es explícito vía
  `manifestMatches`, que compara el manifiesto recomputado.
- **Paridad CLI/plugin por construcción:** ambos adaptadores delegan en un
  único núcleo puro; ninguno implementa reglas.

### Reconciliación B4–B6 contra el código (2026-10-10, HEAD `1d933fc`)

Cada ítem se verificó contra el árbol, no contra el estado declarado. Un ítem
solo cuenta Done con implementación **y** falsificación observable.

| Item | Estado real | Evidencia en el árbol |
|---|---|---|
| B4.1 autoridad | **DONE** | `Layers.kt:67` `AuthorityRegistry(grants)`, `:103` `EMPTY`, `:201` fail-closed por parámetro |
| B4.2 waivers | **DONE** | `Waivers.kt:228-229` lee `projectScope`/`datasetScope` vía `scopeSatisfied` |
| B4.3 enforcement | **DONE** | `governance/EnforcementInterpreter.kt` puro y compartido |
| B4.4 shadow | **DONE** | `PolicyCheckDsl.kt:49` `enforcement` con default `ENFORCED` |
| B4.5 diff | **DONE** | `PolicySet.kt:83` `severity: RuleSeverity?`; `PolicyDiff.kt:143` emisión condicional |
| B4.6 plan | **DONE** | `governance/PolicyCheckPlan.kt` calculado por el kernel |
| B4.7 ingress | **DONE** | Kernel: `PolicyCheckPlanRequest.ingressLimits` (default = presupuesto enviado) y `compute` admite **antes** de decodificar, con `RESOURCE_TOO_LARGE`/`BUNDLE_TOO_LARGE` distintos de `NOT_BASE64` (ley 8); el helper privado `decodeBase64` sin cota fue eliminado. Hosts: `BoundedRead` (CLI) acota los 16 `readBytes()` sin cota — stat O(1) primero, luego lectura **capada** a `budget + 1`, de modo que el crecimiento entre stat y read se acota y no solo se detecta; presupuestos derivados de `ResourceIngressLimits`, nunca literales duplicados. El plugin acota su `release.properties` de classpath con un tope propio (64 KiB), explícitamente **no** el presupuesto de ingress. Falsificado: cap → `readBytes()` mata 1/12 (`BoundedReadTest`), y `CheckCmd` → `readBytes()` mata 1/4 (`OversizedInputRefusalTest`). Gate: 495 tests, 0/0/0 |
| B4.8 replay | **DONE** | `Waivers.kt:70` instante inyectado, scopes aplicados |
| B4.9 UAT | **DONE** | `uat-external-distribution.sh` recorre 20 ficheros reales (16 conformes + 4 incumplidores) contra la distribución **instalada** vía `--plugin-jar`, y exige concordancia en los **dos** sentidos: `PASS ok (exit 0, 16 successful checks, 0 error findings)` y `VIOLATE ok (exit 1, VIOLATED, PLUGIN failure, fail-fast after 1 step)`. Precondición verificada **por comportamiento** (comando inválido ⇒ RC=1), nunca parseando `--help`, e `installDist` con `--rerun-tasks`. **Defecto corregido**: el harness grepeaba `PASSED`, un token que el plugin nunca imprime — `"policy check PASSED"` es el mensaje de `StepOutcome.Failure` y solo aparece cuando el veredicto deniega, así que la mitad conforme solo podía fallar (falso negativo fijo). Las aserciones son ahora conductuales y no vacuñas: recuento exacto de steps ejecutados (16 en PASS, 1 antes del fail-fast en VIOLATE), ausencia de findings `[VIOLATION\|ERROR\|REFUSAL]` en el corpus conforme, y `Finished: failure` como prueba de que la denegación fue un fallo del motor y no un crash. **Falsificado**: corpus incumplidor mutado a conforme ⇒ `VIOLATE FAILED rc=0`, el mutante sale 1 donde el original sale 0 |
| B5.3 UTF-8 | **DONE** | `7fff04a`: los 5 bucles byte→char de `CsvRowSource`, `CsvResourceDecoder` y `JsonlSource` acumulan bytes y decodifican UTF-8 una vez. Falsificado revirtiendo el fix (RED 9/9 CSV, 6/7 JSONL; el único verde fue el control ASCII) |
| B5.5 identidad | **DONE** | `StreamingReport.results` pasa a `Map<RuleKey, RuleOutcome>` y `rulesByKey` preserva el `policyId` al aplanar. Dos policies con el mismo `ruleId` ya no comparten tally, acumulador ni entrada de reporte. `DatasetPlan.localRuleIds` sigue como `List<String>`: follow-up, no degradado |
| B5.6 acumuladores | **DONE** | `Accumulator.Outcome` tipado (`Accepted` / `Refused(cap, consumed, limit)` con vocabulario cerrado COUNT/SUM/ROW_BUDGET/TYPE_MISMATCH); `Sum` acumula en `BigDecimal` en vez de `Double`, y el `exact: Long` que se calculaba y se descartaba desapareció. Falsificado con dos mutaciones: reintroducir `Double` mata 3/3 exactitud, reintroducir la excepción mata 4/4 budget |
| B6.1 certificacion | **DONE** | `scripts/m6/certify.sh` emite `docs/history/B6_CERTIFICATION.md` desde la ejecucion real: borra los XML, corre `test --rerun-tasks`, y registra SHA del arbol de fuentes, HEAD, modulos leidos de `settings.gradle.kts`, **tests realmente ejecutados** contados del XML recien escrito (no un recuento de ficheros), toolchain, coordenadas resueltas y digests de artefactos. Sustituye al anclaje decorativo: `certified-sha` se contrasta contra el arbol real (`CertificationAnchorTest.01b`) y `baseline-tests` pasa de 273 (predataba casi toda la suite) a **498**, con `certify.sh` negandose a certificar si ejecutan menos. **Falsificado por ambos lados**: (a) puenteando el gate, emitia recibo certificando 281 tests con exit 0; (b) puenteando el gate **e** inflando el baseline a 9999, la segunda puerta lo mata (`GATE FAILED: 281 executed but certifies 9999`). **Defecto encontrado al verificar**: el recibo decia `JVM 25` porque leia `java -version` del shell, pero el build forkea **JDK 21** (`build.gradle.kts` fija `testJvm`); describia una JVM que no habia compilado ni ejecutado un test. `certify.sh` resuelve ahora el toolchain del build, lo verifica contra el binario de esa instalacion y falla si no la encuentra en vez de degradar al del shell. Gate: 498 tests, 0/0/0 |
| B6.2 matriz JDK | **DONE** | `scripts/m10/compiler-matrix.sh` reescrito. El veredicto anterior era `PASS (same suite => same count)`: dos JVM con el mismo **numero** de tests se declaraban equivalentes, y el nombre del runtime era un literal que ningun comando verificaba. Insatisfactorio por construccion: una suite puede renombrarse o perderse de forma asimetrica y la aritmetica sigue cuadrando. Cada fila exige ahora toolchain verificado, gate verde, **identidad del conjunto de tests ejecutados** (`classname#method`) y cero skips; un JDK ausente **falla** la matriz en vez de descartarse. Resultado: JDK 21 y 25, 498 tests en ambas, 0/0/0, **PASS**. **Falsificado**: suite distinta inyectada en la fila 25 con el recuento intacto 498/498 => exit 1 vs 0 del original. Informe: `docs/history/B6_COMPILER_MATRIX.md` |
| B6.3 paridad multiformato | **DONE** | El defecto era estructural: la pata CSV evaluaba un `ValueNode` **escrito a mano** (`expectedTree(...)`), de modo que el digest etiquetado "CSV" nunca venia del decoder CSV. Ahora la salida real del decoder se reproyecta por el `MapAdapterDecoder` real y de ahi al evaluador; no queda arbol escrito a mano en el camino CSV, y el contrato TEXT (ADR-0011 D-05) se declara en vez de sortearse. **Falsificado** conmutando el binding de columna (`entries[col]` => `entries[header.first()]`): muere **3/4**, y sobrevive solo 03a, que es JSON/YAML y no toca CSV. **Medicion A/B honesta**: el test viejo tambien fallaba 1/2 contra el mismo defecto, pero por una asercion de forma mientras su `03b` semantico seguia **verde**; la diferencia es *que propiedad* queda probada, no verde vs rojo |
| B6.4–B6.10 | **ABIERTO** | property-based/fuzzing, mutation testing autentico, certificacion adversaria, procedencia del plugin instalado, replay/memoizacion, reconciliacion documental y admision de release |

**Guardarraíl de pureza (B4): CERRADO.** `DomainIoPurityTest` ya usa
`walkTopDown()` sobre las raíces de dominio y afirma cobertura explícita
incluyendo `kernel/dataset/Accumulator.kt` y `kernel/dataset/DatasetPlanner.kt`.
La ley 5 de B4 dejó de ser decorativa.

**Corrección de una afirmación previa:** el resumen de sesión daba B4-T3
(`PolicySet.layer` opcional) y B4-T5 (`resourceBudget`) como abiertos. B4.7 sí
estaba abierto, como ausencia del tipo `ResourceIngressLimits` y como lecturas
de host sin cota. Cerrado en dos commits: el cableado del kernel
(`bf2639e`) y el acotado de los hosts CLI y plugin (este bloque).

### Prioridad derivada de esta reconciliación

1. ~~**B4.7 `ResourceIngressLimits`**~~ — **cerrado**: tipo en el kernel,
   admisión antes de decodificar, y todos los hosts leen con cota.
2. ~~**B5.3 UTF-8 estricto**~~ — **cerrado** en `7fff04a`.
3. ~~**B5.5 `RuleKey`**~~ — **cerrado** en `2980c48`.
4. **B4.9 + B6.1** — UAT real y certificación del SHA exacto. *Siguiente.*

## H0 — Plan de entrega `v0.3.0` (continuación correctiva de B0–B6)

**Base auditada:** `main` @ `6df518d` (2026-10-10), `HEAD == origin/main`, árbol
limpio salvo trabajo en curso de B6.5.

**Regla de integración:** los cinco hitos siguientes se planifican **aquí**, no
en un segundo roadmap. Cada línea se clasifica contra el código antes de
aceptarla, y `DONE` documental no acredita cumplimiento.

### Estado medido por línea (auditado contra el árbol, no contra el plan)

| Línea | Estado medido | Evidencia en el árbol |
|---|---|---|
| H1.1 `severity` en PolicyIR | **CERRADO (H1.1)** | El defecto era en el codec, no en `PolicyIrDocument`: el `Rule.severity` ya viajaba dentro del `PolicySet`, pero `CanonicalPolicyJsonWriter.policies()` emitía `code/expected/actual/params/supersession` y no `severity`, y el decoder no lo leía. Dos reglas que solo difieren en severidad codificaban a bytes idénticos. Fix en commit `e44a1ae`: el writer emite `severity` solo cuando está declarada (nunca por defecto, porque la ausencia es dato significante para `PolicyDiff`), y el decoder la lee como vocabulario cerrado rechazando nombres desconocidos con `IrRefusal.CorruptEncoding` en lugar de degradar a `null`. **RED observado antes del fix: 4 de 5 tests nuevos fallaban** (INFO volvía `null`, CRITICAL e INFO bytes idénticos, sin clave `severity` en el documento). GREEN con `--rerun-tasks`. Mutantes D1/D2 añadidos al gate: **9/9 muertos**. Ancla recalibrada a `44a5d89c...`, `baseline-tests` 508 → 513. |
| H1.2 gobierno extremo a extremo | **PARCIAL** | `governance/` existe (`EnforcementInterpreter`, `PolicyCheckPlan`, `Layers`, `Waivers`). Falta verificar el servicio de aplicación puro compartido por CLI y plugin con `AuthorityRegistry` del host como entrada explícita. |
| H1.3 semantic diff `corpus.first()` | **CERRADO (B4.5)** | `PolicyDiff.kt:163-169` emite `SEVERITY_CHANGED` condicionalmente; `:142` documenta que la emisión previa era *"permanently dead"*. No hay `corpus.first()` en la ruta de diff. |
| H2.1 `RuleKey` en dataset planning | **DEFECTO VIVO** | `DatasetPlanner.kt:35` `val localRuleIds: List<String>`; consumido en `StreamingEvaluator.kt:73` y `:131` por `it.id in plan.localRuleIds`. Una regla LOCAL y otra AGGREGATE con el mismo texto colisionan. |
| H2.2 resultado de planificación sellado | **ABIERTO** | Requiere inspección de `DatasetShapeAnalyzer`/`IndexPlan` en su bloque. |
| H2.3 agregados | **PARCIAL** | `Accumulator.Sum` ya acumula en `BigDecimal` (`Accumulator.kt:90,111`) y `Outcome` es tipado con vocabulario cerrado COUNT/SUM/ROW_BUDGET/TYPE_MISMATCH (B5.6). Pendiente: COUNT debe contar **coincidencias**, no filas. |
| H2.4 GLOBAL | **ABIERTO** | Rechazo tipado o patrón de índice limitado; sin optimizador genérico. |
| H3.1 lectura incremental | **PARCIAL** | `cli/BoundedRead.kt` lee por chunks con tope, pero devuelve `ReadResult.Ok(bytes: ByteArray)` (`:55`) y `readCapped` materializa en `ByteArrayOutputStream` (`:117-130`). Falta el cursor de filas incremental sobre `InputStream`. |
| H3.2 presupuestos | **CERRADO (B4.7)** | `ResourceIngressLimits` en el kernel, admisión antes de decodificar, `BUNDLE_TOO_LARGE`/`RESOURCE_TOO_LARGE` distintos de `NOT_BASE64` (ley 8). |
| H4.1 CLI agent-first | **PARCIAL** | `--json-help` existe y se despacha desde `PolicyCli.kt:18,37` con `CommandRegistry` como única fuente. Falta verificar el escape seguro en `check/test/diff/explain/inspect/stream`. |
| H4.2 plugin externo | **PARCIAL** | `uat-external-distribution.sh` ejecuta el Step instalado vía `--plugin-jar` (B4.9). Falta procedencia del SDK publicado (B6.7). |
| H4.3 replay y memoización | **ABIERTO** | B6.8. Sin.Files de fingerprint efectivo ni hit/miss observados. |
| H4.4 compatibilidad cruzada | **ABIERTO** | No existe matriz Policy × SDK × JDK. Versión actual declarada `0.2.0-M2` (`build.gradle.kts:16`), coherente con el destino `v0.3.0`. |
| H5.1 certificación de fuentes | **PARCIAL** | `certified-sha` cubre `src/{main,test}/kotlin` del **módulo raíz** (81 ficheros). Un cambio en producción de un submódulo deja válida una certificación previa. |
| H5.2 mutation testing | **CERRADO (B6.5)** | `scripts/m6/mutation-gate.sh` con 7 mutantes semánticos (A1–A3 `appliesWhen`/veredicto, B1 precisión `BigDecimal`, B2 claves duplicadas, C1 orden de mappings, C2 orden de sequences). Último run: **7 muertos, 0 sobrevividos, 0 inválidos**, restore verde. Falsificado en negativo dos veces (test mal dirigido → `SURVIVED` + exit 1; mutante que no compila → `INVALID` + exit 1). Dos tests dirigidos añadidos durante el cierre: rechazo de `TypedRefusal` en `appliesWhen`, y orden canónico de sequences con aserción asimétrica. |
| H5.3–H5.5 | **ABIERTO** | B6.6, B6.9, B6.10. |

**Medición transversal:** `check` incluye `detekt` (verificado con
`./gradlew check --dry-run`). Todos los documentos referenciados por el plan
existen (`docs/03-specifications/POLICY_IR.md`, ADR-0015,
`docs/07-performance/PERFORMANCE_AND_SCALE.md`, `docs/history/M6_UAT_EVIDENCE.md`,
`docs/history/M10_CSV_1GIB_RECEIPT.md`, `docs/06-testing/*`). `gradle-jdk21.sh`
existe. **Cero tags** en el repositorio: ningún release ha sido publicado.

### Orden de ejecución derivado de la medición

El plan declara H1→H5, pero el estado medido obliga a un orden distinto en dos
puntos, porque H5 sin H1 produce una certificación de un producto semánticamente
incorrecto:

1. **B6.5 cerrado.** El gate de mutation testing está verde y falsificado en
   ambos sentidos. Era condición de todo lo demás: es la herramienta que
   certifica, y certificar sin ella es afirmar sin medir.
2. **H1.1 cerrado.** Era un P0 semántico: una decisión
   `CRITICAL` que sobrevive en memoria y se pierde al serializar produce un
   reporte que miente sobre su propia severidad. Corregido en `e44a1ae`.
3. **H2.1 antes que H3.** `localRuleIds` colisiona por identidad, y H3
   caracteriza el streaming sobre los mismos `StreamingEvaluator`; corregir la
   identidad primero evita certificar dos veces el mismo defecto.

## B5 — Streaming real, datasets y presupuestos (P1) — PARCIAL (M8 WU-1..6)

Descomposición:

- B5.1 Reclasificar M8: de BLOCKED-BY por ausencia de mediciones a trabajo
  ejecutable, conservando los límites de las mediciones históricas.
- B5.2 Streaming de verdad. `CsvRowSource`/`JsonlSource` reciben `ByteArray`;
  evolucionar a fuente incremental por chunks en la **capa adaptadora**. El
  kernel no hace I/O (ley 5). Un parser streaming no almacena todas las filas.
- B5.3 Una sola semántica de parsing. Compartir tokenizer y validación entre
  CSV materializado e incremental, sin acoplar el dominio al parser.
- B5.4 Dataset planning completo. `DatasetShapeAnalyzer`, `DatasetPlanner`,
  `DatasetPlan`, `IndexPlan`, `StreamingEvaluator`. LOCAL / AGGREGATE / GLOBAL
  explícitos. **No degradar GLOBAL a LOCAL para que un test pase.**
- B5.5 Identidad de reglas en datasets: la `RuleKey(policySetId, policyId, ruleId)`
  de B1, no solo `rule.id`.
- B5.6 Acumuladores. `Accumulator.Sum` con numérico exacto. Límite excedido ⇒
  resultado tipado de presupuesto, **no** excepción incidental ni truncamiento.
- B5.7 CLI `stream`: CSV, JSONL, bundle verificado, resultados JSONL, métricas
  estructuradas, exit codes coherentes, `--json-help`.
- B5.8 Medición real y repetible: 64 MiB con heap limitado, 1 GiB bajo
  caracterización independiente. RSS, heap, throughput, filas, tamaño máximo
  retenido. **Sin umbral arbitrario de tiempo como gate semántico.**
- B5.9 Límite de integración PipelineK. Si el SDK no da ejecución externa,
  entregar streaming por CLI primero y **registrar la dependencia concreta**.
  No bloquear el kernel por una limitación de integración externa.

Nota de solape con B4: B4 deja el patrón de presupuesto tipado
(`ResourceIngressLimits`); B5.6 debe migrar `BudgetExceededException` a ese
patrón en lugar de crear un segundo.

## B6 — Certificación real y release (Release gate) — PENDIENTE

Descomposición:

- B6.1 Certificación del SHA exacto. Sustituir el anclaje por archivo estático y
  el recuento de `*Test.kt`. Certificar SHA, árbol de fuentes, módulos y tests
  realmente ejecutados, toolchain, dependencias resueltas y artefactos. Los
  informes deben ser **nuevos**, no reutilizar XML antiguo.
- B6.2 Matriz real JDK 21/25, distinguiendo versión del compilador Kotlin y ABI
  del Plugin SDK. Dos JVM con el mismo número de tests no certifican dos
  compiladores.
- B6.3 Paridad multiformato. Reparar `CsvParityTest`: debe consumir la **salida
  real** de cada decodificador, no un árbol construido a mano.
- B6.4 Property-based y fuzzing sobre JSON/YAML/CSV válidos y malformados,
  Unicode, claves duplicadas, números extremos, round-trip IR, bundle corrupto,
  límites de profundidad. Registrar seeds.
- B6.5 Mutation testing auténtico: mutación válida → **compila** → test
  dirigido **falla por la aserción esperada** → restore → test verde. Un fallo
  de compilación **no** cuenta como detección semántica.
- B6.6 Certificación adversaria: límites de bundle, reglas, profundidad,
  selectores, entrada y agregación; ausencia de I/O oculto y de ejecución
  arbitraria. Distinguir ejecutar con éxito un caso grande de **rechazar
  correctamente** los que exceden el límite.
- B6.7 Plugin instalado con procedencia. Nada de instalaciones antiguas ni
  mavenLocal sin identificar. Registrar SHA/digest de las dependencias de
  integración. Recorrer el Step instalado, no solo `evaluate()`.
- B6.8 Replay y memoización: reevaluar la declaración N/A de M10 cuando el SDK
  exponga el recorrido; sin extrapolar la pureza del evaluador a toda la
  integración.
- B6.9 Reconciliación documental: ROADMAP, README, ejemplos, contratos CLI,
  matriz de compatibilidad, estado de M4 y M8, ledger de deuda, notas de
  release. **La ausencia de TODO/FIXME no prueba ausencia de deuda.**
- B6.10 Release admission: versión coherente, SHA de fuentes, checksums,
  provenance, resultados de gates, compatibilidad declarada, deuda aceptada,
  limitaciones conocidas e instrucciones de reproducción. No promover release
  production-ready con defectos críticos abiertos.

## Auditoría externa 2026-10-10 — hallazgos verificados en código

Triaje de la auditoría publicada sobre HEAD `25d440f`. **Cada punto fue reproducido
en código antes de aceptarlo.** Un hallazgo externo no es autoridad: si no se
reproduce, no se corrige.

### P0-04 · Bundle vigente con `CollectionPredicate` rechazado — CONFIRMADO

`LegacyPolicyIrJsonV1.encode` devuelve `null` si el documento contiene un
`Expression.CollectionPredicate` (`LegacyPolicyIrJsonV1.kt:12`), y `identify`
hace `val legacyBytes = ... ?: return null` **antes** de probar el formato
CURRENT (`BundleIrCanonicality.kt:12`). Consecuencia: un bundle correctamente
serializado en el formato vigente es rechazado por `verifyPacked()` porque no se
pudo construir una codificación histórica.

Es un **defecto de orden**: admitir el formato actual no puede depender de que el
antiguo sea reproducible. Corrección: comparar CURRENT primero y usar el legacy
solo como alternativa. Test: `pack → verifyPacked → evaluate` con un predicado de
colección por la API pública, más el golden legacy intacto.

**No reabre B2 entero.** El codec canónico, el round-trip y los presupuestos están
correctos; el fallo es de orden en un solo punto de admisión. Se registra como
**B2.9**, corrección puntual y atómica.

### P0-01 · Error + Violated en SHADOW termina en Success — CONFIRMADO

`PolicyCheckStepDefinition` evalúa `violations > 0` **antes** que `errors > 0`
(`:161-167`), y `PolicyCheckOutput` mapea `VIOLATED + SHADOW` a
`StepOutcome.Success` (`:55`). Una regla con `Error` y otra con `Violated` en el
mismo informe producen `VIOLATED`, luego `Success`, pese a no haberse evaluado todo
correctamente.

El comentario del propio código declara la precedencia correcta
(`REFUSED > ERRORED > VIOLATED > PASSED`); la **implementación no la sigue**. Es una
divergencia entre intención documentada y comportamiento.

**Reabre B0** de forma puntual como **B0.5**: el error debe ser observable como
fallo operativo también en SHADOW, y el caso mixto ENFORCED/SHADOW entra en la
fortress como test negativo obligatorio.

### P1-03 · Identidad del finding en CLI — CONFIRMADO

`RuleKey.of(set.id, policyId, rule.id)` es una clave compuesta, y `CheckCmd`
publica `ruleId = ruleId.value` junto con `policyId = runtime.report.policySetId`,
es decir la clave entera como si fuera el id simple y el id del policy set como si
fuera el de la policy propietaria. El digest interno conserva la identidad; la
superficie JSONL dirigida a agentes no. Registrado como **B3.6**, porque el
contrato de finding es de B3.

### CERT-01 · El anclaje M10 no certifica el SHA ejecutado — CONFIRMADO

`cert/SHA.txt` declara `certified-sha: 5a9162b…` mientras el HEAD es `25d440fa`.
`CertificationAnchorTest.01a` solo comprueba que el marcador no esté vacío y
contenga una línea `baseline-suites:`; **nunca compara el SHA con el HEAD**. `01b`
cuenta ficheros `*Test.kt` con `Files.walk`, o sea cuenta ficheros en disco, no
tests ejecutados. El anchor pasa verde certificando código que ya no existe. Es
justo el caso que la propia regla transversal de este roadmap prohíbe. Registrado
como **B6.1**, ya contemplado.

### Hallazgos confirmados que el roadmap ya cubría

- **P0-02** (supersesión sin autoridad) = **B4.1**, ya implementado en curso. Sin
  novelty: la auditoría llega tarde a un hallazgo ya identificado.
- **P1-09** (waivers sin scopes) = **B4.2**. Verificado además: `datasetScope` y
  `projectScope` aparecen **solo** en su propia declaración.
- **P1-10** (`PolicyDiff` con `else` residual) = **B4.5**.
- **P1-11** (ingress sin límites) = **B4.7**.
- **P2 arquitectura** (`DomainIoPurityTest` por sufijo) = guardarraíl ya escrito en
  la sección B4.

### Deuda M8 pendiente, sin novelty

P1-05 (identidad simple en datasets), P1-06 (agregados sin aserción completa),
P1-07 (plan `Any` e índices declarados no construidos), P1-08 (`readBytes()` y
`toInt().toChar()` que corrompe UTF-8 multibyte) = **B5.2–B5.6**. El defecto
Unicode de P1-08 es el más grave del grupo y se eleva a condición de cierre de B5.3.

### Clasificación final de la auditoría

Reabre **B0** (B0.5) y **B2** (B2.9). No añade deuda nueva a B4, B5 ni B6:
confirma lo que ya estaba planificado. **La certificación de producción sigue
BLOQUEADA** y ahora por un motivo adicional y demonstrated: un bundle del
formato vigente con predicado de colección no se admite.

## Reglas transversales (vinculantes)
Arquitectura emergente (extraer módulos solo con necesidad demostrada);
compatibilidad con ADR en cambios de contrato; evidencia por bloque en
`docs/history/B*.md`; commits atómicos; falsificación por semántica;
PASS/FAIL/PARTIAL/NOT_MEASURED/N-A sin conversions; no rebajar DEFERRED
defectos P0/P1; trabajo excluido (FIR completo, UI, MCP, etc.) permanece
excluido.

### Autoridad única por concepto (ley 12)

Ningún adaptador establece una segunda semántica:

| Concepto | Autoridad |
|---|---|
| Datos de recurso | `ValueTree` |
| Semántica de policy | `PolicyIR` |
| Evaluación | Pure Evaluator |
| Posición en origen | `SourceMap` |
| Composición | `LayerComposer` |
| Exenciones | `WaiverMatcher` |
| Decisión operativa | Enforcement Interpreter |
| Estado de PipelineK | PipelineK `StepOutcome` |
| Evidencia de certificación | Receipts del HEAD ejecutado |

### Cómo se cierra un bloque

1. Cada criterio del bloque tiene un test que **falla antes** del cambio y
   **pasa después**. Verde-después sin rojo-antes no es evidencia.
2. Una mutación que **no compila** no cuenta como detección semántica.
3. Los informes de gate son de la **ejecución actual**. Reutilizar XML o
   receipts de una ejecución anterior es falsificar evidencia.
4. Un `exit code` capturado a través de un pipe sin `pipefail` no es evidencia.
5. Una precondición se verifica **por comportamiento**, no por la existencia de
   un fichero ni por parsear la ayuda de un CLI.
6. Cada commit se publica al remoto y se verifica que la ref remota coincide
   con HEAD local antes de empezar la siguiente unidad.

### Gate global de release

**La certificación de producción sigue BLOQUEADA** mientras exista cualquier
defecto P0/P1 semántico o de seguridad abierto de B0, B1, B2 o B4.

`B6.10` (release admission) es PASS únicamente con: suite completa verde sobre
el SHA candidato exacto, sin P0/P1 abiertos, UAT ejecutadas, AAT verificadas,
matriz reproducible, mutaciones críticas detectadas, bundle y plugin
certificados externamente, límites y seguridad demostrados, dependencias de
integración identificadas, documentación coherente y ledger de deuda completo.

**Reconciliación pendiente en B6.9:** M4 sigue FAIL documentado (Opción C, línea
DX separada) y M8 sigue sin ciclo cerrado hasta que B5 lo cierre. Ninguno de
los dos puede declararse DONE por el mero hecho de que el roadmap tenga la
sección escrita.
