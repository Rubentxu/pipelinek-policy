# ADR-0010 — M1 preflight: build/lint posture and re-pinning

- Status: ACCEPTED (M1 preflight)
- Cycle: p-dd1a1c7a7d448b0c/m1-value-kernel
- Date: 2026-10-07
- Deciders: orchestrator (sddk-apply)
- Source spec: cycle-artifacts/.../m1-value-kernel/spec.md §M1 preflight (INC-001..004)

## Context

Spec REQ §M1 preflight demands atomic closure of four pre-existing incidents
(INC-001..004) in the **first M1 domain commit**, before any `ValueNode` /
`Policy` / `Rule` / `RuleEvaluation` code lands. Concretely:

- **INC-001**: `M0_BOOTSTRAP_RECEIPT.md` carries two referents under one
  `baseline_sha` key — the original receipt header (`a4390f3` = first build)
  and the spec/cycle-artifacts expectation (`62dc4a0e` = build-fix baseline).
- **INC-002**: `libs.jetbrains.annotations` (`org.jetbrains:annotations`) is in
  the deps block and the production allowlist, but no M1 domain code needs it
  (the evaluator forbids I/O per architectural law 5 and is otherwise pure
  stdlib).
- **INC-003**: cold-cache re-verify of Kotlin 2.4.20 + Gradle wrapper 8.14.5
  is required, and any re-pinning decision must be recorded here.
- **INC-004**: exactly one of `ktlint` or `detekt` must be wired into
  `:check` **before** the first domain slice lands; strict-TDD re-detection
  must be enabled.

## Decisions

### D1.1 INC-001 — rename `baseline_sha` and realign

Replace the single ambiguous key in `docs/history/M0_BOOTSTRAP_RECEIPT.md`
with two unambiguous keys:

```yaml
first_build_sha: a4390f35789d2bf1b8c19759b3489a4d0680daa1   # first build w/ guard
baseline_sha:     62dc4a0e34e93101a70393d25868351f7e039b85   # cycle-artifacts baseline
```

`first_build_sha` preserves the historical fact (a4390f3 was the original
`base_sha` of the receipt); `baseline_sha` carries the spec/cycle-artifacts
expectation that the **build-fix commit** is the real baseline for downstream
M1 work (per `M0_CHARACTERIZATION.md` and `inventory.json`).

### D1.2 INC-002 — drop `libs.jetbrains.annotations` entirely

Remove:

- the `implementation(libs.jetbrains.annotations)` line from `dependencies`;
- the `org.jetbrains:annotations` entry from `gradle/libs.versions.toml`;
- the `org.jetbrains:annotations` coord from `allowedCoords` in
  `build.gradle.kts`.

Rationale:

- The evaluator is pure (architectural law 5) and Kotlin 2.4.20's stdlib
  transitively re-exports the JSR-305 / nullability annotations; an explicit
  `org.jetbrains:annotations` coord is dead weight.
- The production allowlist must list only what the domain code actually
  resolves; a stale annotation coord widens the allowlist unnecessarily.

### D1.3 INC-003 — cold-cache re-verify + no re-pinning

Observed (evidence under
`/home/rubentxu/.local/share/sddk/evidence/p-dd1a1c7a7d448b0c/m1-value-kernel/probes/inc-003-*.{log,txt}`):

- Cold `.gradle/` + `build/` removed.
- `./gradlew check --no-daemon --no-build-cache` → BUILD SUCCESSFUL in 19s.
- Wrapper bootstrap: Gradle 8.14.5 (revision `62345becae08...`), Launcher JVM
  21.0.8 LTS, Kotlin Gradle Plugin DSL `2.0.21` (Gradle's own DSL runner — the
  *project* uses Kotlin 2.4.20 from `libs.versions.toml`).
- `BootstrapSmokeTest` 2/2 PASSED; `architectureFitnessGuard OK`.

**No re-pinning required.** Kotlin 2.4.20 + JDK 21 toolchain resolve cleanly
from cold cache; the dependency tree is reproducible.

### D1.4 INC-004 — wire `detekt` (not `ktlint`) into `:check`

Choose `detekt` 2.0.0-alpha.6 because:

- The local Gradle cache already contains
  `dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6` and all of its transitive
  runtime. `ktlint` (and the `org.gradle.jacoco`/`com.github.ktlint`
  marker-pom coordinate) is **not** in the local cache; wiring it would force
  a network resolution that the cold-cache preflight (D1.3) explicitly avoids.
- detekt covers the same lint surface as ktlint for an M1 kernel: naming,
  complexity, comments, potential bugs, style, exceptions, performance, and
  empty blocks. The spec says "exactly one of `ktlint` OR `detekt`".
- detekt's Gradle DSL is self-contained (`plugins { alias(libs.plugins.detekt)
  }`); it does not enter `compileClasspath`/`runtimeClasspath`, so the M0
  production allowlist guard remains satisfied.

Wiring details:

- `plugins { alias(libs.plugins.kotlin.jvm) alias(libs.plugins.detekt) }`.
- `detekt { config.setFrom(files("config/detekt/detekt.yml")); ... }`.
- `tasks.withType<dev.detekt.gradle.Detekt>().configureEach { jvmTarget = "21" }`.
- `tasks.named("check") { ... dependsOn(tasks.withType<dev.detekt.gradle.Detekt>()) }`.
- `config/detekt/detekt.yml`: minimal config tightening complexity, naming,
  potential-bugs and style rules; `build.maxIssues: 0` so any finding fails
  `:check`.

### D1.5 strict-TDD re-detection

Strict TDD mode is already the orchestrator's choice for this slice
(`strict_tdd_mode: true`). The runner is the standard JUnit Platform 5 runner
the smoke test already exercises; the strict-TDD cycle reads/writes via the same
`./gradlew check` task. **No re-detection flag required** — `apply-strict-tdd.md`
already binds its RED/GREEN/TRIANGULATE/REFACTOR cadence to JUnit 5 via the
existing `:test` task that `compileTestKotlin` depends on.

If a future cycle introduces a runner swap (e.g. kotest), this section must
be re-opened and the strict-TDD loop must re-detect.

## Consequences

- `allowedCoords` shrinks to a single production coord; the architecture
  fitness guard becomes tighter, not looser — any future `org.jetbrains:*`
  production dep other than `kotlin-stdlib` will fail the guard at config
  time.
- `config/detekt/detekt.yml` becomes part of the lint contract: any later
  cycle that wants to soften a rule must update this file **and** record
  the change here.
- The preflight commit carries no domain code (zero src files changed),
  preserving the spec's "atomic closure of INC-001..004 before any value/policy
  code" ordering invariant.

## Out of scope

- Re-pinning Kotlin, JDK, or Gradle (none required by D1.3).
- Switching from detekt to ktlint (ktlint is not in the local cache; revisit
  if/when ktlint lands in `~/.gradle/caches/`).
- Wiring `detekt-formatting` (extra formatting rule set); M1 kernel is small
  enough that the default rule set + complexity-naming rules are sufficient.