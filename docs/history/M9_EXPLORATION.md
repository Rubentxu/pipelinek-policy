# M9 Exploration — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** explore

## Decisión de secuenciación (DOCUMENTED)

El roadmap marca M8 como siguiente en orden lineal, pero la sección *Entrada*
del propio M8 exige "casos reales medidos que necesiten más que
local-resource evaluation" — inexistente hoy. M9 está `BLOCKED-BY-M5/M7`,
ambos DONE, y M10 depende de M6..M9 sin mencionar M8. Secuenciar M9 antes
que M8 respeta el DAG y evita violar la condición de entrada de M8.

## Inventario de capacidades (OBSERVED)

Superficies existentes que el CLI debe exponer:

| Dominio | Artefacto | Nota |
|---|---|---|
| Compilación DSL | `dsl/` (Combinators, DslScope, Symbols, BuilderCtx) | DSL canónico Kotlin M3 |
| IR + bundle | `ir/PolicyIrDocument.kt`, `bundle/` (PolicyBundle, BundleVerifier, IrRuntimeAdapter) | M5: bundle reproducible |
| Evaluación | `kernel/evaluator/Evaluator.kt` + `kernel/policy/` | core puro sin I/O (Law 5) |
| Layers/waivers | `kernel/policy/Layers.kt`, `Waivers.kt` | M7 |
| Diff | `kernel/policy/PolicyDiff.kt` | M7, waiver-aware |
| Decoders | json/yaml/csv/map en submódulos `policy-decoders-*` | M2 SPI |
| Plugin | `pipelinek-policy-plugin` | M6, pipeline-integration |

**No existe hoy**: ningún módulo CLI/`main`. `find` no arroja `main()` fuera
de tests. El CLI es superficie nueva.

## Decisiones de exploración

1. **Módulo nuevo** `pipelinek-policy-cli`: el evaluador es puro (Law 5);
   todo I/O (fs, argv, exit codes) pertenece a la capa CLI, no al kernel.
   Mismo patrón de separación que el plugin (M6).
2. **Entry point**: `PolicyCli` con dispatch por subcomando. Sin Picocli
   ni parser externo: argparse manual mantiene el grafo de dependencias
   del dominio limpio (Law 6 no aplica directamente al CLI, pero cero
   deps nuevas es la opción conservadora).
3. **Salida**: text/json/jsonl vía emitter dedicado; JSON manual (el
   proyecto ya serializa a mano en CanonicalPolicyJson — mismo estilo).
4. **Exit codes estables** (spec): 0 ok, 1 violations, 2 refusals/usage,
   3 error interno. Documentados en `--json-help`.

## Riesgos

- El CLI no debe convertir el kernel en impuro: toda lectura de ficheros
  vive en el CLI (STRUCTURAL: Evaluator no toca fs hoy y el chequeo de
  pureza de M10 lo exige).
- `explain` necesita SourceMap (decoder/) — ya existe la infraestructura.
- No hay "test runner" de policies como concepto aún: `test` command
  requiere corpus de fixtures; el shape del subcomando se define en spec.

## Preguntas que la spec debe cerrar

- ¿`check` evalúa un recurso o un corpus? (UAT M9 habla de findings JSONL
  ⇒ corpus multi-recurso con salida por-finding.)
- ¿`shape` imprime el LOCAL/AGGREGATE/GLOBAL shape de un dataset (M8
  vocabulary) o el shape de una policy? M8 no está hecho ⇒ shape de
  policy/dataset-lite v1.
- ¿`inspect` = dump IR canónico de un bundle? (Propuesta: sí.)
