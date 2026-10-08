# M10 Exploration Report — Production Certification

**Cycle:** `p-dd1a1c7a7d448b0c/m10-production-certification` · Phase: explore
**Clasificación por fila:** OBSERVED (evidencia en repo) / STRUCTURAL / DERIVED / DOCUMENTED.

## Inventario gap vs gates del ROADMAP §M10

| # | Gate ROADMAP | Estado actual | Gap | Clase |
|---|---|---|---|---|
| 1 | full `./gradlew check` exact SHA | `check` verde 256/0 en cada milestone; SHA exacto no se registra como evidencia certificable | CertRunTest que capture y exija SHA del tree + conteo de tests | OBSERVED |
| 2 | compiler matrix | toolchains Gradle detecta Temurin 21/24/25/26 disponibles (javaToolchains OBSERVED); check solo corre en 21 (jvmToolchain(21) global) | Harness/matrix documentada: misma suite en 21 y 25 (LTS) | OBSERVED |
| 3 | multi-format parity | ParityTest existe (json/yaml/map); CSV cubierto por CsvDecoderTest + CheckCmdTest 02a-c | Asegurar parity test incluye CSV end-to-end vía CLI o documentar cobertura exacta | OBSERVED |
| 4 | bundle reproducibility | BundleReproducibilityTest + compile determinista CLI (01a) | Ya cubierto; M10 lo cita como PASS sin cambios | OBSERVED |
| 5 | mutation score targeted | MutationGateTest existe; M9 añadió contracts por REQ; INC-005 (M6 backfill) P2 DEFERRED | Backfill INC-005 dentro de M10 (es la última oportunidad) o DEFERRED con rationale | DOCUMENTED |
| 6 | fuzz/property decoders/evaluator | CERO property tests en repo (grep fuzz/kotest/jqwik vacío) | Añadir property tests deterministas con seed fija (sin dependencias nuevas: generador propio en test scope) | OBSERVED |
| 7 | 1 GiB CSV characterization | No existe generator 1 GiB; CSV decoder es full-materialize | Harness de caracterización: CSV sintético escalonado (64 MiB asserted en CI, 1 GiB runnable on-demand con budget documentado) + characterization digest | OBSERVED |
| 8 | adversarial regex / large policy limits | Kernel SIN regex evaluable por usuario (TEXT_EQUALS/BOOLEAN_EQUALS estructurales; grep regex en kernel = 0) ⇒ superficie de ataque ReDoS = 0 | Test de límites: política de 1000 rules, deep nesting adversarial, documentos gigantes no explotan (bounded behavior) | STRUCTURAL |
| 9 | plugin external same-SHA install | M6 UAT installDist hecho en s6 HEAD; no repetido same-SHA desde M9 | Re-run del harness scripts/m6 con HEAD actual de este repo | DOCUMENTED |
| 10 | restart/replay if PipelineK reuse | No aplica (no hay estado persistente en el plugin; evaluación pura) | Declararlo N/A con rationale (ley 5: sin I/O ⇒ nada que replay-ar) | STRUCTURAL |
| 11 | security purity + fitness + docs/examples | DomainIoPurityTest M9 (baseline exacta); ArchitectureFitnessGuardDualAllowlistTest; docs/09-examples/EXAMPLES.md + UAT catalogs | Verificar frescura de EXAMPLES.md contra superficie M9 (CLI) y actualizar | OBSERVED |

## Release candidate condition (ROADMAP)

"No known semantic ambiguity puede quedar clasificada como pendiente informal; todo item abierto debe quedar explícitamente DEFERRED con rationale no bloqueante o BLOCK release."

⇒ La spec M10 debe enumerar TODO lo abierto (INC-005, D-M7-*, D-M9-*) y forzar resolución o DEFERRED formal.

## Decisiones de alcance (DERIVED, a confirmar en specify)

1. Property tests con generador PRNG propio seed-fijo (kotlin.random.Random(seed)) — cero dependencias nuevas, determinista, cumple ley 6.
2. 1 GiB CSV: characterization con generador streaming (no fixture en repo); CI asserta 64 MiB; 1 GiB queda como comando documentado con presupuesto (tiempo/RAM) — no inflar CI.
3. Compiler matrix: gradación documentada 21 (gate) + 25 (verificación manual scripted). Kotlin 2.4.20 soporta ambas.
4. Regex: superficie cero certificada por test negativo (ninguna política puede llegar a un motor regex) + límites large-policy.
5. INC-005: backfill mínimo (mutation contracts para M6 wiring) si cabe en el ciclo; si no, DEFERRED formal P2 con rationale — pero M10 exige que NADA quede "informal".

## Next executable task

Specify: 8-10 REQ (una por gate agrupado) con escenarios y falsificación.
