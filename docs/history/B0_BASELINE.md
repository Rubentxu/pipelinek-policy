# B0.4 — Baseline de verdad (BLOQUE B0)

Fecha: 2026-10-09 · Rama: `feat/m10-cert`

## Identidad exacta

- **HEAD:** `a90e72670da13d0147f06cb7ed6cce81e7fd455d`
- **Tree:** limpio (`git status` 0 entradas) tras commits M8 WU-5 (`ddad548`) y WU-6 (`a90e726`), ambos con align+close SDDK satisfechos.
- **SHA auditado por el plan B0–B6:** `90c3d0e` — **obsoleto**: existen 2 commits posteriores que ya resuelven parte del scope B5 (streaming M8 WU-1..WU-6). Ver "Cambios posteriores a la auditoría".

## Módulos compilados (settings.gradle.kts)

| Módulo | Rol |
|---|---|
| `:` (root) | kernel de dominio puro + IR + bundle |
| `pipelinek-policy-cli` | CLI (9 comandos) + cert suite M10 |
| `pipelinek-policy-plugin` | plugin externo PipelineK (SDK 0.47.0) |
| `policy-decoders-csv/json/map/yaml` | decoders SPI |

## Tests — ejecución fresca (XML aislado, `clean` + `--rerun-tasks`)

**BUILD SUCCESSFUL** (test + detekt, 2026-10-09T10:52Z, toolchain JDK 21 vía gradle-jdk21.sh; `java` del sistema = 25.0.4.1 LTS, irrelevante para la build).

| Módulo | tests | failures | errors | skipped |
|---|---|---|---|---|
| root (kernel) | 188 | 0 | 0 | 0 |
| cli | 61 | 0 | 0 | 0 |
| plugin | 21 | 0 | 0 | 0 |
| decoders-csv | 11 | 0 | 0 | 0 |
| decoders-json | 16 | 0 | 0 | 0 |
| decoders-map | 5 | 0 | 0 | 0 |
| decoders-yaml | 7 | 0 | 0 | 0 |
| **TOTAL** | **309** | **0** | **0** | **0** |

Nota: 309 ≠ baseline M10 (273). Delta = suite M8 (DatasetShape 5, Accumulator 5, StreamingEvaluator 7, CsvRowSource+Jsonl 6, StreamCmd 8, help 9-command update, etc.). Los XML son de esta ejecución (dir mtime 12:50–12:54 local), no reutilizados.

## Detekt / fitness

- `detekt`: PASS (incluido en el BUILD SUCCESSFUL; el refactor de StreamingEvaluator en WU-6 resolvió las 6 violations nuevas).
- Fitness completo (AAT): **NOT_MEASURED** en esta pasada (solo test+detekt). Pendiente ejecutarlo en el gate B0.

## Plugin SDK externo

- `pipeline-sdk-bom 0.47.0` desde mavenLocal (branch `s6-plugin-sdk` de pipeline-kotlin, HEAD local `e01bd7a2`).
- Estado: funcional (21/21 tests plugin), pero **no re-publicado para este HEAD**: B6.7 debe registrar SHAs/digests de las dependencias de integración.

## Cambios posteriores al SHA auditado (90c3d0e) relevantes al plan B

- `ddad548` + `a90e726` = M8 WU-1..WU-6: cubre **parcialmente** B5.4 (planner/shapes), B5.6 (accumulators, aunque `Sum` sigue en Double → B5.6 pendiente de semántica exacta), B5.7 (CLI stream). **No** cubre B5.2 (streaming por chunks; hoy `ByteArray` completo), B5.3 (tokenizer duplicado), B5.8 (medición reproducible formal).
- `Expression.DatasetRef` ya existe y es simétrico en el writer JSON canónico; la **asimetría decode** que cita B2.4 sigue abierta (el reader aún no reconstruye `datasetRef`): verificar en B2.

## Requisitos verificados / no medidos (estado de partida)

- Verificados (evidencia arriba): suite 309/0, detekt, streaming M8 con UAT 64 MiB plana.
- **No medidos / abiertos (inputs del plan B):** B0.1 Error→PASSED (por reproducir), B1.1 colisión RuleId, B1.2 Double en comparaciones, B1.3 forbid, B1.4 CountAsLongSignal, B2.1 round-trip IR exhaustivo, B2.3 toString selectores, B2.7 admission budgets, B4.2 waiver matching, B6.3 CsvParityTest con árbol manual.

## Certificación de producción

**BLOQUEADA** (por mandato): permanece bloqueada mientras existan defectos P0/P1 de B0/B1/B2/B4 sin resolver.
