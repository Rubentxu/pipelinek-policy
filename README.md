# pipelinek-policy — Blueprint de producto y arquitectura

Este paquete define una base lista para iniciar un repositorio independiente de **policy-as-code en Kotlin** integrado como plugin externo de PipelineK.

La idea central es deliberadamente distinta de «OPA escrito en Kotlin» y de «validar YAML con reglas». El producto debe evaluar **políticas sobre cualquier dato estructurado recorrible** —JSON, YAML, CSV, TOML, mapas Kotlin, XML/DrawIO normalizado, SBOM, SARIF, Terraform plan JSON, facts de CogniCode, etc.— con una experiencia de autoría Kotlin, un runtime funcional puro y un IR declarativo, determinista y portable.

## Decisiones cerradas

1. **El runtime no ejecuta bytecode arbitrario de políticas.** El Kotlin de autoría construye `PolicyIR`; el bundle distribuye IR, no lambdas JVM como autoridad.
2. **El modelo universal es un árbol de valores**, no `Deployment`, YAML ni JSON: `Object | Array | Text | Integer | Decimal | Boolean | Null`.
3. **Los formatos son adapters.** Un `ResourceDecoder` transforma cualquier origen a `ResourceDocument + ValueTree + SourceMap`.
4. **Kotlin 2.4+ es la superficie de autoría.** Context parameters, sealed ADTs, value classes, builder inference y un compiler plugin FIR pequeño se usan donde aportan seguridad y ergonomía.
5. **`root.foo?.bar` es la sintaxis objetivo.** En shape abierto significa navegación simbólica por clave. Con shape conocido se añaden autocomplete, tipos y detección de claves inexistentes.
6. **El compiler plugin no es autoridad semántica.** Debe bajar a la misma API canónica explícita y producir el mismo digest de `PolicyIR`.
7. **No hay I/O dentro del evaluator.** Ficheros, red, secretos, reloj y procesos se resuelven fuera, normalmente mediante PipelineK Steps/capabilities, y entran como facts.
8. **El core es funcional puro.** Efectos en los bordes; errores y estados son ADTs explícitos; no globales, singletons ni excepciones como control de flujo.
9. **PipelineK es host/orquestador, no parte del engine.** `pipelinek-policy` debe ser útil y testeable sin PipelineK; el plugin adapta Step/Event/DSL públicos.
10. **Cedar futuro y este proyecto no compiten.** Cedar queda orientado a autorización de efectos (`principal/action/resource/context`); `pipelinek-policy` a compliance, validación y evaluación de facts arbitrarios.
11. **No se requiere schema para escribir políticas.** Un objeto abierto ya es navegable. Los schemas sólo aumentan las garantías.
12. **La implementación se gobierna por falsación.** Cada hito tiene UAT, AAT/fitness, mutaciones y condiciones explícitas de STOP/ADR.

## Orden de lectura

1. [Product Charter](docs/00-product/PRODUCT_CHARTER.md)
2. [Decisiones y no-objetivos](docs/00-product/DECISIONS.md)
3. [Ingeniería inversa de framework_modular](docs/01-research/FRAMEWORK_MODULAR_REVERSE_ENGINEERING.md)
4. [Comparativa mercado + Kotlin](docs/01-research/MARKET_AND_KOTLIN_RESEARCH.md)
5. [Arquitectura](docs/02-architecture/ARCHITECTURE.md)
6. [Core funcional](docs/02-architecture/FUNCTIONAL_CORE.md)
7. [Modelo de datos y shapes](docs/02-architecture/DATA_AND_SHAPE_MODEL.md)
8. [Arquitectura del compiler plugin](docs/02-architecture/COMPILER_ARCHITECTURE.md)
9. [DSL](docs/03-specifications/KOTLIN_POLICY_DSL.md)
10. [Policy IR](docs/03-specifications/POLICY_IR.md)
11. [ResourceDecoder SPI](docs/03-specifications/RESOURCE_DECODER_SPI.md)
12. [Semántica de evaluación](docs/03-specifications/EVALUATION_SEMANTICS.md)
13. [Integración PipelineK](docs/02-architecture/PIPELINEK_PLUGIN_INTEGRATION.md)
14. [ROADMAP](ROADMAP.md)
15. [UAT](docs/06-testing/UAT_CATALOG.md)
16. [AAT/fitness](docs/06-testing/AAT_AND_FITNESS.md)

## Estructura documental

```text
.
├── README.md
├── ROADMAP.md                    # única autoridad de secuenciación
├── AGENTS.template.md
└── docs/
    ├── 00-product/
    ├── 01-research/
    ├── 02-architecture/
    ├── 03-specifications/
    ├── 04-adrs/
    ├── 05-roadmap/
    ├── 06-testing/
    ├── 07-performance/
    ├── 08-migration/
    ├── 09-examples/
    ├── 10-governance/
    └── 11-references/
```

## Regla de autoridad documental

- `ROADMAP.md` es la única autoridad de secuenciación.
- Los ADR fijan decisiones, no estados de ejecución.
- Los receipts/evidencias se crearán durante la implementación y se moverán a `docs/history/` al quedar superados.
- El estado operativo de SDDK no se guarda en el repo; vive en el directorio de usuario por proyecto.

## Quickstart

Estado: **v0.3.0-alpha.2** en el código. H1 está funcionalmente cerrado pero el
release sigue **BLOQUEADO por B6.10**; ver `ROADMAP.md` para el estado real por
hito. Esta sección se corrigió el 2026-10-10: antes describía el quickstart de
M0 («1 test class», «sin tipos de dominio») cuando el proyecto ya evaluaba
políticas de verdad. Era la misma clase de deriva que un ancla sin comparar.

Requisitos:

- JDK 21. El toolchain se resuelve vía Gradle; no se commitea path absoluto de
  usuario. El shell puede estar en otro JDK: el build forkea 21 y los receipts
  siguen al build, no al shell.
- Gradle 8.14.5 vía wrapper (`./gradlew`), cuyo JAR está versionado en
  `gradle/wrapper/gradle-wrapper.jar` (SHA256 verificado contra el oficial
  publicado en <https://gradle.org/release-checksums/>).
- Red para resolver Maven Central en la primera compilación.
- El módulo `pipelinek-policy-plugin` además necesita el SDK de PipelineK
  `0.47.0` instalado en `mavenLocal`. **Verifica su sha256 antes de usarlo:**
  `scripts/m6/uat-external-distribution.sh` lo comprueba y falla si el SDK no es
  el identificado (B6.7).

Construir y ejecutar la suite completa:

```bash
./gradlew check
```

Certificar y medir (ambos desde árbol limpio, ambos con receipts):

```bash
./scripts/m6/certify.sh         # suite completa + recibo en docs/history/B6_CERTIFICATION.md
./scripts/m6/mutation-gate.sh   # mutantes semánticos; falla si alguno sobrevive
```

`certify.sh` no solo ejecuta: escribe `docs/history/B6_CERTIFICATION.md` con el
SHA del árbol, los digests de los artefactos, los del SDK de integración, la
versión del toolchain y el número de tests **realmente ejecutados**. Ese número
está anclado en `cert/SHA.txt` y una ejecución más corta que la certificada
hace fallar el gate.

### Contrato CLI

`CommandRegistry` es la **única** fuente del contrato. Nueve comandos:

| Comando | Qué hace | Posicionales |
|---|---|---|
| `compile` | Compila fuente de política a un bundle reproducible | `policy` `out` |
| `check` | Evalúa recursos contra un bundle | `policy` `format` |
| `test` | Ejecuta fixtures allow/deny | `policy` `fixtures` `format` |
| `diff` | Diferencia dos bundles (opcionalmente sobre un corpus) | `a` `b` `corpus` |
| `explain` | Explica la evaluación de una regla | `policy` `rule` `resource` |
| `inspect` | Vuelca el IR canónico | `policy` |
| `shape` | Resumen estructural del bundle | `policy` |
| `bundle` | Operaciones de bundle (`verify`) | `verify` |
| `stream` | Aplica el bundle sobre un dataset csv/jsonl con tope de filas | `policy` `row-budget` `format-out` |

`--json-help` se despacha desde el registro, así que la tabla de arriba y la
salida de `--json-help` no pueden divergir sin que un test lo note.

`stream` es la superficie donde los límites son parte del contrato: un dataset
que excede `--row-budget` **se detiene y lo reporta** con el tope y lo consumido,
marcando como incompletas las reglas cuyo plegado quedó cortado. No lanza
excepción ni devuelve un informe limpio de una ejecución truncada (B6.6).

Para regenerar solo el wrapper (no necesario normalmente):

```bash
JAVA_HOME=<jdk-21> gradle wrapper --gradle-version 8.14.5 --distribution-type bin
```

Recibts e historial:

- `docs/history/B6_CERTIFICATION.md` — certificación vigente (se regenera)
- `docs/history/M0_BOOTSTRAP_RECEIPT.md` — SHA baseline, comandos, hashes
- `docs/history/M0_CHARACTERIZATION.md` — notas de investigación DOCUMENTED
