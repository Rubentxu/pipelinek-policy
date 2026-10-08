# M9 UAT Evidence — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Veredicto: PASS (OBSERVED)**

## UAT del ROADMAP (§M9 Agent UAT)

> "Dado un finding JSONL, un agente puede identificar fichero/celda/path y
> propuesta de corrección sin parsear consola humana."

Evidencia: `FindingsTest` (04a/04b) y `HelpAndExitCodesTest` — todo finding
en JSONL incluye policyId, ruleId, resourceId, severity, state, location
(fichero/línea/celda), remediation y fingerprint (ViolationFingerprint M7).
04b es el test de falsificación: falla si falta cualquiera de los campos
accionables. CheckCmdTest 02a/02b/02c prueban el pipeline end-to-end con
corpus JSON/YAML/CSV y agregación error>violation>ok.

## Superficie entregada

- 8 subcomandos: compile/check/test/explain/inspect/diff/shape/bundle verify
- Formatos text/json/jsonl con paridad json↔jsonl (03a)
- `--json-help` derivado del CommandRegistry (05a/05b cross-check vs dispatch)
- Exit codes estables 0/1/2/3 (06a/06b matriz + falsificación silent-pass)
- Digest diff CLI == API PolicyDiff (08a/08d)
- Pureza del dominio auditada: DomainIoPurityTest baseline exacta (ley 5)

## Reproducción

```
./gradle-jdk21.sh check   # BUILD SUCCESSFUL, 256 tests, 0 failures
```
