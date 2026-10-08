# M10 Specification — Production Certification

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · Phase: specify

Formato heredado de M9: REQ numerados, escenarios con codificación
`NNx` (a positivo, b/c falsificación/negativo), criterios de salida.

## REQ M10-01 — Certificación de ejecución (check exact SHA)

El estado de certificación se ancla al SHA del commit: un test de
certificación registra el SHA del tree y exige conteo mínimo de tests.

- 01a: CertificationAnchorTest captura `git rev-parse HEAD` (o marcador
  embebido) y el conteo total de suites ejecutadas; el verify-report M10
  cita SHA + conteo exactos.
- 01b (falsificación): el test falla si el conteo de suites es menor que
  el baseline certificado (protege contra "se cayó un módulo del check").

## REQ M10-02 — Compiler matrix documentada y ejecutada

- 02a: script `scripts/m10/compiler-matrix.sh` ejecuta la suite completa
  con toolchains 21 y 25 (Temurin LTS disponibles OBSERVED); registra
  salida y exit codes en `docs/history/M10_COMPILER_MATRIX.md`.
- 02b (falsificación/negativo): la matrix se niega a reportar PASS si
  alguno de los dos runtimes falla o si el conteo de tests difiere entre
  toolchains (misma suite ⇒ mismo conteo).

## REQ M10-03 — Multi-format parity end-to-end

- 03a: CsvParityTest: mismo corpus lógico en JSON/YAML/CSV/Map produce el
  MISMO informe de evaluación (findings equivalentes módulo location).
- 03b (falsificación): mutar un campo del corpus CSV (valor distinto)
  cambia el informe; la paridad no es trivial-all-green.

## REQ M10-04 — Property/fuzz deterministas (decoders + evaluator)

- 04a: PropertyFuzzTest con PRNG propio seed fija (kotlin.random.Random):
  200 documentos aleatorios válidos por formato decodifican sin excepción
  y la evaluación de una política canónica es total (sin throw).
- 04b: 200 documentos MALFORMADOS aleatorios producen DecodeRefusal
  (nunca excepción no declarada ni crash).
- 04c (falsificación): el generador con la misma seed reproduce exactamente
  los mismos inputs (assert de digest del corpus generado) — sin esto el
  "property test" no es reproducible.

## REQ M10-05 — CSV characterization escalonado (64 MiB CI / 1 GiB on-demand)

- 05a: CsvCharacterizationTest genera streaming un CSV sintético de
  64 MiB, decodifica, evalúa y produce un digest de caracterización
  (SHA-256 del informe); budget acotado en CI.
- 05b (falsificación): mutar una celda del corpus cambia el digest.
- 05c: `scripts/m10/csv-1gib.sh` ejecuta el mismo harness a 1 GiB
  on-demand y documenta presupuesto (tiempo/RSS) en
  `docs/history/M10_CSV_1GIB_RECEIPT.md`; su no-ejecución en CI queda
  DECLARADA, no silenciosa.

## REQ M10-06 — Límites adversariales y superficie regex cero

- 06a: LargePolicyLimitsTest: política de 1000 reglas evalúa un documento
  medio sin StackOverflow y con comportamiento acotado.
- 06b: deep nesting adversarial (documento anidado 500 niveles) ⇒
  DecodeRefusal o evaluación acotada, nunca desbordamiento silencioso.
- 06c (falsificación/superficie): RegexSurfaceZeroTest afirma que NINGÚN
  nodo del Expression ADT compila regex (grep estructural: kernel no
  importa java.util.regex); falla si alguien añade MATCHES sin test de
  límites.

## REQ M10-07 — Plugin external install same-SHA

- 07a: re-run del harness scripts/m6 (installDist del plugin PipelineK
  externo) contra HEAD actual; receipt en verify-report.
- 07b (negativo): si la instalación del artefacto falla, el gate reporta
  BLOCKED (no PASS condicional).

## REQ M10-08 — Restart/replay N/A declarado

- 08a: documento en verify-report: evaluación pura sin estado persistente
  (ley 5) ⇒ no hay path de restart/replay aplicable; rationale
  STRUCTURAL, no omisión.

## REQ M10-09 — Purity + fitness + docs/examples frescos

- 09a: DomainIoPurityTest y ArchitectureFitnessGuardDualAllowlistTest ya
  en check (PASS sin cambios).
- 09b: EXAMPLES.md ampliado con la superficie CLI M9 (check/jsonl/exit
  codes) — frescura de docs certificada.
- 09c (negativo): EXAMPLES.md cita comandos que existen en el
  CommandRegistry (cross-check automático o revisión con receipt).

## REQ M10-10 — Release-candidate ledger (cero pendientes informales)

- 10a: M10_RC_LEDGER.md enumera TODO item abierto del proyecto
  (INC-005, D-M7-1/2, D-M9-1/2/3) con estado RESUELTO-EN-M10 o
  DEFERRED + rationale no bloqueante.
- 10b (falsificación): el ledger no admite estado "informal": todo item
  debe tener severidad, prioridad y disposition explícitos.

## Criterios de salida

- `./gradle-jdk21.sh check` verde con los tests nuevos incluidos.
- Compiler matrix 21/25 ejecutada con receipt.
- RC ledger completo sin pendientes informales.
