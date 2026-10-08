# M6 Exploration — PipelineK External Plugin

**Cycle:** `p-dd1a1c7a7d448b0c/m6-external-plugin` · **Path:** A-full · **Branch:** `main`
**Date:** 2026-10-08 · **Phase:** `explore`

## External precondition (documented, not blocking)

M6's input asks for "PipelineK S6 Plugin SDK certificado e integrado, no sólo branch
experimental". Observed state of `pipeline-kotlin` (repo `Rubentxu/pipeline-kotlin`):

- The S6 work lives on branch `s6-plugin-sdk` (69 commits over main, v0.47.0), with cycle
  `rp7-sem-s6-plugin-sdk` OPEN at phase build. Its substance is certified by receipts:
  B2 (SDK v2 + BOM + external execution proven) CLOSED, B3/B4 partial, B4.5 BLOCKED_EXTERNAL.
- Merging to that repo's main requires explicit authorization under its own governance
  (precedent B0-F1, PR #99). That merge is an EXTERNAL CONDITION recorded here, not a
  blocker for policy-plugin work: this cycle builds against the published artifacts.

**Resolution used by this cycle (local, no remote writes to pipeline-kotlin):**

1. `publishToMavenLocal` of `pipeline-sdk-bom`, `pipeline-domain`, `pipeline-events`,
   `pipeline-output`, `pipeline-scripting-api` — all at **0.47.0** from branch
   `s6-plugin-sdk`. OBSERVED: 5 module dirs under `~/.m2/repository/dev/rubentxu/pipeline/v2/`.
2. Installed binary `pipelinek` 0.47.0 (asdf) verified: `--plugin-jar` present in
   `CliParser` (strings of `pipeline-application-0.47.0.jar`), smoke `validate` on a
   minimal script → `VALIDATION SUCCESSFUL`.

## The seam, mapped (all OBSERVED in pipeline-kotlin sources)

| M6 deliverable | Public SDK surface |
|---|---|
| plugin manifest | `PluginManifest` (schemaVersion, plugin ResourceRef, release, apiRange, publisher, families, delivery, trust, contributions) — `pipeline-domain/…/step/PluginManifest.kt` |
| `policy.check` StepDefinition | `StepDefinition<I,O>` with `StepContract(key, StepDescriptor, inputCodec, outputCodec, requiredCapabilities)` + `StepHandler<I,O>` — pattern `examples/example-block-plugin/…/RepeatBodyStepDefinition.kt` |
| codec/request/result | `StepCodec<I>` / `EncodedStepValue` (JSON string); typed `@Serializable` input/output data classes |
| DSL façade | `StageScope.registryStep(stepKey, encodedInput, schemaVersion)` — `pipeline-scripting-api/…/StageScopeBuilders.kt:331` |
| event definitions | `EventDefinitionContributor.definitions(): EventDefinitionCreation<P>`; `EventDefinition.create(kind namespaced, schemaVersion, payloadClass, codec, emittedBy)` — `pipeline-events/…/registry/EventDefinition.kt` |
| ServiceLoader/contributor wiring | `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor` (+ events equivalent); runtime adapter does discovery, domain never does |
| external build against published SDK | Gradle-independent subproject resolving `dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.47.0` platform from mavenLocal (pattern: `examples/example-block-plugin/build.gradle.kts`, WU-RP-035 slice D) |
| installed distribution fixture | `pipelinek` 0.47.0 asdf install + `--plugin-jar` run |

## Policy-side surface (OBSERVED in this repo)

- `BundleVerifier.verify(PolicyBundle)` / `verifyPacked(bytes)` → `VerifiedBundle`.
- `IrRuntimeAdapter.evaluate(VerifiedBundle, ValueNode)` → `RuntimeEvaluation(report, sourceMap)`.
- `ResourceDecoder` SPI (`DecoderContributor`) with per-format submodules json/yaml/csv/map.
- Root build enforces a dual allowlist (core = stdlib only; parser buckets per submodule).
  A new subproject MUST register its dependency bucket in `parserAllowedCoords`-style
  allowlists or the guard fails closed.

## Design decisions entering propose

1. New Gradle subproject `pipelinek-policy-plugin` inside this repo (external boundary:
   depends on published SDK 0.47.0 via mavenLocal platform BOM + policy core project).
2. `policy.check` = atomic registry Step (controller-side, READ_ONLY effects, MEMOIZED).
   Input: resource bytes + format + packed bundle bytes (both travel as Base64 in the
   typed input; the handler decodes purely — no filesystem I/O inside the handler beyond
   what the engine hands it, honouring evaluator law 5).
3. "read resource" of the UAT is the pipeline's native `readFile` step feeding the input,
   not plugin-internal I/O.
4. Output: `PolicyCheckOutput` (verdict, violations count, report digest, per-rule
   summary) implementing `TypedStepOutput`; operational failure = `StepOutcome.Failure`
   with `FailureKind.POLICY` if available, else ENGINE-adjacent carrier per SDK rules.
5. Events: `policy.check.reported` (namespaced `policy.check.reported`, schemaVersion 1)
   via `EventDefinitionContributor`.
6. DSL façade: `StageScope.policyCheck(resource=..., bundle=...)` lowering to
   `registryStep` with the plugin's `PluginStepId("policy.check")`.
7. Hard gate: 0 references of `policy.check` in semantic coordinator/core dispatch —
   satisfied by construction (plugin lives outside core); asserted by a search test
   (fitness-style) in this repo + the external build compiling against SDK only.

## Risks / unknowns

- `FailureKind` enum values in SDK 0.47.0 not yet enumerated (check at implementation).
- The distribution fixture requires the plugin jar built by an independent Gradle
  invocation resolving only published artifacts (mirror of example-block-plugin).
