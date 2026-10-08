# M6 Implementation Plan

**Cycle:** `p-dd1a1c7a7d448b0c/m6-external-plugin` · **Phase:** plan

## Work units

- **WU-1 · SDK dependency bucket.** Root build: add `pipelinek-policy-plugin` to the
  module allowlist with SDK coords bucket (`dev.rubentxu.pipeline.v2:*`), settings
  include. Guard test stays green. Est: small.
- **WU-2 · Input/output/codec.** `PolicyCheckInput`, `PolicyCheckOutput`
  (TypedStepOutput with PASSED/VIOLATED/REFUSED verdicts), codecs.
  `PolicyCheckCodecTest` (02a, 02b). Est: small.
- **WU-3 · StepDefinition + handler.** Contract (atomic/controller/READ_ONLY/MEMOIZED),
  pure handler wiring decoder→verifyPacked→evaluate; enforcement via
  `PolicyCheckOutput.outcome`. `PolicyCheckHandlerTest` (01a, 01b, 03a-c). Est: medium.
- **WU-4 · Contributors + manifest + events.** `PolicyCheckContributor`
  (registrations with metadata), `PolicyEventContributor` + payload/codec,
  `PolicyPluginManifest`, META-INF/services. Tests: ServiceLoaderDiscoveryTest,
  PolicyEventContributorTest (05a/b), PolicyPluginManifestTest (06a). Est: medium.
- **WU-5 · DSL façade.** `StageScope.policyCheck(...)` lowering to registryStep.
  Compile-checked in module tests. Est: small.
- **WU-6 · Hard gate test.** `CoreZeroReferencesTest` scanning src/main outside plugin
  (09a). Est: small.
- **WU-7 · External build fixture.** `fixtures/policy-plugin-external` with own
  settings; publishes root artifacts to mavenLocal, builds plugin jar from published
  coordinates only (07a). Executed as part of UAT prep, not per-commit. Est: medium.
- **WU-8 · UAT acceptance (installed distribution).** `M6UatAcceptanceTest`: build
  plugin jar via fixture, run installed `pipelinek` 0.47.0 with `--plugin-jar` on
  pass + violate scripts (08a, 08b); assert event + enforcement. Est: large.
- **WU-9 · Docs + roadmap closure.** POLICY_PLUGIN.md usage guide; ROADMAP M6 entry.

## Ordering

WU-1 → WU-2 → WU-3 → (WU-4 ∥ WU-5) → WU-6 → WU-7 → WU-8 → WU-9.

## Verification

- Surgical: each WU ships its own test, run with `./gradle-jdk21.sh :module:test`.
- Integration gate: full `check` (all modules + detekt 0 issues) before
  `phase.build.complete` (gate implementation-complete).
- UAT: WU-8 executed against the REAL installed binary, evidence captured in receipts.
