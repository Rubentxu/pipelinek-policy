# H4.4 · Compatibility matrix alignment receipt

WorkItem (SDDK authority): `6fb5b421-1201-4773-b748-e6510da8b87b`
Cycle: `p-dd1a1c7a7d448b0c/b4-b5-b6-remediation`
Date: 2026-10-10
Baseline `HEAD` at start of this work: `d891d54`

## What the roadmap claimed, and what was actually true

The H4.4 row read **ABIERTO · "No existe matriz Policy × SDK × JDK"**. That claim
was not merely unfulfilled, it was **untestable**: `sdkVersion` in
`pipelinek-policy-plugin/build.gradle.kts` was a hardcoded literal, so the SDK axis
could not be varied at all. A matrix script could have been written, printed two SDK
versions, and every run would have silently measured the same one SDK while the
report claimed otherwise. That is the failure mode this receipt exists to rule out.

## Change

`val sdkVersion = "0.47.0"` became
`providers.gradleProperty("sdkVersion").getOrElse("0.47.0")`.

- **Default is unchanged.** A build that does not ask for another SDK resolves
  exactly what it resolved before.
- It is a Gradle property, not an env var, so the value used is visible in the
  build invocation and in whatever receipt runs it.

Verified differentially: `-PsdkVersion=0.48.0` resolves `pipeline-domain:0.48.0`,
not the default.

## Measured result

`scripts/m10/compat-matrix.sh`, 4 cells, every cell executed fresh:

| SDK | Test JDK | Executed | Verdict |
|---|---|---|---|
| 0.47.0 | 21 | tests=577 failures=0 errors=0 skipped=0 | PASS (reference) |
| 0.47.0 | 25 | tests=577 failures=0 errors=0 skipped=0 | PASS |
| 0.48.0 | 21 | tests=577 failures=0 errors=0 skipped=0 | PASS |
| 0.48.0 | 25 | tests=577 failures=0 errors=0 skipped=0 | PASS |

**PASS (4 of 4, identical executed suite on every one), script exit 0.**

The cells are compared on the **set of `classname#method` ids**, not on the count.
A count comparison would let a swapped or truncated suite pass with the same total.

## Falsifications performed

Each of these was observed, not reasoned about:

1. **Empty SDK directory** → `NOT_MEASURED`, 0/6 cells, exit 1. Never PASS. This was
   the first run's real behaviour, and it is why the "matrix executed nothing" guard
   exists.
2. **BOM sought as a JAR** → the first run wrongly marked all six cells
   `NOT_MEASURED`. The BOM ships as POM plus Gradle module metadata with no jar;
   requiring a jar tested an artifact whose absence means nothing. Guard now checks
   the POM, which is what Gradle actually reads for a `platform()` coordinate.
3. **JDK 24 wrongly included** → removed. It is installed, but
   `BootstrapSmokeTest` requires a certified LTS, so that row would have reported a
   FAIL about declared policy while being read as a compatibility result.
4. **Verdict prefix vs full string** → PASS accompanied by exit 1, because the script
   compared the whole verdict string instead of its prefix. Fixed.
5. **Stale claim in the B6.10 receipt** → see below.

## Defect found and fixed outside H4.4

Two real defects, both caused by the same mistake: treating a **declared constant**
as if it were a stable interface. Both were found by reading the regenerated receipts
rather than by a test failing.

### D1 · The B6.10 receipt asserted H4.4 was unbuilt

`scripts/m10/release-admission.sh` hardcoded:

```
4. H4.4 compatibility matrix Policy x SDK x JDK: not built.
```

That line became false the moment the matrix passed, and it would have shipped as a
false statement inside a release receipt. It is now **read** from
`docs/history/H4_COMPATIBILITY_MATRIX.md` rather than asserted:

| Report state | Receipt now says |
|---|---|
| verdict PASS | `PASS` + the explicit build/test-only limit |
| verdict FAIL | `FAIL` |
| verdict NOT_MEASURED | `NOT_MEASURED` |
| verdict unreadable | `verdict unreadable ...; treat as NOT_MEASURED` |
| report absent | `NOT_MEASURED (no matrix report at ...)` |

**Mutation**: a hardcoded replacement returns the same false "not built" string
whether the report says PASS or FAIL. Verified. The fixed block returns the correct
verdict in all five states.

### D2 · The B6.1 certification receipt silently lost the SDK version

Worse, because it degraded **silently and looked fine**. `scripts/m6/certify.sh`
resolved the SDK with:

```sh
sed -n 's/^val sdkVersion = "\(.*\)"/\1/p'
```

That matched the old literal. Once `sdkVersion` became a property, the regex matched
nothing, `SDK_VERSION` became the **empty string**, and the receipt printed:

```
- **integration SDK**: 0 artifacts at version
```

with the version field blank and the whole SDK digest block silently emptied. It did
not fail. It looked like a legitimate measurement of "no SDK artifacts", which is
exactly what makes it dangerous: B6.7 was built specifically so the receipt would
describe the SDK the plugin was compiled against, and this silently erased it.

Fixed to accept **both** declaration forms (literal and `getOrElse` property) and to
**refuse to emit a receipt at all** when the version cannot be resolved, rather than
printing a blank one. `SDK_VERSION_OVERRIDE` exists for the case where a run passed
`-PsdkVersion` that the script cannot otherwise see.

Falsified against the shipped block:

| `build.gradle.kts` declares | Result |
|---|---|
| property form | `0.47.0`, exit 0 |
| legacy literal form | `0.46.0`, exit 0 (no regression on the old shape) |
| property form without `getOrElse` | **empty → guard fires, exit 3**, no receipt |
| same, with `SDK_VERSION_OVERRIDE=0.48.0` | `0.48.0`, exit 0 |

The third row is the point: a matcher that cannot read the declaration now **stops**
instead of publishing a blank. Re-run after the fix: `sdk: 4 artifacts at 0.47.0`,
digest block restored.

## D3 · A blank `-PsdkVersion` silently broke the build

Found only by exercising the property's own edge cases after the fact, which is
exactly the check the first pass skipped.

`providers.gradleProperty("sdkVersion").getOrElse("0.47.0")` looks correct:
absent property, use the default. But `getOrElse` fires only when the property is
**absent**. `-PsdkVersion=` (an empty value, or a CI variable expanding to nothing)
leaves it **present and empty**, so the default is never used. Observed result:

```
+--- dev.rubentxu.pipeline.v2:pipeline-sdk-bom ->  FAILED
+--- dev.rubentxu.pipeline.v2:pipeline-domain FAILED
+--- dev.rubentxu.pipeline.v2:pipeline-events FAILED
+--- dev.rubentxu.pipeline.v2:pipeline-scripting-api FAILED
```

Every SDK coordinate failed while the task itself still exited 0, because
`dependencies` prints a tree even when resolution failed. A caller passing an empty
override got a confusing resolution failure several steps later instead of being
told what was wrong.

Fixed with an explicit configuration-time guard naming the default, plus `.trim()`
so a whitespace-only value is caught by the same check. Falsified on the real build:

| Invocation | Observed |
|---|---|
| no override | `pipeline-domain:0.47.0` (default intact) |
| `-PsdkVersion=0.48.0` | `pipeline-domain:0.48.0` |
| `-PsdkVersion=` | **rejected at configuration time** |
| `-PsdkVersion="  "` | **rejected at configuration time** |
| `-PsdkVersion=9.9.9` | resolution failure (SDK genuinely absent) |

This also changed the declaration shape to `DEFAULT_SDK_VERSION`, which
`certify.sh` had to be taught: its regex matched the `getOrElse` form and would
have gone empty, at which point the guard correctly aborted the run rather than
emitting a blank SDK version. That guard earned its keep immediately.

## D4 · The matrix axes could not be falsified

`MATRIX_SDKS=("0.47.0" "0.48.0")` was a literal, so the claim "the matrix reports
NOT_MEASURED for an absent SDK rather than PASS" could not be tested without
editing the script. Axes are now overridable via `MATRIX_SDKS_OVERRIDE` /
`MATRIX_JDKS_OVERRIDE`, empty meaning defaults, so shipped behaviour is unchanged.
Verified: `MATRIX_SDKS_OVERRIDE=9.9.9` produces

```
verdict: NOT_MEASURED (no cell executed; a matrix that ran nothing certifies nothing)
exit 1
```

with each missing artifact named. Never PASS.

## Acceptance evidence on the real public interfaces

Unit tests are a proxy. These are the paths a user or a host actually runs, all
exercised after the build change.

### The installed plugin inside a live PipelineK host

`scripts/m6/uat-external-distribution.sh` — the real acceptance path, rebuilt
distribution, plugin loaded via `--plugin-jar`:

| Step | Observed |
|---|---|
| SDK provenance | 4 artifacts at 0.47.0, digests matched |
| Distribution precondition | invalid command rejected `rc=1` (proved by behaviour, not `--help`) |
| 16 compliant resources | `PASS ok (exit 0, 16 successful checks, 0 error findings)` |
| 4 violating resources | `VIOLATE ok (exit 1, VIOLATED, PLUGIN failure, fail-fast after 1 step)` |

So the plugin does load in a real host and decide correctly in both directions.
This is what the earlier "a cell is not host evidence" caveat was protecting, and
it holds for the declared SDK only.

### The installed CLI, end to end

`installDist` launcher, observed exit codes against the documented matrix:

| Invocation | Observed exit |
|---|---|
| no args | 1 (usage printed) |
| unknown command | 1 |
| `--json-help` | 0, all 9 commands, exit-code legend matches `ExitCodes` |
| `--version` | 1, `unknown command: --version` |

`--version` is not implemented; it is not documented either, so this is a gap in
the surface rather than a contradiction. Recorded as **N/A** rather than quietly
ignored.

Full policy round trip through the real binary:

| Step | Observed |
|---|---|
| `compile` valid IR | exit 0, `bundleSha256` 571 bytes |
| `compile` IR missing `irVersion` | exit 5 (compiler error), as documented |
| `inspect` compiled bundle | exit 0, IR round-trips identically |
| `check` against compiled bundle | exit 0 on a satisfied rule |
| `check --policy <source.json>` | exit 4, `bundle refused: invalid pack magic` |

The last row is the CLI refusing to treat policy source as a compiled bundle, which
is the admission boundary behaving correctly rather than a defect.

## Observed limit: the Gradle launcher JDK

`./gradlew check` **fails in this environment** with:

```
java.lang.IllegalArgumentException: 25.0.4.1
  at ...JavaVersion.parse
  at org.jetbrains.kotlin.cli.jvm.modules...
```

This is the ambient JDK `25.0.4.1`. It fails at **configuration time**, before any
task runs — even `./gradlew help` fails. It is **not caused by this work**: it was
reproduced on a pristine tree via `git stash`. The repo ships `gradle-jdk21.sh`
precisely for this, and all gates here were run through it.

The matrix therefore measures the **test toolchain** (`jvmToolchain` via
`-PtestJvm`), with the Gradle launcher pinned to JDK 21 in every cell. A cell is
evidence that the plugin **compiles and tests** under that JDK. It is not evidence
that the whole toolchain bootstraps on it. Both the matrix report and the ROADMAP row
state this rather than leaving the reader to assume it.

## Gates, all from this tree

| Gate | Result |
|---|---|
| `compat-matrix.sh` | 4/4 PASS, exit 0 |
| `./gradle-jdk21.sh check` | BUILD SUCCESSFUL |
| `certify.sh` | 577 tests, 0 failures, 0 errors, 0 skipped; source sha `44c6fb71…`, 87 files |
| `mutation-gate.sh` | 17 killed, 0 survived, 0 invalid; restore green (`Evaluator.kt` clean afterwards) |

## Verdict on the roadmap row

**PARCIALMENTE MEDIDA**, not CERRADO, and not because local work remains.

What is established: for the certified JDK set (21, 25) and the two SDKs present in
`mavenLocal` (0.47.0, 0.48.0), the plugin builds and its full suite passes
identically.

What is **not** established:

1. Policy content combinations are not enumerated.
2. A cell is **not** host evidence. B6.7 exercises the installed plugin against the
   declared SDK only. Installed-plugin UAT on any other SDK is `NOT_MEASURED`.
3. The Gradle launcher is not exercised on JDK 25 (see above); it cannot be, today.
4. `0.40.0-dev.2` is **not measurable** and is therefore excluded rather than
   reported as a failure. `mavenLocal` holds only partial artifacts for it
   (`pipeline-domain/0.40.0-dev.2`, `pipeline-scripting-api/0.40.0-dev.2`) and
   **no `pipeline-sdk-bom/0.40.0-dev.2` at all**, so the build has no platform to
   resolve against. `pipeline-sdk-bom` contains exactly `0.47.0` and `0.48.0`.
5. Host evidence exists for the **declared** SDK only. The UAT pins 0.47.0 by
   digest, so the plugin loading in a live host on **0.48.0** remains
   `NOT_MEASURED`. The 0.48.0 cells are build-and-test only.
6. The CLI has no `--version` flag. Observed exit 1, `unknown command`. It is not
   documented, so this is **N/A** to the acceptance contract rather than a failure.

## Requirement trace

Each changed public output mapped to a concrete check and observed result, rather
than to an aggregate test count.

| Changed output | Concrete check | Observed |
|---|---|---|
| `sdkVersion` default unchanged | resolve with no override | `pipeline-domain:0.47.0` |
| `sdkVersion` override works | resolve with `-PsdkVersion=0.48.0` | `pipeline-domain:0.48.0` |
| blank override rejected | resolve with `-PsdkVersion=` | rejected at config time |
| matrix measures 4 real cells | `compat-matrix.sh` | 4/4 PASS, exit 0 |
| matrix refuses PASS on nothing | `MATRIX_SDKS_OVERRIDE=9.9.9` | NOT_MEASURED, exit 1 |
| matrix cells are the same suite | id-set diff per cell | identical, 577 each |
| plugin loads in a real host | `uat-external-distribution.sh` | 16 pass / 4 violate |
| CLI exit-code contract | installed launcher, 5 invocations | matches documented matrix |
| policy round trip | compile → inspect → check | exit 0 throughout |
| admission receipt H4.4 line | run with report PASS / FAIL / absent | matches the real verdict in each |
| certification SDK version | `certify.sh` SDK parse + guard | `4 artifacts at 0.47.0`, exit 3 if unreadable |

An observation is not a support promise, and the row now says so.
