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

## Quickstart (M0 — parcial)

Estado: **M0 INCOMPLETE · BLOCKED-fixture-license** (ver `ROADMAP.md`).

El repositorio contiene ya un esqueleto Gradle Kotlin/JVM mínimo ejecutable. No hay
código de producto en M0 (la base de tipos de policy vive en M1).

Requisitos:

- JDK 21 (cualquier distribución estándar: Temurin, Zulu, Liberica). El toolchain se
  resuelve automáticamente via Gradle toolchain auto-detection; no se commitea path
  absoluto de usuario.
- Gradle 8.14.5 vía wrapper (`./gradlew`). El wrapper JAR está versionado en
  `gradle/wrapper/gradle-wrapper.jar` (SHA256 verificado contra el oficial publicado
  en <https://gradle.org/release-checksums/>).
- Red disponible para resolver Maven Central en la primera compilación.

Construir y ejecutar la suite (1 test class genérico, sin tipos de dominio):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || echo "$JAVA_HOME") ./gradlew check
```

El comando debe terminar con `BUILD SUCCESSFUL` y `BootstrapSmokeTest` PASSED para:

- `JVM runtime is JDK 21` (`Runtime.version().feature() == 21`)
- `Kotlin runtime matches declared 2_4_20` (`KotlinVersion.CURRENT.toString() == "2.4.20"`)

**Guard automático**: el task `architectureFitnessGuard` corre como parte de `check`
y rechaza cualquier dependencia que no esté en la allowlist de producción
(`org.jetbrains.kotlin:kotlin-stdlib`, `org.jetbrains:annotations`), cualquier
directorio `pipelinek-policy-plugin/` presente en la raíz, y cualquier coordenada
reservada (`com.pipelinek:*`, `io.pipelinek:*`, `dev.pipelinek:*`,
`com.pipelinek.app:*`, `com.pipelinek.plugin:*`).

Para regenerar solo el wrapper (no necesario normalmente):

```bash
JAVA_HOME=<jdk-21> gradle wrapper --gradle-version 8.14.5 --distribution-type bin
```

Para inspeccionar receipts y caracterización del M0:

- `docs/history/M0_BOOTSTRAP_RECEIPT.md` — SHA baseline, comandos, hashes
- `docs/history/M0_CHARACTERIZATION.md` — notas de investigación DOCUMENTED
