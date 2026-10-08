# M6 Design — pipelinek-policy external plugin

**Cycle:** `p-dd1a1c7a7d448b0c/m6-external-plugin` · **Phase:** design

## Module layout

```text
pipelinek-policy-plugin/            # NEW subproject (SDK-facing, outside src/main core)
  build.gradle.kts                  # platform(dev.rubentxu.pipeline.v2:pipeline-sdk-bom:0.47.0)
                                    # + implementation(project(":")) core policy
                                    # + decoders subprojects needed at runtime
  src/main/kotlin/com/pipelinek/policy/plugin/
    PolicyCheckInput.kt             # @Serializable input (resourceBase64, format, packedBundleBase64)
    PolicyCheckOutput.kt            # @Serializable TypedStepOutput (verdict, counts, digest, summaries)
    PolicyCheckCodecs.kt            # StepCodec<PolicyCheckInput>/<PolicyCheckOutput> over JSON
    PolicyCheckStepDefinition.kt    # StepDefinition + StepContract + handler (pure pipeline)
    PolicyCheckDsl.kt               # StageScope.policyCheck(...) -> registryStep
    PolicyCheckEvent.kt             # payload + EventPayloadCodec
    PolicyEventContributor.kt       # EventDefinitionContributor (policy.check.reported v1)
    PolicyCheckContributor.kt       # StepDefinitionContributor (+ registrations() metadata)
    PolicyPluginManifest.kt         # PluginManifest for com.pipelinek.policy
  src/main/resources/META-INF/services/
    dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
    dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
fixtures/policy-plugin-external/    # independent Gradle build (own settings.gradle.kts)
  build.gradle.kts                  # resolves ONLY published coordinates + plugin jar
fixtures/policy-pipeline/           # UAT scripts + resources (passing + violating)
```

## Handler data flow (pure, evaluator laws honoured)

```text
PolicyCheckInput
  ├─ resourceBase64 ──decode──> ByteArray ──ResourceDecoder(format)──> DecodeResult
  │                                ├─ Ok(root: ValueNode, sourceMap)
  │                                └─ Refused ────────────────┐
  └─ packedBundleBase64 ──BundleVerifier.verifyPacked──> VerifiedBundle
                                   └─ refusal ────────────────┤
                                                              ▼
                                    PolicyCheckOutput.REFUSED (Failure carrier)
Ok + Verified ──IrRuntimeAdapter.evaluate──> RuntimeEvaluation(report)
                                   ▼
        verdict = VIOLATED iff any RuleEvaluation is Violated
        PolicyCheckOutput(PASSED|VIOLATED, violationsCount, report.digest,
                          report.policySetId, ruleSummaries)
```

- Enforcement: `PolicyCheckOutput.outcome` returns `StepOutcome.Failure` when
  verdict=VIOLATED (TypedStepOutput contract; the boundary treats it as the step's
  operational result, exactly like RepeatOutput.failedAt in the SDK example).
- REFUSED (decode/verify refusal) is an operational failure carrier, distinct from a
  policy verdict; it never masquerades as PASS (fail-closed).
- The handler performs ZERO I/O: bytes arrive encoded; decoding/eval is pure.

## StepContract

```kotlin
StepContract(
  key = PluginStepId("policy.check"),
  descriptor = StepDescriptor(
    stepId = "policy.check", name = "policyCheck", configRef = "",
    pluginId = "com.pipelinek.policy", pluginVersion = "0.1.0",
    executionLocation = ExecutionLocation.CONTROLLER,
    effects = listOf(Effect.READ_ONLY),
    replayPolicy = ReplayPolicy.MEMOIZED,
    body = StepBody.Atomic,           // atomic: no body, no capabilities
  ),
  inputCodec = PolicyCheckInputCodec, outputCodec = PolicyCheckOutputCodec,
  requiredCapabilities = emptySet(),
)
```

(Final shapes verified against SDK 0.47.0 at implementation; if `StepBody.Atomic`
differs, the example-uppercase atomic plugin is the reference.)

## DSL façade

```kotlin
fun StageScope.policyCheck(resource: String, format: String = "json", bundle: String) {
  val input = PolicyCheckInput(
    resourceBase64 = Base64.getEncoder().encodeToString(fileBytes(resource)),
    resourceFormat = format,
    packedBundleBase64 = Base64.getEncoder().encodeToString(fileBytes(bundle)),
  )
  registryStep(stepKey = PolicyCheckStepDefinition.KEY, encodedInput = PolicyCheckInputCodec.encode(input))
}
```

Reading files in the façade happens at COMPILE time of the pipeline script only if the
distribution exposes file access to scripts; otherwise the UAT script uses native
`readFile` and passes its content through script bindings. Chosen at implementation by
probing the public scripting API surface (readFile exists in StageScope; its content is
available to subsequent registry steps via script variables).

## Event

```kotlin
EventDefinition.create(
  kind = "policy.check.reported", schemaVersion = 1,
  payloadClass = PolicyCheckReported::class.java, codec = PolicyCheckReportedCodec,
  emittedBy = "com.pipelinek.policy",
)
```

Payload: `policyCheckReported(digest, verdict, violationsCount, policySetId)` serializable.

## Manifest

`PluginManifest` with apiRange covering 0.47.x, families {STEP, EVENT}, contributions
listing the policy.check step + the policy.check.reported event; validated by
`PluginManifestValidator` in a unit test (06a).

## External build fixture

Mirror of example-block-plugin: own `settings.gradle.kts`, requires `-PsdkVersion`,
resolves `platform(...pipeline-sdk-bom)` from mavenLocal plus
`com.pipelinek.policy:policy-core:<ver>` (published by root `publishToMavenLocal`).
Produces `policy-plugin.jar` consumed by the UAT via `--plugin-jar`.

## Architecture consistency

- Law 4/5: handler executes policy IR via in-process evaluator, no arbitrary bytecode,
  no I/O. ✓
- Law 6: format libraries stay in decoder subprojects; plugin depends on the SPI +
  decoders but evaluator core untouched. ✓
- Law 11: integration is exclusively the public plugin seam (registryStep/contributors);
  no pipeline-kotlin core edits. ✓
- Root build allowlist: new module gets its own bucket entry (sdk coords), guard stays
  fail-closed. ✓

## Test matrix

| Test | Covers |
|---|---|
| PolicyCheckCodecTest | REQ-02 (round trip, lexical numbers) |
| PolicyCheckHandlerTest | REQ-01, REQ-03 (pass/violate/not-applicable/refused) |
| PolicyEventContributorTest | REQ-05 (valid kind + falsification) |
| PolicyPluginManifestTest | REQ-06 (validator pass, identity) |
| ServiceLoaderDiscoveryTest | REQ-01 (META-INF wiring resolves) |
| CoreZeroReferencesTest | REQ-09 (0 hits in src/main outside plugin) |
| ExternalBuildTest (fixture, executed) | REQ-07 |
| M6UatAcceptanceTest (installed distribution) | REQ-08 (08a happy, 08b enforced) |
