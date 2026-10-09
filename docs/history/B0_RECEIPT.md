# BLOQUE B0 — Receipt (Admisión segura y baseline)

Fecha: 2026-10-09 · Commits: sobre `9112987` (baseline) · Ver `B0_BASELINE.md` para B0.4.

## B0.1 — Error → PASSED (P0) · **FIXED, falsificado**

**Defecto (OBSERVED, reproducido en rojo):** `PolicyCheckStepDefinition.evaluate`
contaba solo `RuleEvaluation.Violated`; un report con `Error` y 0 violaciones
devolvía `PolicyCheckOutput.passed(...)` → `StepOutcome.Success`. Tests
`B0-1a`/`B0-1b` fallaban contra el código pre-fix (evidencia en la transcripción
de ejecución, 10:58:32Z).

**Fix:** veredicto `ERRORED` (cuarto valor del enum `PolicyCheckVerdict`,
aditivo al wire contract serializado; `PASSED/VIOLATED/REFUSED` intactos) +
`PolicyCheckOutput.errored(...)`. Outcome: `StepOutcome.Failure(PLUGIN)` en
ENFORCED **y en SHADOW** (un error operativo no es un veredicto de negocio;
shadow preserva would-deny como evidencia, no convierte "no pude evaluar"
en éxito silencioso). Precedencia: REFUSED > VIOLATED > ERRORED > PASSED
(las violaciones siguen dominando el verdict; los errores operativos puros
sin violaciones también fallan).

Tabla B0.1 requerida (estado resultante):

| Resultado evaluador | ACTIVE | SHADOW |
|---|---|---|
| Passed | Success | Success |
| Violated | Failure(PLUGIN) | Success + wouldDeny=true |
| NotApplicable | Success (estado en summaries) | ídem |
| Error | Failure(PLUGIN) ERRORED | Failure(PLUGIN) ERRORED (no silencioso) |
| Refusal admisión | Failure REFUSED | Failure REFUSED (domina) |

## B0.2 — Propagación tipada · **PARCIAL (lo que posee B0)**

- Catch de admisión de bundle ampliado a `Exception` genérica (un crash del
  verificador ya no puede fugarse como PASSED ni como excepción sin clasificar):
  REFUSED con `Clase: mensaje`.
- Separación vigente: decode refusal / bundle refusal / error semántico
  (ERRORED) / violación (VIOLATED) — cuatro clases distinguibles en el output.
- La taxonomía completa de seis clases (error adaptador vs engine, etc.) y el
  wrapper `Result<PolicyCheckOutput, PolicyCheckError>` viven en B4.3
  (enforcement como responsabilidad diferenciada). Registrado como deuda de
  bloque, no DEFERRED encubierto.

## B0.3 — Regression fortress · **8/8 VERDE**

`FailClosedFortressTest` (plugin): B0-1a error ENFORCED fail-closed con estado
semántico en summaries; B0-1b error SHADOW no silencioso; B0-1c mixto
passed+error+violated distinguible; B0-3a NotApplicable legítimo; B0-3b bundle
corrupto falla incluso en SHADOW; B0-3c violated SHADOW = would-deny +
Success; B0-3d decode refusal → REFUSED Failure; B0-3e violated ENFORCED →
Failure(PLUGIN) con mensaje.

## Mutación auténtica (B6.5 aplicada ya en B0)

1. Mutación válida: eliminada la rama `errors > 0` (replica el defecto original). ✔
2. Código mutado compiló. ✔
3. Fortress ejecutado: 2 FAILED (B0-1a, B0-1b). ✔
4. Fallos por las aserciones esperadas ("must never be reported as PASSED",
   "silent PASSED"). ✔
5. Restore del código. ✔
6. Re-run: 8/8 verde (XML 0 failures). ✔

## Gate B0 · **PASS**

- Ningún `RuleEvaluation.Error` puede producir PASSED (fortress + mutación).
- El estado semántico del evaluador sobrevive (summaries: passed/error/
  violated/not-applicable).
- SHADOW no oculta errores operativos ni rechazos de admisión (B0-1b, B0-3b).
- Tests negativos demostraron el fallo previo (rojo pre-fix, OBSERVED).
- `gradle check --rerun-tasks` global: **BUILD SUCCESSFUL, 317 tests / 0
  failures / 0 errors** (188+61+29+11+16+5+7). Detekt incluido.

## Deuda nueva / pendiente

- D-B0-1 (P2): taxonomía de errores de seis clases + wrapper tipado → B4.3.
- D-B0-2 (P3): `IrRuntimeAdapter` sigue devolviendo report plano; envolvente
  de error de adaptador → B4.
- Wire: consumidores del enum serializado deben tolerar `ERRORED` (aditivo);
  registrado para B2.8 compatibilidad.
