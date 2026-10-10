# Exploration report · b4-b5-b6-remediation

**Cycle** `p-dd1a1c7a7d448b0c/b4-b5-b6-remediation` · path A-min
**HEAD** `aea22a04023198eac1e2c8082870ec79e7c61c74` · `origin/main` idéntico · worktree limpio
**Ledger** verify OK · 184 eventos · `sha256:bad17e1d…c674a593`
**Base** código verificado en el árbol, no documentos de estado

## Why a new cycle

`m8-datasets-streaming` está en `RELEASE_PENDING` con **frontera vacía**: es
terminal. `sddk cycle next` devuelve `frontier: [] (terminal)`. Un ciclo terminal
no ofrece transición legal, y `replan` exige un lease activo sobre un ciclo no
terminal. Crear un ciclo nuevo deja la trazabilidad limpia en vez de forzar un
estado que la máquina no permite.

El WorkItem `6fb5b421-1201-4773-b748-e6510da8b87b` sigue registrado en el ledger
perteneciendo al ciclo m8 cerrado; se referencia como antecedente, no como
propiedad del ciclo actual.

## Verified baseline

`clean check --rerun-tasks` en `aea22a0`: **446 tests, 0 fallos, 0 errores,
0 skips**, 72 suites, detekt limpio. Delta exacto +9 sobre las 437 de `1d933fc`
(las 9 de B4.7). El purity guard `DomainIoPurityTest` cubre `kernel/governance`
recursivamente.

## Open items, each verified in the tree

### B4.7 · ingress budget — PARTIAL

`ResourceIngressLimits` existe en `aea22a0` con budget 64 MiB, techo 256 MiB
clampado, guarda O(1) sobre longitud codificada y vocabulario de rechazo tipado.
**Nadie lo invoca.** `grep` de `admitResource|admitBundle|ResourceIngressLimits`
sobre `src/main`, `pipelinek-policy-cli/src/main` y
`pipelinek-policy-plugin/src/main` no devuelve ninguna referencia de consumo.
`CheckCmd`, el adapter del plugin y `PolicyCheckPlanRequest` decodifican sin
presupuesto. Un presupuesto que nadie aplica no es un presupuesto.

Además, 5 comandos CLI usan `readBytes()` (`BundleCmd:34`, `CheckCmd:63`,
`CheckCmd:108`, `CompileCmd:35`, `DiffCmd:58`) sin `Files.size` previo.

### B5.3 · UTF-8 corruption — OPEN (P0)

`policy-decoders-csv/src/main/kotlin/com/pipelinek/policy/decoders/csv/CsvRowSource.kt`
líneas **106** y **127**: `sb.append(c.toInt().toChar())` convierte byte a
carácter 1:1. Cualquier multibyte UTF-8 se corrompe en silencio, sin excepción ni
warning. `grep` de caracteres no-ASCII sobre los tres módulos de decoders
(`policy-decoders-csv`, `-json`, `-yaml`) no encuentra **ningún** test.

> Corrección de una ruta: la auditoría del 2026-10-10 sitúa `CsvRowSource.kt:106`
> en `decoders/csv/…`. La ruta correcta es `policy-decoders-csv/…`. El
> defecto y la línea son las mismas; la ruta del informe estaba mal.

### B4.9 · real installed-plugin UAT — OPEN

`scripts/m6/uat-external-distribution.sh` no fija 20 recursos
(`grep -cE 'RESOURCE_COUNT|for i in|seq '` → 0) ni verifica la precondición por
comportamiento. Además mantiene `PIPELINEK_REPO` con default a una ruta local
hardcodeada.

### B5.5 · rule identity in datasets — OPEN

`kernel/dataset` indexa por `rule.id` en 8 sitios: `DatasetShape.kt:76`
(`shapesByRule: Map<String, DatasetShape>`) y `StreamingEvaluator.kt:118, 138,
156, 157, 164, 165, 166`. `StreamingEvaluator.kt:117` **sí** usa
`RuleKey.of(set.id, policy.id, rule.id)` para leer resultados, así que la
identidad es inconsistente dentro del mismo fichero: la lectura es compuesta y
la escritura es simple.

### B5.6 · accumulator budget — OPEN

`kernel/dataset/Accumulator.kt:38,62` lanza `BudgetExceededException` (línea 81).
El roadmap exige resultado tipado de presupuesto, no excepción incidental. Debe
migrar al patrón de B4.7 en vez de crear un segundo.

### B6.1–B6.10 · certification — OPEN

`cert/SHA.txt` declara `certified-sha: 5a9162b…`, `baseline-suites: 42`,
`baseline-tests: 273`. HEAD real es `aea22a0`. Ningún test compara el marcador
con HEAD. Gates confirmados como incapaces de fallar:
`CsvCharacterizationTest.kt:83` compara `reportStreamDigest(rows)` consigo mismo;
`CsvParityTest.kt:129` evalúa un `expectedTree()` construido a mano en vez de la
salida real del decoder. `scripts/m10/` tiene matriz de compiladores y CSV 1 GiB
pero no hay cobertura (sin JaCoCo ni Kover).

### P0 · CLI/plugin verdict divergence — severity UNDECIDED

`CheckCmd` tiene 0 referencias a `WaiverMatcher`, `AuthorityRegistry` o
`LayerComposer` y no expone `--waivers` ni `--enforcement`. El plugin sí usa el
intérprete compartido. M9 declara el CLI como superficie de producción, lo que lo
haría release blocker, pero esa lectura necesita confirmación humana.

## Sequencing

1. **B5.3 UTF-8** — corrupción silenciosa de datos; condición de cierre declarada
   por el propio roadmap. Adapters primero (CSV/JSONL/JSON), luego B5.2.
2. **B4.7 host wiring** — convierte el budget en garantía; incluye
   `Files.size` antes de los 5 `readBytes()`.
3. **B5.5 RuleKey** — identidad compuesta, 8 sitios, ya existe `RuleKey` en B1.
4. **B5.6** — migrar `BudgetExceededException` al patrón de B4.7.
5. **B4.9** — UAT real; requiere la distribución externa y su SHA.
6. **B6.1–B6.10** — certificación; B6.1 es un gate de tres líneas que puede
   invalidar todo lo demás si el SHA no se ancla al ejecutado.

## Unknowns

- Si el CLI es superficie de producción o herramienta de desarrollo (decide B4-T10).
- Cobertura real: no configurada, no cero.
- UAT de PipelineK instalado: requiere distribución externa y su SHA.
- B6.4 seeds y B6.5 harness de mutación: sin diseñar.

## Explicit non-claims

Este reporte no declara B4 cerrado. B4.7 y B4.9 están abiertos, y la
certificación sigue bloqueada por P0/P1 semánticos abiertos, tal como exige el
gate global de `ROADMAP.md`.
