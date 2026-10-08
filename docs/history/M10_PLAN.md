# M10 Implementation Plan — 6 WUs

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · Phase: plan

- **WU-1** `cert/` fundaciones: CertificationAnchorTest (01a/01b, marcador
  SHA.txt + baseline suite-count), RandomDocuments.kt (PRNG seed fija,
  generador ValueNode válidos + bytes malformados, digest corpus 04c),
  RegexSurfaceZeroTest (06c). Falsificaciones: 01b, 04c, 06c.
- **WU-2** PropertyFuzzTest (04a válidos totales, 04b malformados ⇒
  DecodeRefusal) + CsvParityTest (03a cuatro formatos mismo informe, 03b
  mutación cambia informe).
- **WU-3** SyntheticCsv.kt streaming + CsvCharacterizationTest 64 MiB
  (05a digest, 05b mutación) + CertCsvMain.kt + scripts/m10/csv-1gib.sh
  + M10_CSV_1GIB_RECEIPT.md (05c on-demand).
- **WU-4** LargePolicyLimitsTest (06a 1000 reglas, 06b deep nesting 500
  ⇒ refusal/acotado). Si aflora defecto real: fix mínimo + test.
- **WU-5** scripts/m10/compiler-matrix.sh (JDK 21+25, conteo igual,
  M10_COMPILER_MATRIX.md) + re-run scripts/m6 plugin install same-SHA.
- **WU-6** EXAMPLES.md sección CLI M9 (09b/09c cross-check con
  CommandRegistry) + M10_RC_LEDGER.md (10a/10b disposition completa,
  INC-005 decidido) + docs de cierre.

Done por WU: tests locales del WU + `check` verde.
Done del plan: 24/24 escenarios de la spec con evidencia.
