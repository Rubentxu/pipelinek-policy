---
cycle: p-dd1a1c7a7d448b0c/m0-bootstrap
phase: apply
work_item: 12788213-822a-428f-8470-c9ca58106019
m0_status: INCOMPLETE · BLOCKED-fixture-license
parent_sha: fca1ff3aba812230f783f67fc21861e55e6f566b
build_sha: a4390f35789d2bf1b8c19759b3489a4d0680daa1
recorded: 2026-10-07T17:40Z
---

# M0 Bootstrap Receipt

## M0 INCOMPLETE — BLOCKED-fixture-license

M0 remains **INCOMPLETE**. Legacy `framework_modular` fixtures
(`build/resources/test/policies/*` including `personal.csv`) cannot
be imported because the original fixture tree ships without a
`LICENSE` file and includes `personal.csv` (personally identifying
data). Until the fixture is relicensed or replaced, M0 only ships
the build skeleton, documentation, and the architecture fitness guard.
No production package depends on PipelineK yet; the bootstrap is
deliberately greenfield-only.

The full M0 deliverable list (see `ROADMAP.md` §M0) is therefore
partially complete:

- ✅ Gradle Kotlin/JVM skeleton
- ✅ Kotlin 2.4.20 pin
- ✅ JDK 21 toolchain
- ✅ SDDK wiring (state out of repo)
- ✅ ROADMAP as single sequencing authority
- ✅ ADR-0001..0009 carried over from blueprint
- ❌ fixtures imported/copied-as-tests from `framework_modular`
- ✅ characterization document (DOCUMENTED sources only, see
  `M0_CHARACTERIZATION.md`)
- ✅ baseline build/test local — no GitHub Actions as CI authority

## Pinned SHAs

| Role | SHA |
|---|---|
| Baseline (parent of receipt; commit fca1ff3) | `fca1ff3aba812230f783f67fc21861e55e6f566b` |
| Build + guard commit (this receipt's parent) | `a4390f35789d2bf1b8c19759b3489a4d0680daa1` |

The pinned SHA for the receipt is `a4390f3...` (the commit immediately
preceding the receipt). It is the SHA that satisfies all M0 §Build
deliverables: green `./gradlew check` plus documented red/green
guard probe.

## Reproducible commands

All four commands are recorded verbatim. The first three are the
acceptance evidence; the fourth is the binary-hash step.

1. `./gradlew wrapper --gradle-version 8.14.5 --distribution-type bin`
   Materialized `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
   `gradle/wrapper/gradle-wrapper.properties` (with
   `distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854`).
2. `JAVA_HOME=$JAVA_HOME ./gradlew check --no-daemon`
   Exits 0. Toolchain resolves JDK 21.0.8 (Temurin 21.0.8+9-LTS).
   `BootstrapSmokeTest` runs 2 tests, both PASSED:
   - `JVM runtime is JDK 21`
   - `Kotlin runtime matches declared 2_4_20`
   `architectureFitnessGuard` runs as part of `check` and logs
   `architectureFitnessGuard OK`.
3. `git rev-parse HEAD` (run after the build commit, before this
   receipt commit) returned `a4390f35789d2bf1b8c19759b3489a4d0680daa1`.
4. `sha256sum` of the build artifacts:

| Path | SHA256 |
|---|---|
| `gradle/wrapper/gradle-wrapper.jar` | `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` |
| `build.gradle.kts` | `6759c1a7c3e94d5b0d90516e1e8b8955b0c8d1a465ab9e1bc2f8f5df96d053d1` |
| `settings.gradle.kts` | `2eed6f3225143f95461b65140fd1ed0e06d7eb97187aa57de8e322d20e86d73f` |
| `gradle/libs.versions.toml` | `0b5d68b5252941e92932bbc82c6823d09b4aa9f38bc04f393247e41c862245d6` |
| `src/test/kotlin/com/pipelinek/policy/bootstrap/BootstrapSmokeTest.kt` | `cf71adf97824d6cfc6bf0af4e2b6fa218d63bed697bfbd95b399c6401646cb98` |

The wrapper JAR SHA matches the official Gradle 8.14.5 reference
at <https://gradle.org/release-checksums/>:

```
8.14.5
  Binary-only (-bin) ZIP Checksum: 6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854
  Wrapper JAR Checksum:           7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172
```

## Architecture fitness guard evidence

### GREEN baseline (baseline `./gradlew check`)

```
> Task :architectureFitnessGuard
architectureFitnessGuard OK

> Task :test
BootstrapSmokeTest > JVM runtime is JDK 21() PASSED
BootstrapSmokeTest > Kotlin runtime matches declared 2_4_20() PASSED
> Task :check
BUILD SUCCESSFUL in 9s
```

### RED probe (negative test for the guard)

In `build.gradle.kts`, temporarily added inside the `dependencies { }`
block (then removed):

```kotlin
implementation("com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT")
```

Then `./gradlew check --no-daemon --offline` produced:

```
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring root project 'pipelinek-policy'.
> architectureFitnessGuard FAIL (declared)
    buildFile: /var/home/rubentxu/Proyectos/kotlin/PoliciesPlugin/build.gradle.kts
    coords:   compileClasspath:com.pipelinek:plugin-sdk
    runtimeClasspath:com.pipelinek:plugin-sdk
  Reason: production allowlist is exactly org.jetbrains.kotlin:kotlin-stdlib, org.jetbrains:annotations; non-external deps are fail-closed.
```

Crucially, the message originates from the **`afterEvaluate` configuration-time
hook** — the failure happens before Gradle resolves dependencies and
therefore before any network request is attempted. Typos and reserved
groups surface as configuration errors, not network errors, as
required by the spec.

After removing the probe line, `./gradlew check` returned to green.

## Why M0 is INCOMPLETE

The `framework_modular` blueprint directory preserved in the repo
contains a `build/resources/test/policies/` tree, including a
`personal.csv` file with personally identifying data, and no LICENSE
file. Until that fixture is replaced (or relicensed) and the legacy
import path is re-enabled, M0 is intentionally closed at "skeleton
+ guard" only. See `M0_CHARACTERIZATION.md` for the research notes
on the legacy system (DOCUMENTED only — no path under
`build/resources/test/policies/` is referenced).

## Local validation checklist

- [x] `./gradlew check` exits 0
- [x] Exactly 1 test class (`BootstrapSmokeTest`)
- [x] No `docs/ROADMAP.md` (ROADMAP at root only)
- [x] `AGENTS.md` byte-identical to blueprint
- [x] `README.md` quickstart section complete
- [x] Wrapper JAR SHA matches official reference
- [x] `architectureFitnessGuard` red/green evidence recorded above
- [x] `.gitignore` matches both `pipelinek-policy-blueprint/` and
      `pipelinek-policy-blueprint (1).zip`
- [x] No GitHub Actions / `.github/workflows/` (M0 prohibits)
- [x] No external tag / release (M0 prohibits while blocker open)
- [x] No push to remote (orchestrator owns publication)

## Cycle handoff

The slice's commit graph is:

```
fca1ff3  docs(M0): promote blueprint tree to repo root
a4390f3  build(M0): Gradle Kotlin/JVM skeleton + architecture fitness guard
<this>   docs(history): M0 bootstrap receipt + characterization
```

This is the receipt commit. It contains only `docs/history/M0_BOOTSTRAP_RECEIPT.md`
and `docs/history/M0_CHARACTERIZATION.md`; no product code.

## References

- Spec: `cycle-artifacts/m0-bootstrap/spec/spec.md` (REQs honored)
- Tasks: `cycle-artifacts/m0-bootstrap/tasks/tasks.md`
- ROADMAP: `ROADMAP.md` §Estado inicial / §M0 / §M1
- ADRs: `docs/04-adrs/ADR-0001..0009`
- Characterization: `docs/history/M0_CHARACTERIZATION.md`