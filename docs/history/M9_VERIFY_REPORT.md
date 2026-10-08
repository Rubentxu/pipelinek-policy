# M9 Verify Report — CLI y Agent DX

**Cycle:** `p-dd1a1c7a7d448b0c/m9-cli-agent-dx` · **Phase:** verify
**Executed by:** orchestrator (inline, patrón M3/M5/M6/M7)
**Verdict: PASS**

## Matriz REQ → evidencia (OBSERVED)

| REQ | Suite | Resultado |
|---|---|---|
| M9-01 subcomandos | DispatchTest + CompileBundleTestTest | 01a compile determinista (bytes idénticos, verify true); 01b/01c unknown ⇒ 2 |
| M9-02 check corpus | CheckCmdTest 6/6 | 02a error>violation>ok; 02b by-extension no sniffing; 02c dedup |
| M9-03 formatos | FindingsTest 5/5 | 03a json↔jsonl paridad; 03b location siempre serializada |
| M9-04 Agent UAT | FindingsTest 04b + HelpAndExitCodesTest | remediation+fingerprint siempre presentes; JSONL accionable |
| M9-05 --json-help | HelpAndExitCodesTest 4/4 | 05a 8 subcomandos; 05b cross-check help vs dispatch |
| M9-06 exit codes | HelpAndExitCodesTest matriz | 0/1/2/2; falsificación silent-pass cazada |
| M9-07 explain/inspect | ExplainCmdTest 4/4 + InspectShapeDiffTest 07b | estado+árbol+locations; IR re-parseable |
| M9-08 diff/shape/test | InspectShapeDiffTest + CompileBundleTestTest | digest CLI == API (08a/08d); shape == IR (08b); fixtures per-fixture (08c) |
| M9-09 pureza | DomainIoPurityTest | baseline exacta de imports I/O del dominio; 0 offenders |

## Regresión

`gradle-jdk21.sh check`: BUILD SUCCESSFUL — **256 tests, 0 failures**
(root + submódulos: 219 previos + 37 nuevos del CLI y pureza), detekt clean.
Fitness guard: bucket nuevo `cliModuleAllowedCoords` activo (CLI puede traer
jackson/snakeyaml en transitivo del runtime de la application, nada más).

## UAT principal (ROADMAP §M9)

"Dado un finding JSONL, un agente puede identificar fichero/celda/path y
propuesta de corrección sin parsear consola humana" — cubierto: todo
finding JSONL lleva policyId/ruleId/resourceId/severity/state/location/
remediation/fingerprint (04b lo falsifica si falta cualquiera).

## Hallazgos

- W1 (informativo): explain v1 no expone traza por nodo del evaluator
  (design §4 scope guard). El contrato (ruleId + árbol culpable +
  locations) se cumple con el árbol estático del IR.
- W2 (informativo): compile v1 acepta IR canónico JSON (no .kts): M4
  documentó el camino FIR como FAIL/Opción C; el IR es la autoridad
  ejecutable (ley 2). Cuando el authoring vuelva, compile gana la rama
  .kts sin cambiar contrato.
- W3 (informativo): diff CLI evalúa el primer recurso del corpus (no
  multi-corpus); PolicyDiff.of es single-report. Multi-recurso requiere
  agregación diff por recurso: follow-up, no exigido por la spec M9.

## Veredicto

PASS. 9/9 REQ con evidencia observada; regresión M1-M7 intacta.
