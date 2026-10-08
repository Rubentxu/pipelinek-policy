# M9 Plan — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** plan

WUs derivados de M9_SPECIFICATION (9 REQ) y M9_DESIGN. Cada WU cierra con
tests quirúrgicos verdes antes del siguiente.

## WU-1 — Infra CLI: módulo, args, registry, exit codes

- Módulo Gradle `pipelinek-policy-cli` (application, mainClass
  PolicyCliKt; settings.gradle + build.gradle.kts, detekt heredado).
- `CliArgs` (posicional + flags `--k v`), `ExitCodes` (0/1/2/3),
  `CommandRegistry` (nombre, flags, descripción, exit codes),
  `PolicyCli.run(args): Int` + `main` con exitProcess.
- Dispatch desconocido ⇒ usage + 2 (REQ-M9-01b).
- Tests: CliArgsTest, DispatchTest (01b/01c falsificación: dispatch que
  caiga a check por defecto debe romperse).

## WU-2 — Findings + check

- `Finding` ADT, `Location`, emitters text/json/jsonl (JSON manual con
  escape), dedup policyId|ruleId|resourceId (02c).
- `CheckCmd`: decoder por extensión (json/yaml/csv del SPI), evaluación,
  agregación exit: error > violation > ok (02a corregido: refusal ⇒ 2).
- Extensión desconocida/sniff ⇒ refusal (02b falsificación).
- Tests: FindingsTest (03a json↔jsonl paridad, 03b location presente),
  CheckCmdTest (02a/02b/02c).

## WU-3 — diff / inspect / shape

- `DiffCmd`: `PolicyDiff.of` directo (08a digest == API, 08d
  falsificación con digest divergente), CorpusMismatch ⇒ 2.
- `InspectCmd`: dump CanonicalPolicyJson re-parseable (07b).
- `ShapeCmd`: conteo rules/selectors/params desde IR (08b vs IR).
- Tests: DiffCmdTest, InspectCmdTest, ShapeCmdTest.

## WU-4 — explain

- `ExplainCmd`: ruleId + estado + expression tree del IR (node ids);
  con recurso opcional (07a subtree culpable, 07c falsificación
  ruleId ausente).
- Scope guard: si Evaluator no expone traza por nodo, v1 = estado rule +
  árbol de expresión estático del IR (design §4).
- Tests: ExplainCmdTest.

## WU-5 — compile / bundle verify / test

- `CompileCmd`: packer M5 → bundle determinista (01a).
- `BundleCmd verify`: BundleVerifier; fallo ⇒ 2 (o 1 si es finding).
- `TestCmd`: fixtures dir (name→allow/deny) vs check interno; mismatch
  ⇒ 1 con finding por fixture (08c).
- Tests: CompileCmdTest, BundleVerifyCmdTest, TestCmdTest.

## WU-6 — --json-help + matriz exit codes + pureza

- `JsonHelp` derivado del CommandRegistry (05a parsea + 8 subcomandos;
  05b falsificación cross-check help vs dispatch).
- Matriz 06a/06b (ok/violation/refusal/unknown ⇒ 0/1/2/2).
- Test arquitectónico 09a: imports fs/net/process en kernel|ir|bundle|dsl
  == baseline exacta (snapshot).
- `gradle-jdk21.sh check` completo verde + detekt.

## Orden y dependencias

WU-1 → WU-2 → {WU-3, WU-4, WU-5} → WU-6. WU-2 bloquea a 3/4/5 (usa
Findings y decoders). WU-6 cierra con check global.
