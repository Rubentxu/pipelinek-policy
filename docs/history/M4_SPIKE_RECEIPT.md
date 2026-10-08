# M4.A — Spike property synthesis: WU-0 offline cache probe

> Cycle `p-dd1a1c7a7d448b0c/m4-fir-authoring` (A-full, phase=`build`).
> Branch: `m4-fir-spike` (off `main` at HEAD `6b6d9f5`).
> Tasks anchor: tasks.md §"Phase 1 — Foundation" 1.1–1.5.
> Authored: 2026-10-08.

## 1. Probe results

The local Gradle cache (`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/`) holds the required Kotlin compiler artefacts. `com.tschuchort.compiletesting` is not cached and was not added.

| Artefact | Version | SHA-256 | Probe |
|---|---|---|---|
| `kotlin-compiler-embeddable` | 2.4.10 | `9309638a2ee03e6bde9ef4b7444055a94b84ab906563675d71ff9aecb64da913` | `K2JVMCompiler` exposes `public static void main` |
| `kotlin-scripting-compiler-embeddable` | 2.4.10 | `156c68ff816251c9d297c90d77e7e8e513eaad594277cee50a36faeddbe88935` | scripting registrar hooks present; legacy `JvmScriptCompiler` absent |
| `kotlin-scripting-jvm` | 2.4.10 | `0b7518219da69b427e0b3f840f13e280db9e61a839c7d3ff094de4630ddbc9f5` | `BasicJvmScriptEvaluator` / `JvmScriptCompilationKt` present |
| `kotlin-scripting-jvm-host` | 2.4.10 | `0a69931e502f547e68e9e53d0508dd3ead3b9f8aaae1238a4808ba0c065f03f2` | present |

## 2. Pin decision

The spike pins its compiler lane to 2.4.10 to remain offline. The root project remains on its existing Kotlin version.

## 3. Reproducible commands

```bash
K2JAR=/home/rubentxu/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10/cac44a5a360313427c693a9de295017bc76d6e4e/kotlin-compiler-embeddable-2.4.10.jar
javap -classpath "$K2JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler | grep 'public static final void main'

SCRJAR=/home/rubentxu/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-scripting-compiler-embeddable/2.4.10/6c59d47caf98d7926d8353b1b50ed15594986ce0/kotlin-scripting-compiler-embeddable-2.4.10.jar
unzip -l "$SCRJAR" | grep -E 'FirScriptingCompilerExtensionRegistrar|JvmCliScriptEvaluationExtension'
```

## 4. WU-0/WU-1 status

WU-0 PASS and WU-1 PASS were committed before this implementation block.

## 5. WU-2/WU-4 gate receipt

The per-gate evidence contract is persisted in
`docs/history/M4_GATE_EVIDENCE_CONTRACT.json`. Each entry contains the exact
command, output SHA-256, log path, input fingerprint and explicit outcome.

Implementation date: 2026-10-08. HEAD before this block: `cc259732cd6b0df5d67952af28328373abb12d19`.

**Verdict: controlled FAIL / Option C.** K2JVMCompiler 2.4.10 is reachable by reflection and exposes `main(String[])`, but the cached 2.4.10 `ExtensionStorage` ABI does not expose the expected unary-plus registration overload for the proposed provider shape. The cached scripting compiler does not expose `kotlin.scripting.jvmhost.JvmScriptCompiler`, and the offline IDE baseline cannot exercise IntelliJ binary diagnostics. Option A is therefore not declared green.

The method formerly named `compileAndLoadRules` is now
`compileAndLoadSyntheticRules`. It invokes K2 only for reachability and uses a
Regex-based synthetic lowering bridge for the narrow test syntax. It is not a
real FIR implementation and is not represented as one in the evidence.

| Gate | Result | Evidence |
|---|---|---|
| 1 canonical IR parity | PASS synthetic | `M4SpikeGatesTest`; FIR-sugar and explicit ADT digests equal; mislowering mutation differs |
| 2 evaluator parity | PASS structural | equal typed `FieldRef` for `anything.whatever` |
| 3 IDE baseline | PARTIAL | K2 reflection probe passes; limitation `IntelliJ-binary-not-exercised-offline` |
| 4 incremental compile | PASS synthetic | unrelated class SHA-256 remains stable after source edit |
| 5 `.kts` | FAIL | `JvmScriptCompiler` absent from cached scripting ABI |

Reproducible test command:

```text
./gradle-jdk21.sh :policy-fir-plugin:test :test --offline --no-daemon
```

Observed result: exit 0.

## 6. Fallback and limitations

Option C keeps the explicit missing-aware AST path:
`root().optionalField("anything").optionalField("whatever").asText()`.
No core parser or runtime dependency was added. Gate 3 is not equivalent to an IntelliJ run. ADR-0012 is intentionally not created because all five gates did not PASS.

## 7. Branch state

```text
branch m4-fir-spike (off main@6b6d9f5)
origin/main SHA: 6b6d9f5
no push, no merge
```
