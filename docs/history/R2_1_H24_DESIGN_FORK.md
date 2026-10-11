# R2.1 — H2.4 design fork recovery: comparison of alternatives

**Fecha:** 2026-10-11 · **Agente:** Jcode · **Work-item:** `6fb5b421-1201-4773-b748-e6510da8b87b`

## Objetivo de R2.1

La directiva R1–R4 pide resolver la bifurcación H2.4 antes de implementar:

> *"El agente ha señalado una decisión A/B con impacto estimado en 14 puntos de código, pero no ha incluido en su informe qué significa cada alternativa. Antes de implementar, recuperar la propuesta original y redactar una comparación breve."*

Este informe NO decide la alternativa. Recupera las tres alternativas que se pueden reconstruir a partir de los archivos históricos, las compara y las presenta al propietario del producto. La decisión queda fuera del alcance del agente.

## R2.1.1 — Lo que se sabe de la bifurcación

- El commit `02fd23e` documenta que el blast radius de la decisión A/B es **14 sitios** (13 tests en 7 archivos + 1 producción en `CanonicalPolicyJson:147`).
- El commit `caeab1b` marca H2.4 como "design fork, not a code gap".
- El commit `f8f16eb` (M8 sealed-planner) preserva `IndexPlan.InMemoryIndex` como miembro del sealed pero no lo emite nunca.
- El comentario `DatasetPlanner.kt:84-89` dice explícitamente:

  > *"Today the GLOBAL execution path is not implemented (law 12) and the planner refuses any GLOBAL-shaped rule before touching this map, so the parameter is intentionally unused. Suppress detekt UnusedParameter until H2.4 lands and the parameter becomes load-bearing."*

- El campo `DatasetSpec.requiresGlobalIndex: Boolean` existe en `DatasetShape.kt:28` como la "declaración que desbloquea GLOBAL" — un solo booleano por dataset.
- El `cert/SHA.txt` no contiene evidencia de la decisión (los anchors previos son fixes de H2.1, H2.2, H2.3, H3.1; ninguno cierra H2.4).
- **No existe un archivo `docs/history/H24_*.md` ni una ADR para H2.4** — el "diseño (A)" y el "diseño (B)" son nomenclatura interna del H2.4 owner, no compromisos grabados en el repositorio.

## R2.1.2 — Las tres alternativas reconstruibles

Estas alternativas NO son las "A y B" originales (que nunca se comprometieron). Son las **tres opciones arquitectónicas** que se pueden extraer de los archivos históricos, cada una con su origen rastreable.

### Alternativa A — "Coarse-grained" (lo que el código tiene hoy)

**Origen:** `DatasetShape.kt:26-28` (campo `DatasetSpec.requiresGlobalIndex: Boolean`) + `M8_DESIGN.md:30-34` + `M8_EXPLORATION_REPORT.md:38` (decisión 3).

**Contrato público:**
- `DatasetSpec` tiene un único `requiresGlobalIndex: Boolean`.
- Una regla GLOBAL se evalúa sólo si TODOS los datasets que referencia declaran `requiresGlobalIndex = true`.
- El planner emite un `IndexPlan` implícito (uno por dataset) o rechaza con `PlanRefusal(GLOBAL_WITHOUT_INDEX)`.

**Semántica LOCAL/AGGREGATE/GLOBAL:**
- LOCAL: una sola fila, O(1) memoria adicional.
- AGGREGATE: bounded accumulator, single-pass.
- GLOBAL: requiere TODOS los datasets del bundle marcados con `requiresGlobalIndex = true`; el executor materializa.

**Impacto:**
- `DatasetPlanner.plan()`: añadir rama "if (datasets.values.all { it.requiresGlobalIndex }) emit InMemoryIndex per dataset; else PlanRefusal".
- `StreamingEvaluator`: añadir rama GLOBAL que materializa cada dataset con requiresGlobalIndex y ejecuta las reglas en un segundo pass.
- `CanonicalPolicyJson:147`: el `requiresGlobalIndex: Boolean` se serializa tal cual.
- **Test surface:** 13 tests (cubren "all datasets required vs not", "single dataset not required", "GLOBAL con spec ausente", etc.).
- **Riesgo:** "all or nothing" para todos los datasets del bundle. Si una regla GLOBAL sólo necesita UN dataset indexado pero el bundle tiene OTRO dataset no indexado, la regla falla aunque no use el segundo.

**Coste de pruebas:** bajo (13 sites, ya identificados).
**Coste de migración:** bajo (es el shape actual, sólo falta la rama del executor).
**Riesgo de cambio irreversible:** medio (compromete el bundle a "todos los datasets GLOBAL-required o ninguno").

### Alternativa B — "Per-dataset required" (más precisa que A)

**Origen:** `M8_DESIGN.md:30-34` (texto "GLOBAL se declara explícitamente vía `DatasetSpec.requires` o `IndexPlan` — sin plan ⇒ PlanRefusal(GLOBAL_WITHOUT_INDEX)"). El texto menciona "IndexPlan" como alternativa, sugiriendo que el "requires" puede ser un campo por-dataset más preciso.

**Contrato público:**
- `DatasetSpec` mantiene `requiresGlobalIndex: Boolean` PERO el IR también lleva `requiresDatasetIndex: Set<String>` por regla (lista de nombres de datasets que la regla necesita indexados).
- El planner intersecta: para cada regla GLOBAL, `rule.requiresDatasetIndex ⊆ datasets with requiresGlobalIndex = true`.
- El planner emite `InMemoryIndex(dataset, column)` SOLO para los datasets que están en `rule.requiresDatasetIndex`.

**Semántica LOCAL/AGGREGATE/GLOBAL:**
- Igual que A, pero la materialización es selectiva (sólo los datasets que la regla usa).

**Impacto:**
- `Expression` ADT: añadir un campo o atributo `requiresDatasetIndex: Set<String>` (donde se llena — esto es la decisión real).
- `DatasetPlanner.plan()`: por cada regla GLOBAL, calcular el set de datasets referenciados, comprobar que están en `requiresGlobalIndex`, emitir un `InMemoryIndex` selectivo.
- `StreamingEvaluator`: ejecutar GLOBAL en un segundo pass, pero sólo sobre los datasets indexados.
- `CanonicalPolicyJson`: añadir `requiresDatasetIndex` al serializado de las reglas GLOBAL.
- **Test surface:** 13 tests (cubren "selective index", "missing index for one of two refs", "irrelevant dataset not required").
- **Riesgo:** el atributo `requiresDatasetIndex` se llenaría desde el IR — pero el IR actual no captura esa información; haría falta un lowering del DSL que la extraiga. Esto es trabajo de M3/M4 (FIR) que está en FAIL documentado.

**Coste de pruebas:** medio (13 sites, pero el atributo IR-side requiere M3/M4 vivo).
**Coste de migración:** alto (toca el IR canónico, no sólo el planner).
**Riesgo de cambio irreversible:** alto (cambia `Expression` ADT, afecta todo bundle existente).

### Alternativa C — "Refusal tipado" (lo que el código hace hoy + se queda ahí)

**Origen:** Es la posición del `DatasetPlanner.kt:97-100` y del `H22SealedPlanTest.kt` (test 02b "GLOBAL sin plan ⇒ refusal tipado, nunca degradar"). También es coherente con la regla arquitectónica preferente del R2.1:

> *"Criterio arquitectónico preferente: conservar el dominio genérico y funcional; tratar GLOBAL no soportado como rechazo tipado, antes que introducir una capacidad parcial o un índice ficticio."*

**Contrato público:**
- GLOBAL permanece como "shape estático del IR" (la anotación de coste de `POLICY_IR.md` §9).
- `requiresGlobalIndex: Boolean` se mantiene como DECLARACIÓN DE INTENCIÓN pero el planner sigue rechazando GLOBAL sin un executor real.
- El usuario final ve `PlanRefusal(GLOBAL_REQUIRES_INDEX)` con remediation explícito: "declare `requiresGlobalIndex: true` AND implement a GLOBAL executor for your use case (H2.4)".

**Semántica LOCAL/AGGREGATE/GLOBAL:**
- LOCAL: O(1)/fila, evaluado.
- AGGREGATE: bounded accumulator, evaluado.
- GLOBAL: refusal tipado en el planner. El IR lleva la anotación (para tooling), pero el evaluador nunca la ejecuta. Si la regla GLOBAL es indispensable, requiere un caso de uso concreto, un límite de memoria medido, y un executor real.

**Impacto:**
- `DatasetPlanner.plan()`: sin cambios. Sigue rechazando GLOBAL.
- `StreamingEvaluator`: sin cambios.
- `CanonicalPolicyJson:147`: `requiresGlobalIndex` se serializa como está.
- **Test surface:** los 13 tests ya pasan (cubren el refusal); no se necesitan tests nuevos.
- **Riesgo:** "GLOBAL está en el IR pero nunca se ejecuta" puede confundir. Mitigación: documentación explícita en `POLICY_IR.md` §9 + `EVALUATION_SEMANTICS.md` + `ROADMAP.md` que diga "GLOBAL support is a forward-compatible shape annotation; the executor is not implemented in v0.3.x. Use AGGREGATE for cross-row analysis within a single pass, or open a product issue for a GLOBAL use case with a measured memory budget."

**Coste de pruebas:** cero (los 13 sites ya están cubiertos por tests de refusal).
**Coste de migración:** cero (no hay cambio de código).
**Riesgo de cambio irreversible:** cero (GLOBAL queda como anotación forward-compatible; el día que llegue un caso real, se reabre H2.4).

## R2.1.3 — Tabla comparativa

| Criterio | A (coarse) | B (per-dataset) | C (refusal tipado) |
|---|---|---|---|
| Contrato público | bool por dataset | set<string> por regla | bool por dataset (anotación) |
| PolicyIR / bundle compat | preserva | cambia el IR | preserva |
| Significado de `Expression.DatasetRef` | gated by all-datasets-required | gated by per-rule set | no se ejecuta |
| Diferencia LOCAL/AGGREGATE/GLOBAL | GLOBAL se ejecuta con materialización total | GLOBAL se ejecuta con materialización selectiva | GLOBAL ⇒ PlanRefusal |
| Impacto en `DatasetPlanner` | +1 rama | +1 set-intersect + emit selectivo | 0 |
| Impacto en `StreamingEvaluator` | +1 rama GLOBAL full-materialize | +1 rama GLOBAL selective | 0 |
| Impacto en `CanonicalPolicyJson` | 0 | +1 campo IR (`requiresDatasetIndex`) | 0 |
| Migración necesaria | baja (sólo executor) | alta (cambia IR canónico) | 0 |
| Coste de pruebas | bajo (13 sites) | medio (13 sites + IR) | 0 (13 sites ya cubiertos) |
| Riesgo de cambio irreversible | medio | alto | 0 |
| Cumple criterio arquitectónico preferente R2.1 | sí (capacidades parciales: GLOBAL sí pero coarse) | sí (capacidades parciales: GLOBAL sí pero selectivo) | **sí** (capacidades completas: AGGREGATE+; GLOBAL es forward-compat) |
| Decisión de producto: extender GLOBAL | sí (todo o nada) | sí (selectivo, pero requiere M3/M4 vivo) | no (GLOBAL queda como anotación) |
| `requiresGlobalIndex: Boolean` se usa | sí (planner lo consulta) | sí (subset check) | sí (DECLARACIÓN, no consumed por planner hoy) |

## R2.1.4 — Cumplimiento del criterio arquitectónico R2.1

La directiva R2.1 dice: *"Criterio arquitectónico preferente: conservar el dominio genérico y funcional; tratar GLOBAL no soportado como rechazo tipado, antes que introducir una capacidad parcial o un índice ficticio."*

**Lectura literal:** "antes que una capacidad parcial" sugiere que A y B (que introducen una capacidad parcial de GLOBAL) deben perder frente a C (que mantiene GLOBAL como rechazo tipado).

**Lectura funcional:** el "antes que" se aplica a la introducción de un ÍNDICE FICTICIO, no a cualquier capacidad. Un `InMemoryIndex` que el planner emite y el executor consume es un índice real, no ficticio. En ese caso, A y B también cumplen el criterio.

**Decisión del agente:** NO decido. La lectura literal vs funcional es del propietario del producto. Lo que SÍ digo:

- Si la lectura literal es la intención → **C** (mantener el refusal, GLOBAL como anotación forward-compatible, sin executor).
- Si la lectura funcional es la intención → **A o B**, según el coste de migración aceptable. **A** preserva el bundle y añade un executor coarse; **B** requiere M3/M4 vivo.

## R2.1.5 — Lo que NO se puede decidir desde aquí

- ¿Hay un caso de uso REAL que necesite GLOBAL con materialización parcial? (necesita casos de uso + medición de memoria, per la directiva: *"Si GLOBAL necesita un índice real, exigir un caso de uso concreto, límites de memoria, coste observable y ejecución completa de ese plan."*)
- ¿Está el equipo dispuesto a tocar el IR canónico (alternativa B) dado que M3/M4 está en FAIL documentado?
- ¿Se prefiere "todo o nada" en el bundle (A) o "selectivo" (B)?
- ¿Cuál es el roadmap de v0.4.0+ para GLOBAL?

## R2.1.6 — Recomendación del agente (NO decisión)

Si la directiva me pidiera una recomendación (no me la pide, pero la doy para que conste):

**Corto plazo (v0.3.0-alpha.2 / -beta.1 / -rc.1):** mantener **C** (refusal tipado). Coste cero, preserva todo, GLOBAL queda como anotación forward-compatible. Los 14 sites siguen igual.

**Medio plazo (v0.4.0+):** si llega un caso de uso real, evaluar **A** (coarse) primero — preserva el bundle, el executor es entero, los tests son los mismos 13 sites. **B** queda como "if M3/M4 lands AND a measured use case demands it".

**Largo plazo:** considerar una ADR nueva `ADR-0017-h2.4-global-execution.md` cuando haya datos para decidir. Hasta entonces, este informe es el registro de la decisión diferida.

## R2.1.7 — Salida R2.1

- ✅ Las 3 alternativas arquitectónicas recuperadas desde M8_DESIGN, M8_SPECIFICATION, M8_EXPLORATION_REPORT, POLICY_IR §9 y el código actual.
- ✅ Tabla comparativa con 13 criterios.
- ✅ Cumplimiento del criterio arquitectónico R2.1 analizado (lectura literal vs funcional).
- ✅ Recomendación del agente, explícitamente marcada como NO decisión.
- ✅ R2.1 cierra en `READY_FOR_HUMAN_GATE`: la decisión de qué alternativa (o de diferir GLOBAL indefinidamente) es del propietario del producto.
