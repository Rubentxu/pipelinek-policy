# B3 Receipt — Resource fidelity, source maps, and agent CLI

- Fecha: 2026-10-09
- WorkItem SDDK: `fd07312d-8e4d-4cf6-a820-7081d97ca1cd`
- Commits de implementación: `eb928a8ad6c54c705483d031da108cacc857c896`, `a6159d532743f02d4fb04519428ec848a166082e`
- Estado B3: **PASS**. No se declara release ni certificación de producción.

## Resultado

B3 conserva identidad de nodos y ubicaciones reales en JSON/YAML/CSV, emite findings base completos y serializados de forma consistente en JSON/JSONL, conserva deduplicación sin pérdida, y distingue las categorías de salida CLI 0–5. `test` y `explain` no convierten errores de evaluación en éxito o denegación esperada. La autoridad del ciclo M8 permanece intacta para B5.

La especificación CLI §5 agrupaba metadatos de gobierno que el evaluator standalone no puede conocer. ADR-0013 y la especificación vigente separan el schema base B3 de la extensión de gobierno B4. El CLI no inventa `enforcement`, `rollout` ni `waiverStatus`.

## Requisito → evidencia de aceptación

| Criterio B3 | Estado | Evidencia observada |
|---|---|---|
| Identidad estructural de todos los nodos | PASS | `CsvDecoderTest.whole document source map follows every node in structural preorder`; `each row source map assigns container before cell values`; `PhysicalLocatorTest.resolves a sibling AFTER a nested branch`. |
| Spans completos JSON | PASS | `JsonDecoderTest.every JSON node has a source span covering its complete token`, incluyendo escalares, objetos y secuencias. |
| Spans reales YAML | PASS | `YamlDecoderTest.scalar value on line 3 carries its real span`; nested scalar y elementos de secuencia verifican rangos completos; multi-document verifica anclas por documento. |
| Identidad y anclas CSV | PASS | `CsvDecoderTest` verifica `WHOLE_DOCUMENT`, `EACH_ROW`, pre-order y `Cell(row,column)`; duplicado de cabecera produce refusal y no sobrescribe identidad de celda. |
| Finding estructurado base | PASS | `FindingsTest.B3 base finding schema is complete in json and jsonl without guessed governance fields`; `CheckCmdTest` verifica digest del bundle, IDs, path lógico, valores actual/expected, mensaje y source anchor. ADR-0013 define las fuentes de cada campo. |
| Ubicación física en findings | PASS | `CheckCmdTest.findings carry the physical line and column of the violation` (JSON); `YAML finding carries its physical text span` (línea 3, columna 9); `CSV finding reports the exact physical row and column` (fila 2, columna 1). |
| Deduplicación sin pérdida | PASS | `FindingsTest` conserva ocurrencias distintas y estados `VIOLATION`/`ERROR` con la misma huella; `CheckCmdTest` conserva recursos/ocurrencias diferentes y deduplica la repetición real. |
| CLI descubrible y códigos estables | PASS | ADR-0012; `HelpAndExitCodesTest.exit codes and machine help match the CLI specification`, `06a 06b exit code matrix`, `05a root json-help lists all nine commands`, `05b falsification every listed command dispatches for real`. |
| `test` no acepta error como deny | PASS | `CompileBundleTestTest.test never accepts evaluator Error as an expected deny`; bundle ausente/corrupto clasificado como admission error; fixtures sin reconocer siguen siendo usage error. |
| `explain` no produce falso éxito | PASS | `ExplainCmdTest.evaluation error exits with evaluation error code`; violación devuelve el código de violación; regla desconocida devuelve usage. |
| JSON válido y escapado | PASS | `FindingsTest.agent JSON escapes control characters in finding strings`; JSON y JSONL comparten un serializer de objeto y conservan sus formatos de salida. |

## Falsificación observada

- Antes del cambio, los nuevos tests de schema fallaron porque el finding no contenía los campos base requeridos.
- Mutación: se renombró temporalmente la clave serializada `violationId` a `mutatedAwayViolationId`. `FindingsTest.B3 base finding schema is complete in json and jsonl without guessed governance fields` falló en la aserción de campo ausente.
- Se restauró el serializer y se repitieron los tests focalizados: **21 tests pasaron**. No se relajaron aserciones.

## Gates ejecutados

1. Baseline focal previo al schema: `./gradle-jdk21.sh :pipelinek-policy-cli:test --tests com.pipelinek.policy.cli.FindingsTest --rerun-tasks --no-daemon --console=plain` → **BUILD SUCCESSFUL**.
2. Bucle RED para el schema: Findings/CheckCmd focalizados → fallos esperados por campos B3 ausentes en JSON/JSONL.
3. Tras implementación y restore de mutación: FindingsTest + CheckCmdTest focalizados → **BUILD SUCCESSFUL**, 21 tests.
4. Gate integrado de módulos afectados:

   ```bash
   ./gradle-jdk21.sh :policy-decoders-json:test :policy-decoders-yaml:test :policy-decoders-csv:test :pipelinek-policy-cli:test --rerun-tasks --no-daemon --console=plain
   ```

   **OBSERVED:** `BUILD SUCCESSFUL` en 2m23s, 21 tareas Gradle ejecutadas. Pasaron las suites completas de decoders JSON/YAML/CSV y el módulo CLI completo.
5. `git diff --check` → **OBSERVED: sin errores**.
6. Alineación SDDK y cierre semántico pasaron para ambos commits de implementación: decoder `eb928a8...`, CLI `a6159d5...`.

Los builds mostraron warnings por constructores SnakeYAML de bajo nivel deprecados y casts redundantes en tests; el gate terminó correctamente. No se cambiaron esas advertencias ni se usaron suppressions.

## Resolución de contrato y alcance

- ADR-0012 fija exit codes 0–5 y conserva el rechazo de falso éxito.
- ADR-0013 corrige explícitamente la agrupación anterior del schema: B3 emite hechos disponibles del evaluator y de `SourceMap`; B4 añadirá datos de gobierno solo con contexto real.
- `D-M9-1` (traza por nodo del evaluator), `D-M9-2` (authoring `.kts` tras M4) y `D-M9-3` (diff multi-recurso) se verificaron contra el código actual y siguen como follow-ups P3 fuera de B3.
- Incidencias SDDK revisadas: la de apply/push y la del hook ceremonial están cerradas; la de flake corresponde al CLI Rust de SDDK, no a este CLI Kotlin. No se abrió un frente tangencial.

## Límites y estado global

- B4–B6 permanecen sin cambios; no se implementó gobierno de waivers, enforcement o rollout en el CLI B3.
- El ciclo `p-dd1a1c7a7d448b0c/m8-datasets-streaming` sigue `RELEASE_PENDING`, fase `release`, sin lease y sin transición durante este trabajo.
- No hubo push, tag ni release. La certificación de producción permanece bloqueada por los bloques posteriores del roadmap.
