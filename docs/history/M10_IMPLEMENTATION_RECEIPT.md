# M10 Implementation Receipt

6 WUs completados (suite `cert/` en pipelinek-policy-cli + harnesses):

- WU-1: CertificationAnchorTest (01a marker SHA.txt+baseline, 01b
  suite-count >= 42), RandomDocuments (PRNG seed fija 0x4D10_C0_71F00,
  digest corpus estable), RegexSurfaceZeroTest (06c sin java.util.regex
  en dominio, Operator sin MATCHES).
- WU-2: PropertyFuzzTest (04a 200 docs totales, 04b 200 malformed
  JSON/YAML⇒Refused + CSV total, 04c seed distinta ⇒ corpus distinto),
  CsvParityTest (03a 4 formatos mismo digest, 03b mutación cambia).
- WU-3: SyntheticCsv streaming, CsvCharacterizationTest 64 MiB (05a/05b,
  heap 4g), CertCsvMain + task certCsv1gib + scripts/m10/csv-1gib.sh;
  1 GiB ejecutado: 12,741,111 filas, digest 33c93065…, heap 24g
  (OOM 6g/12g documentado).
- WU-4: LargePolicyLimitsTest (06a 1000 reglas sin overflow, 06b deep
  nesting 500 JSON Ok/Refused + árbol ValueNode acotado).
- WU-5: scripts/m10/compiler-matrix.sh con -PtestJvm (toolchain
  parametrizable en los 7 módulos + javaLauncher del manifest JavaExec);
  PASS 273/273 en JDK 21 y 25. Re-run UAT plugin externo same-SHA:
  PASS/VIOLATED exit 0/1 correctos.
- WU-6: EXAMPLES.md §8 CLI M9, M10_RC_LEDGER.md (10a/10b completo, 0
  TODOs en main OBSERVED), BootstrapSmokeTest LTS 21|25.

Evidencia: gradle-jdk21.sh check exit 0 (273 tests, 0 failures,
detekt clean). Compiler matrix PASS 273/273. 1 GiB receipt con digest.
Firmado: orchestrator inline $(date -u +%Y-%m-%dT%H:%M:%SZ)
