# M6 UAT Evidence — external plugin against an installed distribution

Fecha: 2026-10-08 · Ciclo: `p-dd1a1c7a7d448b0c/m6-external-plugin` · Path A-full

## Resumen

REQ-08 (UAT contra distribución instalada vía `--plugin-jar`) demostrado con
runner reproducible: **pass** (exit 0, evento `policy.check.reported` PASSED) y
**violate** (exit 1, `FailureKind: PLUGIN`, evento VIOLATED). Clasificación:
**OBSERVED** (salidas reales de `pipelinek run`, capturadas abajo).

## Entorno

| Pieza | Valor |
|---|---|
| Distribución PipelineK | `installDist` desde checkout `pipeline-kotlin` branch `s6-plugin-sdk` HEAD `dd089e55` |
| Por qué no el binario asdf 0.47.0 | El tag `v0.47.0` NO contiene `events/registry` (commits `99c2b8ab..98a18992` son posteriores). El jar asdf tiene 0 clases registry; mavenLocal/installDist sí (27). |
| SDK consumido por el plugin | `dev.rubentxu.pipeline.v2:*:0.47.0` desde mavenLocal (publicado desde s6-plugin-sdk) |
| Plugin JAR | `pipelinek-policy-plugin-all.jar` (fat) sha256 `9c1cd1e69cd940fc29539be7ff5c98a5c423c28f8f6fd9bac250de7a78441753` |
| Manifest | `META-INF/pipelinek/plugin-manifest.json` emitido en build-time por `emitPolicyManifest` (patrón example-block-plugin S6/I), digest SHA-256 medido por `computePolicyRelease` |

## Runner

```
bash scripts/m6/uat-external-distribution.sh
```

Salida observada (2026-10-08 18:32):

```
== 4. UAT PASS (expect exit 0, PASSED)
PASS ok (exit 0, PASSED event)
== 5. UAT VIOLATE (expect exit 1, VIOLATED, FailureKind PLUGIN)
VIOLATE ok (exit 1, VIOLATED event, PLUGIN failure)
RUNNER_EXIT=0
```

## Evidencia PASS (replicas=4 >= 3)

```
"kind":"PluginEventEmitted","registryKind":"policy.check.reported","schemaVersion":1,
"payload":"v1PASSED0<digest>uat","emittedBy":"com.pipelinek.policy"
"kind":"RunFinished","outcome":"success"
Pipeline finished with SUCCESS
```

## Evidencia VIOLATE (replicas=2 < 3)

```
"kind":"PluginEventEmitted","registryKind":"policy.check.reported",
"payload":"v1VIOLATED1<digest>uat","emittedBy":"com.pipelinek.policy"
"kind":"StepFailed","failureKind":"PLUGIN",
"message":"policy check VIOLATED: 1 violation(s), report digest 9b9f226e..."
Pipeline finished with FAILURE (exit 1)
```

## Descubrimientos (OBSERVED)

1. **El tag v0.47.0 no sirve de host**: la superficie `events/registry`
   (EventDefinitionContributor, PLUGIN_EVENT_EMISSION_CAPABILITY) es posterior
   al tag. La distribución instalada por asdf lanza
   `NoClassDefFoundError: PluginEventEmissionKt` al registrar el plugin. La
   distribución debe venir de `s6-plugin-sdk` HEAD (installDist). El merge
   S6→main de pipeline-kotlin queda como condición externa abierta.
2. **Admisión real verificada**: el PreLoadPluginAdmission aceptó el artifact
   (sin manifest era rechazado con exit 2): "Discovered external Step plugins:
   ..., com.pipelinek.policy" + "Discovered external event definitions:
   policy.check.reported".
3. **La façade DSL resuelve rutas relativas al CWD del proceso** host
   (esperable: corre en el script host); el runner hace `cd $WORK`.
4. **CLI**: `pipelinek run --plugin-jar <jar> <script>` (la opción va tras el
   subcomando; antes del subcomando es `InvalidCommand`).
5. Fixture: bundle packed binario (`PKB1` magic) via `packUatBundle`
   (root test-runtime), regla `spec.replicas >= 3` (misma forma que
   M5UatAcceptanceTest).

## Tests módulo (OBSERVED)

`./gradle-jdk21.sh check` BUILD SUCCESSFUL: 15 tests del módulo plugin
(7 handler + 8 wiring) + suite completa del repo verde. Hard gate REQ-09
(0 referencias al core en main sources del módulo) en `CoreZeroReferencesTest`.
