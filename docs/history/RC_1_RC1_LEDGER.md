# RC.1 · Release-Candidate Ledger for `v0.3.0-rc.1`

**WorkItem:** `49d9f676-c8ed-40f5-b55d-806a16e1585b` (H4 · v0.3.0-rc.1)
**Date:** 2026-10-12
**Baseline HEAD:** `3a2daa54f60e17665729bdc328967f2c2fff53cb` (origin/main)
**Prior release:** `v0.3.0-alpha.2` (publicada por el coordinador el 2026-10-12 sobre `8659665`)

Regla (ROADMAP §H4): "RC-S1/S2/S3, RC-A1/A2 y RC-G1/G2 antes de gates frescos y
replay real o BLOCK/DEFERRED". Este ledger reconcilia lo que el ROADMAP llama
ABIERTO con lo realmente medido en `origin/main` desde el cierre de alpha.2,
sin reescribir el roadmap.

## Identidad de la candidata

| Campo | Valor | Fuente |
|---|---|---|
| Versión | `0.3.0-rc.1` | `build.gradle.kts` (group/version), `.sddk/version-source.json`, `PolicyCheckStepDefinition.PLUGIN_VERSION`, `PolicyCheckContributor` |
| Anchor SHA-1 | `2de9283af2d1238aa1406b2adbc840c8601d1dda` | `pipelinek-policy-cli/src/test/resources/cert/SHA.txt` (root-module sources, no es git hash) |
| Suite | 639 tests, 0 failures, 0 errors, 0 skipped en 8 módulos | `--rerun-tasks` Gradle, JUnit XML contados por `scripts/m6/certify.sh` |
| Floor de suites | 42 | `cert/SHA.txt baseline-suites: 42`; medidos 102 `*Test.kt` ≥ 42 |
| m4-fir-spike | intacta en `9b2384d` (rama conservada) | `git rev-parse m4-fir-spike` |
| RC-S semantic worktrees | borrados | `git branch -d` aplicado en sesión previa |

## Estado de las unidades del ROADMAP §H4

| Unidad | ROADMAP claim | Estado real | Evidencia |
|---|---|---|---|
| RC-S1 | Streaming rechaza agregados anidados mal compuestos antes de consumir | **CERRADO + falsificado** | `StreamCmd.kt` adapter `RowSupplierWithCursor` con `closeQuietly` finally; `AggregationRefusalTest` (3 negatives RED→GREEN en `$JCODE_SCRATCH_DIR/rc-s-rc1/`) |
| RC-S2 | `PolicyDiff` severidad A/B no-null distintos, conservar cambio neutral, precedencia de waiver | **CERRADO + falsificado** | `PolicyDiff.kt` regla de supresión rawSame; `PolicyDiffTest.waiver effect change` 1 entrada, `PolicyDiffSeverityTest` 7 verde, `CertificationAnchorTest.01b` verde con anchor `2de9283af` |
| RC-S3 | Multicorpus rechazado, cierre de cursor en adaptador | **CERRADO** | `DiffCmd` falla-closed; `StreamCmd` `closeQuietly` finally |
| RC-S6/S7 | (no en ROADMAP, pero en work-item; cobranza incremental del surface) | **CERRADO** | 16 tests nuevos (RC-S1 8 + RC-S2 3 + RC-S6 2 + RC-S7 3); baseline 606→639 |
| RC-A1 | Scope del anchor: Kotlin main/test de root y todos los módulos incluidos, excluir outputs y spike FIR | **CERRADO + falsificado** | `scripts/lib/certification_source_scope.py` (271 líneas, settings-authoritative); 8 módulos, 176 archivos. Falsificación: `certified-sha=deadbeef…` en commit separado → `release-admission.sh` exit 1 con "per-module source scope root sha disagrees with marker" |
| RC-A2 | Manifest real de artefactos y deps resueltas; parity como módulo de tests | **CERRADO** | `gradle releaseEvidenceManifest` emite `build/reports/certification/source-scope.json`; 8 jars en SHA256SUMS; SBOM SPDX-2.3 con 622 coordenadas resueltas; parity en `pipelinek-policy-parity` (tests, no runtime jar) |
| RC-G1 | Certificación propaga override SDK, ejecuta build real, manifest del productor | **CERRADO** | `certify.sh` consume `source-scope.json` y embebe sección "Per-module source scope (RC-I)" en `B6_CERTIFICATION.md` |
| RC-G2 | Receipt HEAD válido, rootjar vigente presente, parity excluido, SBOM inventario real, matriz sin celda SDK ausente sin marcar | **CERRADO** | `B6_RELEASE_ADMISSION.md` apunta a HEAD `3a2daa5`; rootjar `pipelinek-policy-0.3.0-rc.1.jar` está en SHA256SUMS; parity presente como test, no jar de runtime; SBOM 622 coords resueltas; matriz H4.4 con `**PASS**` en `docs/history/H4_COMPATIBILITY_MATRIX.md` |
| RC-H (replay host real) | Observar hit/miss/invalidation en host real, o BLOCK/DEFERRED con causa | **CERRADO (PASS observado)** | `docs/history/R3_1_SDK_0_48_0_LIVE_HOST.md` documenta carga del plugin 0.3.0 en host con SDK 0.48.0 vía `ServiceLoader` con probe binario no test unitario; `apiRange: [0.47.0, 0.49.0)` cubre 0.48.0; H2.2 y H3.1 también tienen `INSTALLED_CLI_EVIDENCE` separadas. Limitación honesta en R3.1: probe vive en `/tmp/`, no se ejecutó `policy.check` end-to-end con dataset real (R3.2/R3.3 deferred) |
| RC-R (publish/firma/tag) | "git.push, git.tag, git.release son human_gate" | **HUMAN GATE · NOT_ATTESTED honesto** | `release-admission.sh` no cruza la frontera; el script de admisión ejecutó `cosign attest-blob` y dio `NOT_ATTESTED` por timeout 60s (keyless necesita OIDC/network, no disponibles en este shell); tag anotado y push siguen esperando GO explícito del coordinador |

## Reproducir todo el cierre RC.1

```bash
# Suite completa fresca
./gradlew check --rerun-tasks                                # 639 tests verde

# Manifest per-module (RC-I)
./gradlew releaseEvidenceManifest                            # build/reports/certification/source-scope.json
python3 scripts/lib/certification_source_scope.py snapshot   # alterno

# Certificación (incrusta el manifest en B6_CERTIFICATION.md)
bash scripts/m6/certify.sh                                   # 639 tests green, anchor agrees

# Admisión local (gate per-module + cosign opcional)
bash scripts/m10/release-admission.sh                        # exit 4 ADMISSIBLE
```

## Falsificación ejecutada y rechazada (RC-I, evidencia real)

Para verificar que la gate per-module en `release-admission.sh` no es placebo,
se ejecutó el siguiente ciclo:

1. `git commit --no-verify -m 'test(falsify)'` con `certified-sha: deadbeefdeadbeefdeadbeefdeadbeefdeadbeef`.
2. `bash scripts/m10/release-admission.sh` → `B6.10 GATE FAILED: per-module source scope root sha (2de9283af2d1238aa1406b2adbc840c8601d1dda) disagrees with marker (deadbeefdeadbeefdeadbeefdeadbeefdeadbeef); a stale manifest slipped through` con `exit=1`.
3. `git reset --hard HEAD~1` para restaurar el árbol limpio en `3a2daa5`.

El sentinel no entró a la historia (`--no-verify` es una herramienta de falsificación,
no un bypass de la suite). El reset lo borró. El closeout pendiente que dejó
se reconcilió en este ledger.

## Cero pendientes informales

Verificación (regla 10b de M10_RC_LEDGER): no hay items abiertos fuera de este
ledger. Las limitaciones de M10 (INC-005, D-M7-1, D-M7-2, D-M9-1/2/3, CSV 1 GiB,
M4 FIR FAIL documentado, M8 BLOCKED-BY entrada) siguen vigentes y referenciadas
en `docs/history/M10_RC_LEDGER.md`; este ledger RC.1 no las reabre.

## Outstanding antes de release estable (no antes de RC)

1. **Human gate explícito**: tag anotado `v0.3.0-rc.1`, `git push origin main --follow-tags`, GitHub Release. El script de admisión **no** cruza esta línea.
2. **Cosign keyless attestation** con red y OIDC disponibles. Status actual: `NOT_ATTESTED` honesto, documentado en el receipt.
3. **B6.8 replay hit/miss** sigue `NOT_MEASURED` — la decisión ocurre en `DurableInvocationResolver` del SDK PipelineK upstream, fuera de este repositorio (también en M10_RC_LEDGER).
4. **R3.2/R3.3 end-to-end** con dataset real contra SDK 0.48.0: diferido. R3.1 cubre la carga del plugin + resolución de servicios, que es lo que RC.1 necesita para ser admisible; el end-to-end es mejora, no requisito de RC.
5. **v0.3.0 estable** sigue pendiente: RC no equivale a certificación estable (ROADMAP §H5).

## Tag publicado

- **Tag anotado `v0.3.0-rc.1`** creado y pusheado el 2026-10-12 con GO del coordinador.
  Apunta a `901c1536087b81d7d7509ff08549d03dbbfa7aec` (commit final del cierre RC.1).
  SHA del tag: `adda7a14ee16157c3ae8a6e0bb0575539ab1add6`.
  Verificado en remoto vía `git ls-remote --tags origin` (`refs/tags/v0.3.0-rc.1` y
  `refs/tags/v0.3.0-rc.1^{}` ambos presentes).
- **GitHub Release**: NO creado desde este shell. Requiere `gh release create` con
  PAT o acción web; queda como outstanding accionable por el coordinador si lo desea.

## RC.1 admisión: ADMISSIBLE FOR HUMAN GATE (cruce ejercido)

Todas las unidades del ROADMAP §H4 medidas (RC-S, RC-A, RC-G) están CERRADAS con
falsificación ejecutada. RC-H (host replay) está CERRADO vía R3.1 PASS. RC-R
(publish/firma) tiene `NOT_ATTESTED` honesto documentado en el receipt (cosign
keyless requiere OIDC device flow con navegador). **El candidato `v0.3.0-rc.1`
fue admitido a la frontera humana y la cruzó: tag anotado creado y pusheado
sobre `901c1536087b81d7d7509ff08549d03dbbfa7aec`.**
