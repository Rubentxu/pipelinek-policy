# M6 Proposal — pipelinek-policy external plugin

**Cycle:** `p-dd1a1c7a7d448b0c/m6-external-plugin` · **Phase:** propose/specify

## Intent

A policy authored, bundled and verified by pipelinek-policy runs INSIDE a real PipelineK
pipeline as an EXTERNAL plugin (`--plugin-jar`), with zero edits to pipeline-kotlin core.
The plugin contributes a `policy.check` step, a DSL façade, and an event; a pipeline
script reads a resource, checks it against a packed policy bundle, and the outcome is
enforced (violations fail the step with the report digest).

## Scope

- NEW Gradle subproject `pipelinek-policy-plugin` in this repo:
  - `PolicyCheckStepDefinition` (`PluginStepId("policy.check")`, atomic, controller,
    READ_ONLY, MEMOIZED), typed `PolicyCheckInput`/`PolicyCheckOutput` (`TypedStepOutput`).
  - `StepCodec`s over canonical JSON (`EncodedStepValue`).
  - `PolicyCheckContributor` (StepDefinitionContributor, overrides `registrations()` with
    real `StepProviderMetadata` from the plugin manifest).
  - `PolicyEventContributor` (EventDefinitionContributor, `policy.check.reported` v1).
  - `PluginManifest` for `com.pipelinek.policy` (steps + events contributions).
  - `StageScope.policyCheck(...)` DSL façade lowering to `registryStep`.
  - `META-INF/services` wiring for both contributors.
- External build fixture: an independent Gradle invocation (settings separate from the
  root build, like example-block-plugin) resolving ONLY published artifacts:
  `platform(dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.47.0)` from mavenLocal plus this
  repo's published policy artifacts. It produces the plugin jar used by the UAT.
- UAT fixture: pipeline script `readFile(resource) -> policy.check -> report` run with
  installed `pipelinek` 0.47.0 distribution + `--plugin-jar`.

## Approach

The handler is pure: it receives Base64 resource bytes + format + packed bundle bytes in
the typed input, decodes via `ResourceDecoder` SPI, verifies via `BundleVerifier.verifyPacked`,
evaluates via `IrRuntimeAdapter.evaluate`, and returns verdict + digest + violations.
Operational failure (refusal) returns `StepOutcome.Failure` carrier; policy violations
return Success carrier with verdict=VIOLATED and the step FAILS via the output's outcome
per TypedStepOutput contract (enforcement).

## Out of scope

- Merging S6 into pipeline-kotlin main (external condition, documented in exploration).
- Directive contributors, KSP metadata, ABI gates (S6.3/S6.5/S6.6 belong to pipeline-kotlin).
- Policy authoring changes (M4 closure stands).
