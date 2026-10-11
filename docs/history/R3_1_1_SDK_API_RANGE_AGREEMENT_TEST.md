# R3.1.1 — SdkApiRangeAgreementTest: R3.1 live host load, in-repo as a runnable gate

**Date:** 2026-10-11
**Branch:** `integration/r1-alpha.2`
**Status:** **PASS** (4/4 tests, against both SDK 0.47.0 and SDK 0.48.0)
**Work item:** closes the R3.1 limitation "the probe lived in /tmp, R3.1.1 will move it to src/test/"

---

## What R3.1 left as a limitation

The R3.1 SDK 0.48.0 live host load was demonstrated by a `/tmp/R3_1_probe.java` binary that loaded the plugin against SDK 0.48.0 jars and called `ServiceLoader.load`. The probe was reproducible by re-running the same Java toolchain, but it was NOT in the repo: a fresh clone of the branch could not run that gate.

The R3.1 report explicitly listed this as a limitation and proposed R3.1.1 as "move the probe to `src/test/` so the gate is reproducible from the test suite".

## What R3.1.1 delivered

A new test class, `SdkApiRangeAgreementTest`, lives at
`pipelinek-policy-plugin/src/test/kotlin/com/pipelinek/policy/plugin/SdkApiRangeAgreementTest.kt`.

It contains **4 tests**, all passing:

```
SdkApiRangeAgreementTest > apiRange comparator rejects a version outside the declared range() PASSED
SdkApiRangeAgreementTest > plugin-manifest apiRange covers the resolved SDK version() PASSED
SdkApiRangeAgreementTest > plugin-manifest apiRange covers SDK 0 dot 48 dot 0 by contract() PASSED
SdkApiRangeAgreementTest > service loader discovers PolicyCheckContributor against the resolved SDK() PASSED
```

The same 4 tests also pass when run explicitly against SDK 0.48.0:
```
./gradlew :pipelinek-policy-plugin:test --tests "com.pipelinek.policy.plugin.SdkApiRangeAgreementTest" -PsdkVersion=0.48.0
```

## What each test exercises

| Test | What it pins | Why it matters |
|---|---|---|
| `plugin-manifest apiRange covers the resolved SDK version` | the v1 manifest's `apiRange` field covers whichever SDK the build resolved | a consumer admission gate reads `apiRange` and rejects if the resolved SDK is outside it; this test fails at the first divergence |
| `plugin-manifest apiRange covers SDK 0 dot 48 dot 0 by contract` | the apiRange covers SDK 0.48.0 specifically, regardless of what the build resolved | this is the R3.1 contractual answer, pinned in code (it does not need a 0.48.0 build to pass) |
| `service loader discovers PolicyCheckContributor against the resolved SDK` | `ServiceLoader.load(StepDefinitionContributor)` returns `PolicyCheckContributor` and exposes `PolicyCheckStepDefinition.KEY` | the runtime wiring works against the SDK the build actually resolved; this is the in-repo replacement for the R3.1 /tmp probe |
| `apiRange comparator rejects a version outside the declared range` (falsification) | the comparator is not a tautology: 0.99.0 must be rejected | satisfies AGENTS.md's mutation/negative-test rule and pins the comparator's behaviour |

## How the test detects the resolved SDK version

`StepDefinitionContributor::class.java.package.implementationVersion` — a single reflective read of the JAR the build resolved. The Gradle build sets this via
```kotlin
implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
```
where `sdkVersion` comes from `-PsdkVersion` (default `0.47.0`). The version propagates to each transitive jar's `Implementation-Version` MANIFEST entry. Reading it from a class package on the test classpath is:

- **honest**: a single reflective read, no SemVer library, no POM probing, no filesystem walking
- **fail-loud**: if the SDK is not on the classpath, the test fails to compile, not to run
- **independent of `-PsdkVersion`**: the test does not import `gradleProperty`; it just reads what the build resolved

This deliberately does NOT use the BOM POM path (`pipeline-sdk-bom-0.X.X.pom`). BOMs are `pom`-only artifacts; their POMs are not on the test classpath. The first attempt at this test probed the BOM POM and failed at runtime with "no POM on the classpath". The current approach avoids that entirely.

## The comparator

`apiRangeCovers(range, version)` parses a SemVer range of the form `[A.B.C, D.E.F)` and returns `lo ≤ v < hiExclusive` using a list-wise integer compare. It does not pull in a SemVer library because the value space is well-bounded (we know the supported SDK versions) and a hand-rolled comparator is one line:

```kotlin
fun cmp(a: List<Int>, b: List<Int>): Int = a.zip(b).fold(0) { acc, (x, y) -> if (acc != 0) acc else x - y }
```

The falsification test (`apiRange comparator rejects a version outside the declared range`) pins that 0.99.0 is NOT covered. If the comparator were rewritten to always return `true` (the kind of mutation the AGENTS.md falsification rule exists to catch), that test would fail.

## Evidence

1. `./gradlew :pipelinek-policy-plugin:test` → **65/65 tests pass** (4 new + 61 prior), según los 12 XML de suites de certify en `98807a2`.
2. Same command, with `-PsdkVersion=0.48.0` → **4/4 pass** in `SdkApiRangeAgreementTest`. Time: 3.5s.
3. The test file is 163 lines, all under `pipelinek-policy-plugin/src/test/`, all in the repo.
4. Certify en `98807a2` ejecutó 623 tests verdes. El SHA root-only permaneció `d5f2c2cb`: incluye `src/main/kotlin` y `src/test/kotlin` del módulo raíz, pero excluye todas las fuentes del módulo plugin.

## Honest limitations

- **Alcance limitado.** Estas pruebas verifican contrato y ServiceLoader en el classpath de tests. No equivalen a instalar el fat jar ni a ejecutar `policy.check` en un host completo. El wiring ya existía en `PolicyPluginWiringTest`. La aportación nueva es el acuerdo de apiRange con la versión observada.
- **Comparator is intentionally narrow.** It only handles the range notation the plugin's v1 manifest emits (`[A.B.C, D.E.F)`). If the manifest schema ever changes to a richer range syntax (e.g. `^0.48.0`, `~0.48.0`), this test will fail with "apiRange is not in the form [A.B.C, D.E.F)" and the comparator will need extending. That is a feature, not a bug: the test is a tripwire against manifest schema drift.
- **The plugin-manifest emission is not re-tested here.** `PluginVersionAgreementTest` already tests the manifest's measured release version, but does not assert the apiRange field. The new test is the only place that pins apiRange behaviour. If a future change drops the apiRange field from the manifest, the new test fails on the parse step; that is the intended tripwire.

## Closing the R3.1 limitation

The R3.1 report's limitation #3 ("SDK 0.48.0 NOT_MEASURED") was corrected in the PR description refresh to "R3.1 PASS, MEDIDO". This R3.1.1 commit goes one step further: the contractual test is now a real, runnable, in-repo gate, not a one-shot /tmp probe. The R3.1.1 number stays in the report's history; it is now a feature, not a TODO.
