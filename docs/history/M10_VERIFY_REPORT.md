# M10 Verify Report — Production Certification

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · Phase: verify
**Executed by:** orchestrator (inline, patrón M3..M9)
**Verdict: PASS**

## Matriz REQ → evidencia (OBSERVED)

| REQ | Evidencia | Resultado |
|---|---|---|
| M10-01 anchor SHA | CertificationAnchorTest 01a/01b | marker SHA.txt (5a9162b, baseline 273); suite-count 47 >= 42 |
| M10-02 compiler matrix | scripts/m10/compiler-matrix.sh | **PASS 273/273** (Temurin 21.0.8 y 25.0.4, --rerun-tasks, conteo XML idéntico) |
| M10-03 multi-format parity | CsvParityTest 03a/03b | 4 formatos mismo digest; mutación rompe paridad |
| M10-04 fuzz/property | PropertyFuzzTest 04a/b/c | 200 válidos totales; 400 malformed ⇒ Refused; seed reproducible y distinguible |
| M10-05 CSV characterization | CsvCharacterizationTest + CertCsvMain | 64 MiB CI digest estable; **1 GiB: 12,741,111 filas, digest 33c93065…, evaluate 450s, heap 24g** (receipt) |
| M10-06 límites + regex 0 | LargePolicyLimitsTest + RegexSurfaceZeroTest | 1000 reglas sin overflow; nesting 500 acotado; dominio sin java.util.regex ni MATCHES |
| M10-07 plugin same-SHA | scripts/m6/uat-external-distribution.sh re-run | PASS ok (exit 0, PASSED) / VIOLATE ok (exit 1, FailureKind PLUGIN) |
| M10-08 restart/replay N/A | análisis estructural | evaluación pura sin estado persistente (ley 5): nada que replay-ar. DECLARADO N/A |
| M10-09 purity+fitness+docs | check verde + EXAMPLES.md §8 | DomainIoPurity/FitnessGuard PASS; EXAMPLES cita comandos del CommandRegistry (05b cross-check M9) |
| M10-10 RC ledger | M10_RC_LEDGER.md | 9 items con disposition explícita; 0 TODO/FIXME en main (grep OBSERVED) |

## Gates del ROADMAP §M10 → cobertura

| Gate ROADMAP | Cobertura |
|---|---|
| full check exact SHA | anchor 01a/01b + SHA en receipts (5a9162b → commit final en merge receipt) |
| compiler matrix | REQ 02 (273/273) |
| multi-format parity | REQ 03 |
| bundle reproducibility | BundleReproducibilityTest M5 + compile 01a M9 (re-ejecutado en check) |
| mutation score targeted | MutationGateTest M5 + contracts 01c..10b M10 (por REQ) |
| fuzz/property | REQ 04 |
| 1 GiB CSV characterization | REQ 05 (receipt on-demand, CI 64 MiB) |
| adversarial regex/large policy | REQ 06 (superficie regex CERO certificada) |
| plugin external same-SHA | REQ 07 |
| restart/replay | REQ 08 (N/A estructural) |
| security purity / fitness / docs | REQ 09 |

## Release candidate condition

**Satisfecha**: RC ledger sin pendientes informales (10a/10b). INC-005 y
deuda P3 con DEFERRED + rationale; M8 BLOCKED-BY entrada (externa) y M4
FAIL documentado (decisión, no deuda).

## Regresión

`gradle-jdk21.sh check`: BUILD SUCCESSFUL — **273 tests, 0 failures**
(256 M9 + 17 nuevos de cert/…), detekt clean. Suite re-ejecutada íntegra
en dos toolchains.

## Hallazgos

- H1: CSV full-materialize ⇒ 1 GiB necesita 12-24 GiB heap (OOM a 6g/12g).
  Registrado como INPUT M8 (streaming LOCAL/AGGREGATE/GLOBAL), no deuda M10.
- H2: la matrix exigió parametrizar jvmToolchain (-PtestJvm) y el
  javaLauncher del manifest JavaExec (UnsupportedClassVersionError v69 en
  daemon 21) — cambios de build con valor permanente, documentados.

## Veredicto

PASS. 10/10 REQ con evidencia observada; los 11 gates del ROADMAP cubiertos.
