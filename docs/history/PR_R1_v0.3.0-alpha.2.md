# Pull Request: R1 v0.3.0-alpha.2 reconciliation

> **Status:** `AUTHORIZED_FOR_PUBLICATION` · **Branch:** `integration/r1-alpha.2` · **Base preflight:** `origin/main` at `c906988582ea16085bb6ec0154559e5a30c770c6` · **Measured prepublication candidate:** `1b2e923bd050f66299ef67799c72d0a0ce44a1c6`. Fresh gates and packaging safeguards: `docs/history/R1_5_PUBLICATION_PREPARATION.md`. Exact PR head is the remote branch ref, not this historical measurement SHA.

## Resumen

Integra 63 commits de trabajo local que cierran los gaps H1–H5 del reviewer y preparan la versión `v0.3.0-alpha.2` para publicación como **GitHub prerelease**. Los gates de admisión local están verdes sobre el HEAD candidato:

| Gate | Resultado |
|---|---|
| `check` | 623/0/0/0 (R3.1.1 added 4 tests) |
| Mutation gate | 38/0/0 |
| Certify | PASS — root SHA `d5f2c2cb7bbc9b901aba0d29c907d952f6fda8ea` sobre 91 archivos |
| Release admission | **ADMISSIBLE** para `v0.3.0-alpha.2` (8 jars + SBOM) |

## Cambios principales

### H2 — Datasets & planning (4 production fixes + 1 refactor)

- **COUNT semántico** — `Accumulator.Count` cuenta filas coincidentes, no filas totales. Falsificado por M1 y M2.
- **RuleKey identity** — los consumidores de streaming ahora seleccionan reglas por `RuleKey` (policy+id), no por id desnudo. Falsificado por R1 y R2.
- **Sealed `PlanResult`** — `DatasetPlanner.plan(...)` retorna un tipo cerrado `DatasetPlanner.PlanResult`. Eliminado el `as DatasetPlan` unchecked cast.
- **`IndexPlan.SinglePass` por defecto** — el planner ya no emite un `InMemoryIndex` decorativo.

### H3 — Streaming (1 stream refactor + 1 CLI refactor)

- **`windowed JsonlSource`** — JSONL ahora se lee por chunks, no materializa. Falsificado por N3 y N4.
- **`StreamCmd` JSONL branch streams** — antes abría en memoria, ahora streamea.
- **`CsvRowSource` streaming** — el decoder CSV streamea el dataset. Falsificado por N1 y N2.

### R1 reconciliation (este ciclo)

- **R1.1 preflight** — medición del estado del repo, registro de la tensión cronológica del tag `v0.3.0-alpha.1` (en `427e30f` localmente vs `c906988` en `origin/main`).
- **R1.2 inventario** — agrupación de los 53 commits reconciliados, identificación de los archivos de identidad de versión.
- **R1.3 reproducibilidad de gates** — ejecución completa de `check`, `certify`, `mutation-gate`, `release-admission`, **falsificación** de la certificación de fuentes para el módulo plugin (la certificación es root-only por diseño, limitación documentada).
- **R1.4 bump de versión** — `0.3.0-alpha.1` → `0.3.0-alpha.2` en 4 fuentes de identidad (build.gradle.kts, PolicyCheckStepDefinition.kt PLUGIN_VERSION, .sddk/version-source.json, README.md) + 2 KDoc de consistencia.

## Archivos de identidad de versión (R1.4)

| Archivo | Línea | Cambio |
|---|---|---|
| `build.gradle.kts` | 16 | `version = "0.3.0-alpha.2"` |
| `pipelinek-policy-plugin/.../PolicyCheckStepDefinition.kt` | 88 | `PLUGIN_VERSION = "0.3.0-alpha.2"` |
| `.sddk/version-source.json` | 4 | `"version": "0.3.0-alpha.2"` |
| `README.md` | 72 | `Estado: **v0.3.0-alpha.2**` |
| `pipelinek-policy-plugin/.../PolicyCheckContributor.kt` | 45 | KDoc |
| `pipelinek-policy-plugin/.../ManifestVersionPrereleaseTest.kt` | 16 | KDoc |

`ManifestVersionPrereleaseTest` es tag-genérico (asserts dash + SemVer structure) — **no requirió edición**.

## Limitaciones declaradas (no silenciadas)

1. **Cert anchor scope es root-only** — la falsificación R1.3.2 demostró que el SHA certificado no captura cambios en el módulo plugin. Decidido: NO extender en R1 (alpha.2); R4.2 extenderá el cert a todos los módulos distribuidos. **No se afirma cobertura completa de fuentes en este alpha.**
2. **Provenance `NOT_ATTESTED`** — la firma cosign/Sigstore OIDC no se ha emitido; requiere login interactivo del propietario. Marcada como R3.4.
3. **Compatibilidad SDK 0.48.0 — R3.1 PASS + R3.1.1 in-repo gate, MEDIDO en host vivo** — el plugin, compilado contra SDK 0.48.0, (1) `gradle assemble -PsdkVersion=0.48.0` BUILD SUCCESSFUL en 12s, (2) `dependencies --configuration runtimeClasspath` resuelve los 4 módulos del BOM a 0.48.0, (3) probe binario contra SDK 0.48.0 + plugin-all.jar descubre `PolicyCheckContributor` vía ServiceLoader (`Found 1 contributors.`), (4) `plugin-manifest.json` declara `apiRange: [0.47.0, 0.49.0)` que cubre 0.48.0 por contrato. **R3.1.1 añadió `SdkApiRangeAgreementTest` al classpath de tests** (4 tests: apiRange cubre SDK resuelto, apiRange cubre 0.48.0 por contrato, ServiceLoader descubre PolicyCheckContributor, falsificación del comparador). 4/4 PASS contra SDK 0.47.0 y 0.48.0. Detalles en `docs/history/R3_1_SDK_0_48_0_LIVE_HOST.md` y `docs/history/R3_1_1_SDK_API_RANGE_AGREEMENT_TEST.md`. Limitaciones honestas restantes: no se ejecutó un `policy.check` real contra bundle+dataset+emitters (R3.2/R3.3); cert anchor sigue root-only por diseño (R4.2 lo extenderá).
4. **Replay hit/miss `NOT_MEASURED`** — la decisión ocurre en `DurableInvocationResolver` (fuera del repo). El fingerprint se verificó, pero el comportamiento real del host no. Marcado como R3.2.
5. **H2.4 design fork (GLOBAL execution path) — RESUELTO, alternativa C** — `docs/history/R2_1_H24_DESIGN_FORK.md` recuperó 3 alternativas (A coarse, B per-rule, C refusal tipado). `docs/04-adrs/ADR-0017-h2.4-global-annotation.md` adopta C: GLOBAL queda como anotación forward-compat en el IR, `DatasetSpec.requiresGlobalIndex: Boolean` se preserva como DECLARACIÓN, el planner sigue rechazando GLOBAL con `PlanRefusal(GLOBAL_WITHOUT_INDEX)` (ley 12), el `StreamingEvaluator` no implementa la ruta GLOBAL. **Cero código tocado.** El seam `DatasetPlanner.plan() @Suppress("UnusedParameter") datasets` queda preservado para reabrir H2.4 si llega un caso de uso medido. La alternativa B sigue gateada en M3/M4 (FAIL documentado).

## Documentación nueva

- `docs/history/R1_1_PREFLIGHT.md` — R1.1 measurement
- `docs/history/R1_2_INVENTORY.md` — R1.2 commit grouping
- `docs/history/R1_3_GATE_REPORT.md` — R1.3 gate reproducibility + plugin falsification
- `docs/history/RELEASE_NOTES_v0.3.0-alpha.2.md` — release notes for v0.3.0-alpha.2
- `docs/history/AUTO_VALIDATION_REPORT.md` — auto-validation of 9 reviewer gaps
- `docs/history/REVIEWER_GAP_CLOSURES.md` — 9 gaps reconciled
- `docs/history/H22_INSTALLED_CLI_EVIDENCE.md` — H2.2 GLOBAL refusal via real CLI
- `docs/history/H31_JL_INSTALLED_CLI_EVIDENCE.md` — H3.1-JSONL strace evidence
- `docs/history/R2_1_H24_DESIGN_FORK.md` — 3 alternatives for H2.4 (A coarse, B per-rule, C refusal), decision deferred
- `docs/04-adrs/ADR-0017-h2.4-global-annotation.md` — ADR adopting C (zero code change)
- `docs/history/R3_1_SDK_0_48_0_LIVE_HOST.md` — R3.1 SDK 0.48.0 live host load (4 evidence sources)
- `docs/history/R3_1_1_SDK_API_RANGE_AGREEMENT_TEST.md` — R3.1.1: SdkApiRangeAgreementTest (4 tests, in-repo gate)

## Receipts (B6 cycle)

- `docs/history/B6_CERTIFICATION.md` — ejecución en HEAD `98807a2`, source SHA `d5f2c2cb` (unchanged), 623/0/0/0, 12 jars, JVM 21 / Kotlin 2.4.20
- `docs/history/B6_RELEASE_ADMISSION.md` — HEAD `8de4129`, **ADMISSIBLE** for `v0.3.0-alpha.2` (8 jars, 622 SBOM coordinates, provenance NOT_ATTESTED)
- `docs/history/B6_MUTATION_RECEIPT.log` — 38 killed, 0 survived, 0 invalid
- `pipelinek-policy-cli/src/test/resources/cert/SHA.txt` — re-anchored `7873b723` → `d5f2c2cb` (post detekt fix, reason recorded)

## Compatibilidad

- **SDK PipelineK:** 0.47.0 y 0.48.0: compilación y ServiceLoader medidos. R3.1.1 verifica además apiRange en el classpath de tests. No demuestra `policy.check` completo ni replay hit/miss en un host instalado.
- **JDK:** 21 (LTS, gate), 25 (toolchain shell).
- **Kotlin:** 2.4.20.

## Cómo revisar

```bash
# Local:
git fetch origin
git checkout integration/r1-alpha.2
git log --oneline origin/main..HEAD
# 64 commits (63 R1 work + 1 merge commit)

# Re-run gates from a clean tree:
bash gradle-jdk21.sh --no-daemon check
bash scripts/m6/mutation-gate.sh
bash scripts/m6/certify.sh
bash scripts/m10/release-admission.sh
```

## Pasos posteriores a la aprobación

Una vez aprobado el merge a `origin/main` (R1.5 step 3, human-gated):

1. Re-correr certify + release-admission sobre el SHA integrado (los receipts deben nombrar el nuevo HEAD).
2. Crear el tag anotado `v0.3.0-alpha.2` apuntando exactamente al SHA certificado.
3. Crear el GitHub Release como **prerelease** con los 8 assets + SBOM.
4. Descargar los 8 assets publicados con `gh release download` y re-hashearlos con SHA-256 para verificar byte-by-byte.

**R1.5 step 3+ autorizado explícitamente el 2026-10-11T22:22:26.808Z.** No se afirma publicación hasta verificar merge, tag, release y descarga. La publicación añadirá jar raíz + ZIP CLI completo y checksums planos. El SPDX JSON se declara inventario de jars, no cobertura de 622 dependencias. Véase `R1_5_PUBLICATION_PREPARATION.md`.
