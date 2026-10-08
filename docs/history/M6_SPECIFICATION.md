# M6 Specification — pipelinek-policy external plugin

**Cycle:** `p-dd1a1c7a7d448b0c/m6-external-plugin` · **Phase:** specify

Requirements with testable scenarios. Each REQ maps to at least one UAT/surgical test.

## REQ-M6-01 · policy.check StepDefinition

The plugin contributes an ATOMIC registry step with key `policy.check`, execution
location CONTROLLER, effects READ_ONLY, replay MEMOIZED, discovered via ServiceLoader
(`META-INF/services/…StepDefinitionContributor`).

- Scenario 01a (pass): a contributor-loaded registry resolves `policy.check` and the
  handler is INVOKED with a valid input.
- Scenario 01b (refused): malformed packed bundle bytes in input yield an operational
  failure carrier (never a thrown unclassified exception, never a fake PASS).

## REQ-M6-02 · codec/request/result

`PolicyCheckInput` (resourceBase64, resourceFormat, packedBundleBase64) and
`PolicyCheckOutput` (verdict: PASSED|VIOLATED|REFUSED, violationsCount, reportDigest,
policySetId, ruleSummaries) are `@Serializable` and round-trip through `StepCodec`
into `EncodedStepValue` byte-exactly.

- Scenario 02a: encode→decode round trip is identity for both types.
- Scenario 02b: lexical number representation is preserved (M5 lesson: no 3→3.0 drift).

## REQ-M6-03 · evaluation and enforcement

Given verified packed bundle B and resource R:

- Scenario 03a (enforced violation): R violates a rule ⇒ output verdict=VIOLATED,
  violationsCount>0, reportDigest present, and the step OUTCOME is Failure (enforced).
- Scenario 03b (pass): R satisfies the policy ⇒ verdict=PASSED, outcome Success.
- Scenario 03c (not-applicable short-circuit preserved): rules with appliesWhen=false
  are NotApplicable in ruleSummaries and do not count as violations.

## REQ-M6-04 · DSL façade

`StageScope.policyCheck(resource = <path>, format = <fmt>, bundle = <path>)` lowers to
`registryStep(stepKey=policy.check, encodedInput=…)`.

- Scenario 04a: a script using the façade VALIDATES against the installed distribution
  with `--plugin-jar` (CompilationFinished diagnostics empty).

## REQ-M6-05 · event definitions

The plugin contributes `policy.check.reported` (schemaVersion 1, namespaced, payload =
digest + verdict + counts) via `EventDefinitionContributor` + ServiceLoader file.

- Scenario 05a: contributor yields `EventDefinitionCreation.Valid` with kind
  `policy.check.reported`.
- Scenario 05b (falsification): a kind without namespace yields Invalid (SDK contract,
  pinned by a test that constructs it).

## REQ-M6-06 · plugin identity/manifest

`PluginManifest` for plugin id `com.pipelinek.policy` declares apiRange 0.47.x,
families {STEP, EVENT}, contributions matching the registered definitions, and the
contributor's `registrations()` returns `StepRegistration` with real metadata.

- Scenario 06a: manifest↔definitions equality holds (validator passes).
- Scenario 06b: plugin identity appears in the executed pipeline evidence (event
  emittedBy or provider provenance = `com.pipelinek.policy`).

## REQ-M6-07 · external build against published SDK

An independent Gradle build (own settings file, not the root composite) resolves ONLY
published coordinates (SDK BOM 0.47.0 from mavenLocal + this repo's policy artifacts)
and produces the plugin jar.

- Scenario 07a: `gradle jar` in the fixture succeeds with the BOM platform import and
  zero project dependencies on pipeline-kotlin sources.

## REQ-M6-08 · installed distribution fixture (UAT vertical)

With installed `pipelinek` 0.47.0 (asdf) and `--plugin-jar <policy-plugin.jar>`:

```text
read resource -> policy.check -> report -> enforced outcome
```

- Scenario 08a (happy): pipeline runs `readFile` + `policyCheck` façade; resource that
  PASSES ⇒ run succeeds, event `policy.check.reported` observed with digest.
- Scenario 08b (enforced): resource that VIOLATES ⇒ run FAILS at the policy.check step
  with the report digest in the failure carrier.

## REQ-M6-09 · hard gate: zero core references

No `policy.check` reference exists in semantic coordinator/core dispatch of
pipeline-kotlin, and none in this repo's core (`src/main` outside the plugin module).

- Scenario 09a: search test (grep-style, executed in CI) returns 0 hits in core
  dispatch surfaces; hits allowed only in plugin module + tests + docs.

## Non-goals

Directive contribution, KSP, ABI gate automation (owned by pipeline-kotlin S6.x).
