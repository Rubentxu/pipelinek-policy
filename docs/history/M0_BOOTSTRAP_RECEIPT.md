---
cycle: p-dd1a1c7a7d448b0c/m0-bootstrap
phase: apply
work_item: 12788213-822a-428f-8470-c9ca58106019
m0_status: INCOMPLETE · BLOCKED-fixture-license
parent_sha: 62dc4a0e34e93101a70393d25868351f7e039b85
baseline_sha: a4390f35789d2bf1b8c19759b3489a4d0680daa1
docs_promotion_sha: fca1ff3aba812230f783f67fc21861e55e6f566b
recorded: 2026-10-07T17:45Z
sdki_workitem: 12788213-822a-428f-8470-c9ca58106019
---

# M0 Bootstrap Receipt

## M0 INCOMPLETE — BLOCKED-fixture-license

M0 remains **INCOMPLETE**. The legacy host repository
`/var/mnt/DiscoChino2-fast/Proyectos/giss/framework_modular` (an
external path on the developer machine, NOT inside this workspace and
NOT inside the blueprint directory) ships test resources at
`modules/policies/build/resources/test/policies/` that include
`inputs/personal.csv` (potential privacy concerns — content not
inspected for PII classification) together with rule YAMLs. The
legacy host repo has no `LICENSE` file. Until the legacy fixtures
are relicensed or replaced with synthetic fixtures, M0 cannot close.

This M0 cycle therefore delivers the build skeleton, the
documentation promotion, and the architecture fitness guard — but
**not** the fixture import. ROADMAP.md M0 row stays
`IN_PROGRESS · BLOCKED-fixture-license` and M1 stays
`BLOCKED-BY-M0` until the fixture license/PII review closes.

The full M0 deliverable list (see `ROADMAP.md` §M0) is therefore
partially complete:

- ✅ Gradle Kotlin/JVM skeleton (Kotlin 2.4.20, JDK 21 toolchain)
- ✅ Single smoke test asserting runtime JVM == 21 and Kotlin == 2.4.20
- ✅ Architecture fitness guard (declared-dep + reserved-module-dir + resolved-component hooks)
- ✅ ROADMAP.md as single sequencing authority (state: M0 IN_PROGRESS, M1 BLOCKED-BY-M0)
- ✅ ADR-0001..0009 carried over from blueprint (initial accepted status)
- ✅ Negative probes captured (Probe A: com.pipelinek:plugin-sdk refused at config time; Probe B: pipelinek-policy-plugin/ refused at task execution)
- ❌ fixtures imported/copied-as-tests from `framework_modular` (BLOCKED — license + PII review)
- ✅ Characterization document (DOCUMENTED sources only, see `M0_CHARACTERIZATION.md`)
- ✅ baseline local build/test green — no GitHub Actions / external tags / releases as CI authority

## Pinned SHAs

| Role | SHA |
|---|---|
| Docs promotion commit (root of the four-commit history) | `fca1ff3aba812230f783f67fc21861e55e6f566b` |
| First build + guard commit | `a4390f35789d2bf1b8c19759b3489a4d0680daa1` |
| Receipt v1 (parent_sha mis-targeted fca1ff3 — superseded) | `1ab8f4c8d66ceb1d3aea46b3250c913f2951465c` |
| Build-fix (compact 94-line guard, JDK21 green) | `62dc4a0e34e93101a70393d25868351f7e039b85` |
| **Parent of THIS receipt (real baseline)** | `62dc4a0e34e93101a70393d25868351f7e039b85` |

The receipt is pinned to `62dc4a0...` (the build-fix commit
immediately preceding this receipt commit) — NOT self-referential.

## Reproducible commands (verbatim, executed against this receipt)

```bash
# (1) Wrapper bootstrap (already committed; here for reproducibility)
./gradlew wrapper --gradle-version 8.14.5 --distribution-type bin

# (2) Green check (this receipt's parent_sha=62dc4a0)
JAVA_HOME=/home/rubentxu/.asdf/installs/java/temurin-21.0.8+9.0.LTS \
  ./gradlew check --no-daemon --no-build-cache

# (3) HEAD pin
git rev-parse HEAD

# (4) sha256 of build artefacts
sha256sum build.gradle.kts settings.gradle.kts gradle/libs.versions.toml \
          gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties \
          src/test/kotlin/com/pipelinek/policy/bootstrap/BootstrapSmokeTest.kt
```

## Observed command outcomes (XDG evidence)

Evidence paths under `/home/rubentxu/.local/share/sddk/evidence/p-dd1a1c7a7d448b0c/m0-bootstrap/`:

| File | Contents |
|---|---|
| `probes/probe-a-stderr.txt` | Probe A stderr: `architectureFitnessGuard FAIL (declared) coords: - compileClasspath:com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT / - runtimeClasspath:com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT` (guard caught reserved group BEFORE Maven 404) |
| `probes/probe-a-stdout.txt` | Probe A stdout (Gradle banner + daemon fork) |
| `probes/probe-a-exit.txt` | `1` (build failed as expected) |
| `probes/probe-a-init-script.sha256` | `920a7ee8...` sha256 of the init-script used for probe A |
| `probes/probe-b-stderr.txt` | Probe B stderr: `architectureFitnessGuard FAIL reserved module dir present: /var/home/rubentxu/Proyectos/kotlin/PoliciesPlugin/pipelinek-policy-plugin` |
| `probes/probe-b-stdout.txt` | Probe B stdout |
| `probes/probe-b-exit.txt` | `1` (build failed as expected) |
| `probes/probe-b-marker-count.txt` | `11` bytes count (probe marker file this session created) |
| `probes/post-restore-check.log` | Full green restore: `architectureFitnessGuard OK`, `BootstrapSmokeTest > JVM runtime is JDK 21() PASSED`, `BootstrapSmokeTest > Kotlin runtime matches declared 2_4_20() PASSED`, `BUILD SUCCESSFUL in 18s` |
| `probes/post-restore-test-results.xml` | JUnit XML: `tests="2" skipped="0" failures="0" errors="0"` |
| `probes/post-restore.sha256` | sha256 of build artefacts (see below) |

### Post-restore sha256 (probe A + probe B cleanly removed)

```
67cfe0a6c3768f096b8dcd360b6415a34a1e346a70c72cd7e715d36115f48d8a  build.gradle.kts
2eed6f3225143f95461b65140fd1ed0e06d7eb97187aa57de8e322d20e86d73f  settings.gradle.kts
0b5d68b5252941e92932bbc82c6823d09b4aa9f38bc04f393247e41c862245d6  gradle/libs.versions.toml
7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172  gradle/wrapper/gradle-wrapper.jar
242d7eeb2236de06e7d40b2dc4b2e33fb9eec0cb2884b6dd81daec0cf683c3ca  gradle/wrapper/gradle-wrapper.properties
cf71adf97824d6cfc6bf0af4e2b6fa218d63bed697bfbd95b399c6401646cb98  src/test/kotlin/com/pipelinek/policy/bootstrap/BootstrapSmokeTest.kt
```

The wrapper jar sha256 `7d3a4ac4...` matches the official Gradle 8.14.5
wrapper jar (regenerated from
`/home/rubentxu/.asdf/installs/gradle/8.14.5/bin/gradle wrapper
--gradle-version 8.14.5 --distribution-type bin` in a scratch dir;
distribution zip SHA256 `6f74b601...` per
`gradle/wrapper/gradle-wrapper.properties` `distributionSha256Sum`).

### Negative probes — summary

| Probe | Mechanism | Expected | Observed |
|---|---|---|---|
| A — `com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT` injected via Gradle init-script (the `implementation` configuration inherits the dep) | config-time `afterEvaluate` hook refuses non-allowlisted production dep BEFORE Gradle hits the network | Guard fails with `FAIL (declared)` naming the coord; exit 1; NO Maven 404 | ✅ match: `coords: - compileClasspath:com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT / - runtimeClasspath:com.pipelinek:plugin-sdk:0.1.0-SNAPSHOT`, exit 1 |
| B — `pipelinek-policy-plugin/` directory created in repo root | task-level `doLast` hook refuses the reserved module directory | Guard fails with `FAIL reserved module dir present:` naming the absolute path; exit 1 | ✅ match: `reserved module dir present: /var/home/rubentxu/Proyectos/kotlin/PoliciesPlugin/pipelinek-policy-plugin`, exit 1 |

Probe cleanup: only this session's marker file
`pipelinek-policy-plugin/marker.txt` + the empty probe directory were
removed (narrow `rm` + `rmdir`). No user files were touched.

## Out-of-scope (intentionally NOT done)

- M1 domain code (`policy-core/`, `ValueNode`, selector DSL, evaluator, IR, decoder SPI).
- Importing legacy fixtures (`build/resources/test/policies/*`) — blocked by license + privacy/PII review.
- External tags / GitHub releases / `.github/workflows/` CI (prohibited while blocker is open).
- ktlint / detekt / kover (out of M0 per spec).
- Multi-module Gradle layout (single root module by design).