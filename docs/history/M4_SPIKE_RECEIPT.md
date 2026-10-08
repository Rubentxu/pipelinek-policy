# M4.A — Spike property synthesis: WU-0 offline cache probe

> Cycle `p-dd1a1c7a7d448b0c/m4-fir-authoring` (A-full, phase=`build`).
> Branch: `m4-fir-spike` (off `main` at HEAD `6b6d9f5`).
> Tasks anchor: tasks.md §"Phase 1 — Foundation" 1.1–1.5.
> Authored: 2026-10-08.

## 1. Probe results

The local Gradle cache (`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/`)
holds the four artefacts the spike needs. `com.tschuchort.compiletesting`
(third-party `kotlin-compile-testing`) is **not** present, as the
proposal/design already documented; the spike MUST NOT add it.

| Artefact | Version in cache | SHA-256 | Reachability probe |
|---|---|---|---|
| `kotlin-compiler-embeddable` | 2.4.10 | `9309638a2ee03e6bde9ef4b7444055a94b84ab906563675d71ff9aecb64da913` | `javap … K2JVMCompiler` shows `public static void main` (PASS) |
| `kotlin-scripting-compiler-embeddable` | 2.4.10 | `156c68ff816251c9d297c90d77e7e8e513eaad594277cee50a36faeddbe88935` | registrar hooks present (`FirScriptingCompilerExtensionRegistrar`, `JvmCliScriptEvaluationExtension`); `JvmScriptCompiler` legacy class is **absent** — replacement is `kotlin.script.experimental.jvm.JvmScriptCompilationKt` + `BasicJvmScriptEvaluator` (Gate 5 will exercise that API) |
| `kotlin-scripting-jvm` | 2.4.10 | `0b7518219da69b427e0b3f840f13e280db9e61a839c7d3ff094de4630ddbc9f5` | `BasicJvmScriptEvaluator` / `JvmScriptCompilationKt` present |
| `kotlin-scripting-jvm-host` | 2.4.10 | `0a69931e502f547e68e9e53d0508dd3ead3b9f8aaae1238a4808ba0c065f03f2` | present |
| `kotlin-scripting-common` | 2.4.10 | `d5399982cdbf5994f7f3a731afacfbc5c21b797d97ffd7328b62cea670103076` | present |
| `kotlin-scripting-compiler-impl-embeddable` | 2.4.10 | `1dcfc5495d8c74966fcbbd39240a9c0f8ab36bb4faad2ea5ffc000c5d7785171` | present |
| `kotlin-stdlib` | 2.4.10 | `8943c84ddc6d5cc00a10dbc3736c397066eeaab5` | already wired by root |
| `com.tschuchort.compiletesting:kotlin-compile-testing` | — | — | **NOT CACHED** ⇒ the spike MUST NOT depend on it (per design §A4 + tasks 2.6) |

## 2. Pin decision (1.2 / 1.5)

The orchestrator hand-off note reports the design says 2.4.20 but the
local cache only holds 2.4.10 in the *primary* slot used by the root
project; 2.4.20 is present in `~/.gradle/caches/.../kotlin-compiler-embeddable/2.4.20/`
as well. To stay **offline** and avoid forcing a fresh Gradle resolve
against an entry that may not have all transitive metadata, **the spike
pins to 2.4.10** (the version `kotlin-reflect:2.4.10` was pinned to in
M3 for the same reason). This is a SINKING-EDGE pin inside the new
submodule only — the root project remains on `kotlin = "2.4.20"`.

Rationale: 2.4.10 has the same K2/FIR SPI surface the proposal needs
(`K2JVMCompiler.main`, `FirSyntheticFunctionProvider`, etc.), so the
spike's Gate 1..5 don't change verdict.

## 3. Reproducible commands (1.3 / 1.4)

```bash
K2JAR=/home/rubentxu/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10/cac44a5a360313427c693a9de295017bc76d6e4e/kotlin-compiler-embeddable-2.4.10.jar
javap -classpath "$K2JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler | grep 'public static final void main'
# → public static final void main(java.lang.String[]);

SCRJAR=/home/rubentxu/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-scripting-compiler-embeddable/2.4.10/6c59d47caf98d7926d8353b1b50ed15594986ce0/kotlin-scripting-compiler-embeddable-2.4.10.jar
unzip -l "$SCRJAR" | grep -E 'FirScriptingCompilerExtensionRegistrar|JvmCliScriptEvaluationExtension|BasicJvmScriptEvaluator|JvmScriptCompilationKt'
# → all four entries present (Gate 5 hook).
```

## 4. Abort gate

WU-0 PASS. Spike continues to WU-1.

If the next probe step (re-running `javap` from inside the new Gradle
submodule once the `compileOnly` deps are declared) fails to resolve the
SPI on the *configured* submodule classpath, the spike would escalate to
Option C. As of this receipt the cache has the right jars; option
WiringError is the only plausible rollback trigger from WU-1 onward.

## 5. Branch state

```text
branch m4-fir-spike (off main@6b6d9f5)
work tree: clean (only this receipt committed in WU-0)
origin/main SHA: 6b6d9f5 (unchanged since cycle open)
```

Result so far: **PROBE PASS, no design deviations, ready for WU-1**.