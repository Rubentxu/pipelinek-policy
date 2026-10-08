# M9 Implementation Receipt

6 WU completados: infra CLI (módulo pipelinek-policy-cli, CliArgs, CommandRegistry,
ExitCodes 0/1/2/3, dispatch), Findings ADT + emitters text/json/jsonl, CheckCmd
(decoder por extensión, agregación error>violation>ok, dedup), DiffCmd (PolicyDiff
API directa), InspectCmd (IR canónico re-parseable), ShapeCmd, ExplainCmd (estado +
árbol de expresión + locations), CompileCmd (IR JSON → bundle determinista),
BundleCmd verify, TestCmd fixtures allow/deny, --json-help derivado del registry,
matriz exit codes y test de pureza del dominio (DomainIoPurityTest baseline exacta).

Evidencia: gradle-jdk21.sh check exit 0. 256 tests 0 failures (root+submódulos).
Falsificación por REQ: 01c/02b/02c/03b/04b/05b/06b/07c/08d/09a.
Firmado: orchestrator inline 2026-10-08T18:16:57Z
