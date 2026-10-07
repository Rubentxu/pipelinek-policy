# Decisiones cerradas, preguntas falsables y líneas rojas

## 1. Cerradas

| ID | Decisión |
|---|---|
| D-001 | Repositorio separado: `pipelinek-policy`. |
| D-002 | Plugin externo real de PipelineK; no submódulo privilegiado del core. |
| D-003 | `ValueTree` es modelo runtime universal. |
| D-004 | Policies Kotlin compilan a `PolicyIR`; runtime no ejecuta lambdas de policy como autoridad. |
| D-005 | Formatos entran por `ResourceDecoder` SPI. |
| D-006 | Source location se preserva en `SourceMap`, separado del valor semántico. |
| D-007 | `root.foo?.bar` es la UX objetivo del compiler plugin. |
| D-008 | La API explícita `field("foo")` es autoridad semántica y oracle de paridad. |
| D-009 | Shape abierto permite cualquier propiedad; shape cerrado puede rechazar claves inexistentes. |
| D-010 | El core es funcional puro y total donde sea posible. |
| D-011 | No excepciones como control de flujo en dominio/evaluator. |
| D-012 | No I/O, clock, random, env, reflection ni threads en policy evaluator. |
| D-013 | Context parameters expresan capacidades de construcción/evaluación; no hay service locator global. |
| D-014 | `Severity`, `Enforcement` y `Rollout` son ejes distintos. |
| D-015 | `SHADOW` no cambia PipelineOutcome. |
| D-016 | Waivers son datos gobernados, no `disablePolicy=true`. |
| D-017 | Platform/org/project/pipeline tienen precedencia explícita y fail-closed en conflicto. |
| D-018 | Cedar queda separado para autorización de efectos sensibles. |
| D-019 | Policy findings no transportan documentos completos como events. |
| D-020 | `ROADMAP.md` es única autoridad de secuenciación. |

## 2. Spikes falsables

### S-001 — Safe-call simbólico `?.`

**Hipótesis:** FIR puede ofrecer `root.spec?.replicas` con buen comportamiento en compilador, IntelliJ, incremental compilation y scripts Kotlin sin introducir una segunda semántica.

**PASS:**
- syntax highlighting y completion correctos;
- mismo IR que API explícita;
- test de compilación incremental;
- test `.kt` y `.pipeline.kts`;
- no backend hacks que el frontend/IDE no comprenda.

**FAIL:** la sintaxis principal pasa a `root.spec.replicas` y la optionalidad queda representada en `Expr`; se conserva navegación por puntos. No se adopta una implementación frágil sólo por mantener `?.`.

### S-002 — Kotlin compiler plugin maintenance budget

**Hipótesis:** limitar el plugin a generación/resolución de properties + checkers permite mantenerlo con una matriz acotada de Kotlin/IDE.

**STOP:** si una minor update de Kotlin exige reescritura sustancial o rompe IDE de forma no contenible, se degrada a codegen/KSP manteniendo la API canónica.

### S-003 — Arrow

**Hipótesis:** Arrow puede reducir boilerplate (`Either`, `NonEmptyList`, optics concepts) sin convertirse en autoridad semántica ni aumentar mucho API/ABI.

**Decisión inicial:** no dependencia obligatoria. Evaluar en M1 después de medir código y allocations.

### S-004 — Persistent collections

Evaluar `kotlinx.collections.immutable` para árboles/IR. Adoptar sólo si mejora sharing estructural/immutability sin coste inaceptable.

## 3. Líneas rojas

- `eval(String)` / `GroovyShell` / JavaScript runtime.
- policies YAML con expresiones.
- `Map<String, Any?>` como contrato público del dominio.
- reflection por operación del evaluator.
- compiler plugin que implemente semántica distinta al core.
- mutable global registry.
- policy que invoque PipelineK Steps desde el evaluator.
- policy que acceda a secretos directamente.
- decision basada en timestamps de ejecución no suministrados explícitamente como facts.
- hidden network calls.
- silently treating missing as `false`.
- silently coercing `"3"` to `3`.
- merging duplicate rules por “last wins”.
- un bundle local capaz de relajar mandatory policy de nivel superior sin autorización.
