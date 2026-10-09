# BLOQUE B1 — Receipt (Cierre semántico kernel/DSL)

Fecha: 2026-10-09 · Base: `02c86dd` · Estado de aceptación: PASS.

## Requisitos y evidencia

| Requisito de ROADMAP B1 | Evidencia de aceptación observada |
|---|---|
| B1.1 RuleKey contextual e identidad sin colisiones | `KernelSemanticsFortressTest`: dos policies con el mismo ruleId conservan ambos resultados; IDs con puntuación no colisionan; claves contextuales duplicadas se rechazan. |
| B1.2 Números exactos, sin conversión implícita a Double | `KernelSemanticsFortressTest`: enteros mayores a 2^53, decimales exactos, mezcla BigInteger/BigDecimal; Double no finito produce Error tipado. |
| B1.3 Forbid y negación declarativa | `DslSemanticsFortressTest`: forbid satisfecho produce Violated y forbid no satisfecho produce Passed; `PolicyBundleRemediationTest` conserva `Expression.Not` en round-trip; `ParityTest` compara DSL con IR explícito. |
| B1.4 COUNT tipado | `KernelSemanticsFortressTest`: COUNT compone con comparación numérica, colección vacía da cero y COUNT aislado produce Error tipado, no señal booleana. |
| B1.5 Missing/Null/opcionalidad DSL→IR→evaluador | `DslSemanticsFortressTest`: Null no colapsa a Missing y campo optional ausente da NotApplicable; `PolicyBundleRemediationTest` conserva FieldRef opcional y Not en round-trip. |
| B1.6 Params en appliesWhen | `DslSemanticsFortressTest`: parámetro se sustituye; referencia sin resolver nunca produce pass silencioso. |
| B1.7 Determinismo por digest | `KernelSemanticsFortressTest`: mismos inputs y distinto orden de inserción producen digest estable. `EvaluatorTest` conserva pruebas de digest determinista. |

## Verificación enfocada y de integración

- Suites focalizadas del kernel, DSL, IR y datasets ejecutadas con `--rerun-tasks`: `KernelSemanticsFortressTest`, `DslSemanticsFortressTest`, `EvaluatorTest`, `CombinatorsTest`, `ParityTest`, `PolicyBundleRemediationTest`, `StreamingEvaluatorTest` y `RuleEvaluationTest`: BUILD SUCCESSFUL.
- Frontera CLI ejecutada con tests focalizados de `InspectShapeDiffTest` y `PropertyFuzzTest`: BUILD SUCCESSFUL.
- Frontera PipelineK plugin ejecutada con `FailClosedFortressTest` y `PolicyCheckHandlerTest`: BUILD SUCCESSFUL.
- Gate global posterior a B0.2 y con el árbol B1 integrado: `./gradle-jdk21.sh check --rerun-tasks --no-daemon --console=plain` terminó BUILD SUCCESSFUL en 2m32s, 36 tareas. XML frescos: 60 suites, 337 tests, 0 failures, 0 errors, 0 skips. `architectureFitnessGuard` y `detekt` pasaron.

## Falsificación negativa

Las pruebas fortress incluyen casos que rechazan el comportamiento defectuoso: colisión y duplicado de RuleKey, número no finito, COUNT sin tipo, Null/Missing distintos y parámetro sin resolver en appliesWhen. No se rebajaron aserciones para obtener verde.

## Límites y deuda explícita

- `Sum` agregado sigue en Double; corresponde a D-B5.6, no a B1.
- Decode simétrico de DatasetRef pertenece a B2.4.
- El ciclo M8 sigue abierto y su cierre permanece en B5.
- La certificación de producción continúa BLOQUEADA por los bloques pendientes B2/B4; este receipt no declara release ni push.
