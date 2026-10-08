# M10 Design — Production Certification

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · Phase: design

## Principio

M10 no añade superficie de producción: añade **instrumentos de
certificación** (tests + harnesses + ledger). Todo lo nuevo vive en
`src/test` del root o en `scripts/m10/` y `docs/history/`. Cero cambios
en módulos main (salvo que un test de límites detecte un defecto real).

## Decisiones

### D1 — Tests de certificación en la suite que certifican

CertificationAnchorTest (01a/01b), CsvParityTest (03), PropertyFuzzTest
(04), CsvCharacterizationTest 64 MiB (05a/05b), LargePolicyLimitsTest +
RegexSurfaceZeroTest (06) viven en `src/test/kotlin/com/pipelinek/policy/cert/`
(root). Así cualquier `check` futuro re-certifica; el conteo del anchor
crece con ellos (baseline se actualiza en el propio ciclo).

### D2 — Anchor de SHA sin acoplarse a git en tests

El test NO llama a git (tests no hacen I/O de proceso): usa un marcador
`cert/SHA.txt` en test-resources cuya frescura verifica el harness
`scripts/m10/compiler-matrix.sh` (que sí puede llamar git). El anchor
exige: suite-count >= baseline certificado y presencia del marcador.
Falsificación 01b: bajar el conteo rompe el test.

### D3 — PRNG propio, seed fija, digest de corpus

`cert/RandomDocuments.kt`: generador determinista sobre
`kotlin.random.Random(seedConst)` que emite ValueNode arbitrarios
(válidos) y bytes malformados por formato. 04c assertion: digest SHA-256
del corpus generado es estable (misma seed ⇒ mismo digest). Sin
dependencias nuevas (ley 6).

### D4 — Characterization escalonada

`cert/SyntheticCsv.kt` genera streaming (Sequence<String>) filas con
columna de temperatura: `temp > threshold` viola. 64 MiB en CI (budget
~60s), digest del informe caracterizado. `scripts/m10/csv-1gib.sh`
escala el mismo generador a 1 GiB on-demand vía un main de test
(`CertCsvMain.kt` con args size) y escribe el receipt con
`/usr/bin/time -v`. No-ejecución en CI: DECLARADA en spec 05c.

### D5 — Regex superficie cero (06c)

RegexSurfaceZeroTest escanea los fuentes main de kernel y evaluator (no
hay paquete regex separado): ninguna import de `java.util.regex` fuera
de la baseline existente (ValueNode/CanonicalPolicyJson/PolicyBundle usan
MessageDigest, no regex ⇒ baseline vacía). Si aparece MATCHES en
Expression.Operator, el test falla exigiendo test de límites. Nota: el
CLI/plugin/decoders pueden usar regex para dispatch de flags: el
escaneo se limita a kernel+evaluator (dominio).

### D6 — Compiler matrix

`scripts/m10/compiler-matrix.sh`: para cada JDK (21, 25) corre
`./gradle-jdk21.sh -Dorg.gradle.java.installations.paths=<jdk> test`
con toolchain auto-detección (o `-Ptoolchain=25` si se añade knob). La
salida parsea el conteo de tests; si difiere entre runtimes ⇒ FAIL.
Escribe `docs/history/M10_COMPILER_MATRIX.md`.

### D7 — Plugin install same-SHA

Re-run de `scripts/m6/*.sh` (installDist externo) con HEAD actual; el
receipt va al verify-report. Si el entorno externo no está clonado, el
gate 07 reporta BLOCKED, no waived.

### D8 — RC ledger

`docs/history/M10_RC_LEDGER.md`: tabla item | severidad | prioridad |
disposition (RESOLVED-IN-M10 / DEFERRED + rationale). INC-005
disposition: backfill parcial si el WU de mutation contracts cabe; si
no, DEFERRED P2 rationale "no bloqueante para RC: contracts por REQ
existen desde M7/M9; backfill M6-wiring queda para el retorno del
authoring FIR" — formal, no informal.

## WUs (preview para plan)

1. WU-1 cert/ anchor + PRNG generador + RegexSurfaceZero
2. WU-2 PropertyFuzzTest (04) + CsvParityTest (03)
3. WU-3 SyntheticCsv + CsvCharacterizationTest (05a/b) + CertCsvMain + csv-1gib.sh
4. WU-4 LargePolicyLimitsTest (06a/b) + deep nesting
5. WU-5 scripts/m10 compiler-matrix.sh + run + receipt; scripts/m6 re-run
6. WU-6 EXAMPLES.md CLI + RC ledger + cierres
