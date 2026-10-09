# B2 receipt: PolicyIR canónico y bundles íntegros

Fecha: 2026-10-09
Estado del bloque: **PASS**
WorkItem SDDK: `dca0f181-6610-4a89-b8d1-c0ef7218e5a0`
HEAD de partida observado: `54d47bc40a7cbb755fed87be103ea615cad22136`

## Resultado

Se cerró el round-trip del codec de PolicyIR, la representación estructural de selectores, la lectura simétrica de `DatasetRef`, la distinción entre digest semántico y digest de artefacto, y la admisión PKB1 fail-closed con límites y validación canónica. El encoder histórico v1 admite únicamente el subset cuyos bytes puede reproducir exactamente. Las formas históricas decodificables pero no reemitibles de forma idéntica se rechazan en la admisión de bundles.

La fixture versionada `src/test/resources/ir/legacy-v1-source-ref.json` fija bytes de IR v1 y SHA-256 `e13b731b4dc5b8fd9f0305addab67207781361dc0414d5d7738f0fbffd5104f8`. El test comprueba igualdad byte a byte con `LegacyPolicyIrJsonV1.encode`, construye un pack PKB1 en runtime y lo envía a `BundleVerifier.verifyPacked`. No existe un blob PKB1 completo estático como fixture dorada.

## Criterios y evidencia observada

| Criterio B2 | Estado | Evidencia concreta |
|---|---|---|
| `decode(encode(IR)) ≡ IR` | PASS | `CanonicalPolicyJsonFortressTest`: round-trip de campos semánticos de documento/regla, `DatasetRef` y carrier decimal `BigDecimal` sin redondeo a `Double`. |
| Campos semánticos completos | PASS | El caso de documento/regla verifica `appliesWhen`, `code`, `expected`, `actual`, params de regla y globales, supersession, functions, shapes, `sourceRefs` y `languageVersion`. |
| Selectores estructurales, sin `toString()` | PASS | El caso de selector de colección comprueba segmentos estables, `expectedType`, `optional`, igualdad de bytes para entradas equivalentes, round-trip y ausencia de `Selector@`. |
| `DatasetRef` simétrico | PASS | El caso dedicado verifica `DatasetRef` tras encode/decode. |
| Canonicalización y determinismo | PASS | `CanonicalPolicyJsonFortressTest` comprueba bytes iguales para selectores equivalentes. `BundleReproducibilityTest` pasa sus dos casos de reproducibilidad. |
| `semanticDigest` frente a `artifactDigest` | PASS | El test de source locations observa digest semántico igual y digest de artefacto distinto al variar únicamente la ubicación. |
| Integridad de admisión PKB1 | PASS | `PolicyBundleAdmissionFortressTest` verifica el fixture legacy por la API pública, compara el digest recalculado y rechaza IR con campo ignorado no canónico, manifest alterado y source map forjado. |
| Admission budgets y entradas malformadas | PASS | Casos negativos para tamaño, profundidad, nodos, reglas, profundidad de selector, UTF-8 inválido, claves JSON duplicadas, gramática numérica inválida y cada prefijo truncado del pack. |
| Compatibilidad versionada | PASS, subset acotado | Fixture IR v1 con SHA-256 fijo y comparación byte a byte contra el encoder histórico. El pack construido en el test es aceptado por `verifyPacked`. Representaciones históricas no reproducibles se rechazan. |
| Rechazo tipado de metadata inválida | PASS | La metadata multilinea falla al empaquetar y `BundleVerifier.verify(bundle)` la traduce a `BundleRefusal`, no escapa como `IllegalArgumentException`. |

## Gates ejecutados

Las tareas Gradle se ejecutaron con `--rerun-tasks` y `--no-daemon`. El control de whitespace se ejecutó por separado.

1. `./gradle-jdk21.sh :test --tests 'com.pipelinek.policy.bundle.PolicyBundleAdmissionFortressTest' --rerun-tasks --no-daemon --console=plain`
   **OBSERVED:** BUILD SUCCESSFUL, 7/7 tests.
2. `./gradle-jdk21.sh :test --tests 'com.pipelinek.policy.ir.*' --tests 'com.pipelinek.policy.bundle.*' --rerun-tasks --no-daemon --console=plain`
   **OBSERVED:** BUILD SUCCESSFUL, 26 tests. Incluye admisión, codec canónico, reproducibilidad, remediación de bundles, source maps y aceptación M5 de bytes empaquetados.
3. `./gradle-jdk21.sh detekt --rerun-tasks --no-daemon --console=plain`
   **OBSERVED:** BUILD SUCCESSFUL, exit 0. Los hallazgos encontrados durante la iteración se resolvieron mediante extracción de helpers/writer y formato. No se añadieron suppressions ni se relajaron reglas.
4. `./gradle-jdk21.sh check --rerun-tasks --no-daemon --console=plain`
   **OBSERVED:** BUILD SUCCESSFUL, exit 0, 36 tareas ejecutadas, duración reportada 2m23s. Incluyó los módulos del proyecto y UAT de streaming que forman parte del gate global.
5. `git diff --check`
   **OBSERVED:** el control inicial detectó espacios finales en este recibo, se corrigieron. El control final se repite sobre los cambios staged antes del commit.

## Falsificación y fallos corregidos

- Antes del fix, la verificación tipada de metadata inválida dejaba escapar una excepción genérica. El test `multiline metadata cannot produce a bundle and typed verification refuses it` falsifica ese comportamiento y pasa con la traducción a `BundleRefusal`.
- Bytes IR no canónicos que el decoder puede ignorar se rechazan al verificar el bundle, en vez de aceptarse basándose solo en los campos reconocidos.
- Las mutaciones de manifest/source map, límites excedidos y truncamiento se rechazan por la API pública `verifyPacked`.
- Detekt inicialmente informó problemas de complejidad/tamaño. Se resolvieron extrayendo `CanonicalPolicyJsonWriter` y `PolicyIrJsonNumbers`; el gate final pasó sin ocultar findings.

## Limitaciones y estado global

- La compatibilidad PKB1 v1 es deliberadamente un subset de bytes históricos reproducibles. La capacidad de decodificar una forma no garantiza que sea admisible para bundle.
- La golden versionada es el IR legacy y su digest, no un archivo binario PKB1 completo.
- B2 queda validado. Esto **no** certifica producción: siguen pendientes los bloques posteriores del roadmap, incluyendo B3–B6 y sus gates.
- No se hizo push ni se creó tag. El ciclo histórico M8 permanece abierto hasta su cierre previsto dentro de B5.
