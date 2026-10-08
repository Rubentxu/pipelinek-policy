# M10 UAT Evidence — Production Certification

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · **Verdict: PASS (OBSERVED)**

## Gates del ROADMAP §M10 — evidencia ejecutada

| Gate | Evidencia (OBSERVED) |
|---|---|
| full check exact SHA | `check` exit 0, 273 tests 0 failures; anchor SHA.txt + baseline; SHA 09a1266 en merge receipt |
| compiler matrix | scripts/m10/compiler-matrix.sh: **273/273** en Temurin 21.0.8 y 25.0.4 (--rerun-tasks, conteo XML idéntico) |
| multi-format parity | CsvParityTest: JSON/YAML/CSV/Map mismo digest; mutación rompe (03b) |
| bundle reproducibility | BundleReproducibilityTest (M5) + compile determinista CLI 01a (M9), re-ejecutados en check |
| mutation score targeted | contracts de falsificación por REQ en M7/M9/M10 (grep por suite) |
| fuzz/property | PropertyFuzzTest: 200 válidos totales, 400 malformed ⇒ Refused, seed reproducible |
| 1 GiB CSV characterization | CertCsvMain: 12,741,111 filas, digest `33c93065…`, heap 24g (M10_CSV_1GIB_RECEIPT.md) |
| adversarial regex/large policy | RegexSurfaceZeroTest (superficie regex CERO en dominio); LargePolicyLimitsTest (1000 reglas, nesting 500) |
| plugin external same-SHA | uat-external-distribution.sh: PASS ok (exit 0) / VIOLATE ok (exit 1, FailureKind PLUGIN) |
| restart/replay | N/A estructural: evaluación pura sin estado persistente (ley 5) |
| security purity/fitness/docs | DomainIoPurityTest + FitnessGuard PASS; EXAMPLES.md §8 CLI con cross-check M9 05b |

## Release candidate condition

Satisfecha: M10_RC_LEDGER.md con 9 items, todos con severidad/prioridad/
disposition explícitos; 0 TODO/FIXME en fuentes main (grep OBSERVED).

## Reproducción

```bash
./gradle-jdk21.sh check                              # 273 tests, 0 failures
bash scripts/m10/compiler-matrix.sh                  # 273/273
bash scripts/m10/csv-1gib.sh                         # on-demand receipt
bash scripts/m6/uat-external-distribution.sh         # PASS/VIOLATE
```
