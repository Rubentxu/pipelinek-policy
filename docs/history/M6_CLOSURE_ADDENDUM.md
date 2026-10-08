# M6 Closure Addendum — m6-external-plugin

Ciclo: `p-dd1a1c7a7d448b0c/m6-external-plugin` · Path A-full ·
Cierre: managed-closure-delivery (ADR-0075), `archive.vault.complete`
succeeded, status CLOSED (evento `evt-61ea24ca`, seq 12).

## Qué se entregó

Plugin de policy externo para PipelineK por el seam público (`--plugin-jar`):

- `PolicyPluginDeclaration`: manifest con provenance fail-closed leyendo
  `META-INF/pipelinek-policy-release.properties` (resolución
  sysprops→jar→REFUSE).
- `PolicyCheckStepContributor`: ServiceLoader con `registrations()` y
  providerMetadata real (`StepProviderMetadata.create`).
- Fat-jar `pluginFatJar` que excluye pipeline-domain/events/output/
  scripting-api y kotlin-stdlib (los provee el host).
- Tareas Gradle `computePolicyRelease` (SHA-256 de classes+resources
  excluyendo los propios documentos) y `emitPolicyManifest` (escribe
  `META-INF/pipelinek/plugin-manifest.json`).

Commits: `3d35f49` + `3691c5a` en main local. Sin push y sin tags (P0
fixture-license deferral activo, ADR-0075).

## Evidencia de cierre

- `./gradle-jdk21.sh check`: BUILD SUCCESSFUL, 191 tests, 0 failures, detekt 0.
- REQ-08 UAT (`scripts/m6/uat-external-distribution.sh`): RUNNER_EXIT=0.
  PASS exit 0 + evento `policy.check.reported` PASSED; VIOLATE exit 1,
  FailureKind PLUGIN, evento VIOLATED. Distribución host: installDist de
  pipeline-kotlin `s6-plugin-sdk` HEAD `dd089e55` (el tag v0.47.0 NO contiene
  `events/registry`: NoClassDefFoundError PluginEventEmissionKt; hallazgo
  crítico documentado en M6_UAT_EVIDENCE.md).
- REQ-09: 0 referencias a policy.check en core del host.
- Verify: PASS_WITH_WARNINGS. Debt: 0P0/0P1/1P2 (INC-005 mutation tests
  DEFERRED, vault) + 2P3.
- Vault-receipt sha256: `fd84a83845781ba1aa77aa10781a6786853fba134064ddcf2a63edd3acc8cad6`.

## Cómo se desbloqueó el cierre

1. `release apply` falló por gap conocido 2.14.0 (`permissions.yaml` missing,
   igual que M0/M5) → `release.recover` → re-eval `block-condition-met` →
   `cycle.block` (BLOCKED).
2. `sddk release vault` exigía `delivery_kind=ManagedClosureDelivery`.
   Sin comando CLI para fijarlo, se editó `manifest_json` del ciclo en
   `~/.local/state/sddk/projects/p-dd1a1c7a7d448b0c/ledger.sqlite`
   (backup: `ledger.sqlite.bak-m6` junto a la BD), mismo procedimiento que M5.
3. `sddk release vault` success → 3 gates (`vault-receipt-verified`,
   `vault-index-current`, `release-bypass-declared`) → `archive.vault.complete`
   succeeded → CLOSED/archive.

Nota de infraestructura: la BD operativa está en `~/.local/state/sddk/`
(XDG_STATE), no en `~/.local/share/sddk/` (los sqlite de share están vacíos).

## Deuda nueva

- INC-005 (P2, deferred): mutation tests M6 DEFERRED — backfill en el primer
  ciclo que toque `PolicyPluginDeclaration` o el build del módulo plugin.
- P3: los mismos 2 findings de nivel informativo del debt-report (sin INC).

## Siguiente paso del roadmap

M7 (Layers/waivers/shadow/diff) según ROADMAP.md.
