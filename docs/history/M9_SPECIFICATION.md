# M9 Specification — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** specify

Referencias: ROADMAP.md §M9, M9_EXPLORATION.md.

## Terminología

- **CLI**: binario `pipelinek-policy` (módulo `pipelinek-policy-cli`).
- **Finding**: un resultado de política accionable (violation, refusal o
  waived) con localización y remediación.
- **Formato de salida**: `text` (humano), `json` (documento único),
  `jsonl` (un finding por línea).

## Requisitos

### REQ-M9-01 — Subcomandos

El CLI expone `compile`, `check`, `diff`, `explain`, `inspect`, `shape`,
`bundle` (con `verify`), y `test`. Comando desconocido ⇒ exit 2 con usage
en stdout.

**Escenarios**
- 01a: `compile <policy.kts?> <bundle>` produce bundle con digest
  determinista; exit 0.
- 01b: subcomando desconocido ⇒ exit 2 + usage; OBSERVED por test argv.
- 01c: FALSIFICACIÓN: dispatch que ignore el primer argumento y ejecute
  `check` por defecto falla 01b.

### REQ-M9-02 — check con corpus multi-recurso

`check --policy <bundle> <resources...>` evalúa cada recurso (decoder por
extensión: .json/.yaml/.csv) y emite findings por recurso. Violations ⇒
exit 1; refusals/decode errors ⇒ exit 2; ok ⇒ exit 0.

**Escenarios**
- 02a: corpus 3 recursos (1 pass, 1 violation, 1 decode-refusal) ⇒ 3
  findings, exit 1 (violations dominan sobre refusal en agregado: exit 1
  con finding de refusal incluido). *Aclaración*: exit code refleja el
  peor estado semántico: error > violation > ok.
  - Corrección: refusal es condición de error ⇒ si hay refusal exit 2;
    si solo violations exit 1.
- 02b: FALSIFICACIÓN: decoder elegido por contenido en vez de extensión
  declarada debe ser cazado (resource .csv parseado como json ⇒ refusal,
  no silent-pass).
- 02c: FALSIFICACIÓN: findings duplicados por recurso (mismo ruleId +
  resource dos veces en la salida) deben ser imposibles (dedup por
  policyId|ruleId|resourceId).
- 02d (B0.2): `RuleEvaluation.Error` conserva `state=error`, no se aplana
  a `violation`, y sale con código 3. Si coexiste con una violación, ambos
  findings sobreviven y el error domina; una refusal sigue dominando con 2.

### REQ-M9-03 — formatos text/json/jsonl

`--format text|json|jsonl` en comandos con findings. `json` emite
documento con summary+findings; `jsonl` un finding por línea. Default
text. Formato desconocido ⇒ exit 2.

**Escenarios**
- 03a: mismo corpus en json y jsonl ⇒ mismos findings (parseo inverso).
- 03b: FALSIFICACIÓN: emitter que serialice findings sin location
  (path/celda) falla el contrato Agent UAT.

### REQ-M9-04 — Agent UAT: findings JSONL accionables

Dado un finding JSONL, un consumidor identifica: policyId, ruleId,
resourceId, location (path + línea/celda cuando el decoder lo provee),
severity, remediation (texto accionable), y fingerprint. Sin parsear
salida humana.

**Escenarios**
- 04a: finding JSONL de una violation CSV tiene celda (fila,col) y
  remediation no vacía.
- 04b: FALSIFICACIÓN: JSONL sin `remediation` ni `fingerprint` debe
  romper el contrato (validador de schema del test).

### REQ-M9-05 — `--json-help` autodescubrible

Todo subcomando acepta `--json-help`: imprime descriptor JSON
(subcomando, flags, exit codes) y exit 0. `pipelinek-policy --json-help`
lista todos los subcomandos.

**Escenarios**
- 05a: `--json-help` del root parsea como JSON y contiene los 8
  subcomandos.
- 05b: FALSIFICACIÓN: help hardcodeado que diverge del dispatch real
  (subcomando listado que no existe) falla (cross-check help vs argv).

### REQ-M9-06 — exit codes estables

0 éxito sin violations; 1 violations presentes; 2 usage/refusal/decode
error; 3 error tipado del evaluador/engine o error interno inesperado.
Estables y documentados en --json-help.

**Escenarios**
- 06a: matriz (ok, violation, refusal, unknown-cmd) ⇒ (0,1,2,2).
- 06b: FALSIFICACIÓN: exit code que colapse refusal a 0 (silent-pass)
  cazado por 06a.

### REQ-M9-07 — explain e inspect

- `explain --policy <bundle> --rule <ruleId> [--resource <res>]`: por qué
  la rule pasó/violó/no aplicó, con expression tree y valores evaluados
  cuando hay recurso.
- `inspect --policy <bundle>`: dump IR canónico (JSON) del bundle.

**Escenarios**
- 07a: explain de rule violada con recurso muestra el subárbol de
  expresión culpable (node id presente en IR).
- 07b: inspect emite JSON que re-parsea como PolicyIrDocument válido.
- 07c: FALSIFICACIÓN: explain que no incluya ruleId en la salida falla.

### REQ-M9-08 — diff, shape y test

- `diff --a <bundle> --b <bundle> [--corpus <resources...>]`: salida del
  PolicyDiff M7 (categorías + digest); CorpusMismatch ⇒ exit 2.
- `shape --policy <bundle>`: resumen estructural (rules, selectors,
  params por rule) sin evaluación.
- `test --policy <bundle> --fixtures <dir>`: fixtures name→expectation
  (allow/deny); mismatch ⇒ exit 1 con diff por fixture.

**Escenarios**
- 08a: diff A/B con corpus ⇒ resumen categorías + digest idéntico al de
  PolicyDiff API (no diverge).
- 08b: shape cuenta rules/selectors correctamente (vs IR parseado).
- 08c: test con 2 fixtures (1 pass 1 fail) ⇒ exit 1, finding por fixture.
- 08d: FALSIFICACIÓN: diff CLI que no use PolicyDiff (reimplementación
  divergente) cazado comparando digest contra API directa.

### REQ-M9-09 — pureza del kernel preservada

Ningún fichero de `kernel/`, `ir/`, `bundle/` ni `dsl/` gana imports de
fs/network/process. Todo I/O vive en `pipelinek-policy-cli`.

**Escenarios**
- 09a: test arquitectónico: scan de imports prohibidos en packages de
  dominio ⇒ 0 hits (java.io/java.nio/java.net/Process/Runtime salvo los
  ya existentes auditados).

## UAT principal (ROADMAP §M9)

> Dado un finding JSONL, un agente puede identificar fichero/celda/path y
> propuesta de corrección sin parsear consola humana.

Cubierto por REQ-M9-04 (04a/04b).

## Fuera de alcance

- Servidor MCP/LSP (no pedido por M9).
- Watch/daemon modes.
- `shape` de datasets M8 (LOCAL/AGGREGATE/GLOBAL) — M8 pendiente.
