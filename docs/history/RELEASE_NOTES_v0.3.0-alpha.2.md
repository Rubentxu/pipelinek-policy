# v0.3.0-alpha.2 — Release notes

**Estado:** `READY_FOR_HUMAN_GATE` (alpha.2)
**Fecha:** 2026-10-11
**SHA candidato (local):** `583036a908ae09567c1ac0eaade4117fd32c58f7`
**SHA candidato (a verificar en remoto):** `c906988582ea16085bb6ec0154559e5a30c770c6` (origin/main — to be advanced by the merge in R1.5)
**Versión bump:** `0.3.0-alpha.1` → `0.3.0-alpha.2`
**Gates:** mutation 38/0/0 · certify 619/0/0/0 · release-admission ADMISSIBLE
**Tag objetivo:** `v0.3.0-alpha.2` (anotado, prerelease en GitHub)

## Resumen ejecutivo

R1 cierra 53 commits de trabajo local (H2 datasets, H3 streaming, auditoría, R1.1–R1.4 reconciliation) en un único candidato publicable. El gate de admisión local está **ADMISSIBLE** sobre `583036a`. La única operación restante es la integración a `origin/main` y la publicación del tag + GitHub Release, que requieren **autorización humana explícita** (regla 10 de la directiva R1–R4).

## Qué cambia entre alpha.1 y alpha.2

### H2 — Datasets & planning (4 production fixes + 1 refactor)

- **COUNT semántico** — `Accumulator.Count` cuenta filas coincidentes, no filas totales. Tres tests nuevos (`StreamingCountMatchesTest`) sostienen la invariante, falsificados por mutantes M1 (drop match test) y M2 (invert it). 4 defectos: el primero intentaba devolver `null` para agregados no-COUNT y silenciosamente removía la regla del reporte.
- **RuleKey identity** — `DatasetShape.Result` y los consumidores de streaming seleccionan reglas por `RuleKey` (policy+id), no por id desnudo. Antes, dos políticas declarando una regla `check` compartían un slot y la última declaración ganaba. Seis tests nuevos (`DatasetRuleIdentityProbeTest`), falsificados por mutantes R1 y R2.
- **Sealed `PlanResult`** — `DatasetPlanner.plan(...)` ahora retorna `DatasetPlanner.PlanResult` (sealed). Eliminado el `as DatasetPlan` unchecked cast. Tres tests (`H22SealedPlanTest`).
- **`IndexPlan.SinglePass` por defecto** — el planner ya no emite un `InMemoryIndex` decorativo cuando `requiresGlobalIndex = true`, porque nadie lo consume (ley 12). El seam se preserva para H2.4.

### H3 — Streaming & budgets (1 stream refactor + 1 CLI refactor)

- **`windowed JsonlSource`** — JSONL ahora se lee por chunks (no materializa el documento). 5 tests nuevos (`JsonlRowStreamSourceTest`), falsificados por N3 (open materialises) y N4 (window never slides). El primer anchor de N4 sobrevivió porque el fixture alineaba el borde de ventana con el borde de fila; el anchor corregido (la llamada `slide()` dentro de `ensure()`) reproduce la misma clase de defecto que el mutante N2 de CSV.
- **`StreamCmd` JSONL branch streams** — antes abría el fichero en memoria, ahora abre como stream.
- **`CsvRowSource` streaming** — el decoder CSV ahora streamea el dataset (no materializa). 5 tests nuevos (`CsvRowStreamSourceTest`), falsificados por N1 y N2. La versión original de `CsvRowSourceTest.06b` allocated su payload antes de tomar la baseline de Runtime, lo que hacía la medición ciega; el reemplazo cuenta bytes extraídos del stream.

### Auditoría y correcciones (sin cambios de comportamiento)

- **Auto-validación de los 9 gaps del reviewer** con evidencia ejercida en CLI instalado (strace sobre 6.9 MiB JSONL, rechazo de GLOBAL byte-idéntico en CLI real, mutante superviviente H1.3 honestamente registrado). 5 correcciones al reporte de auto-validación.
- **Corrección H2.4 design (B) blast radius 18 → 14 sitios** vía grep preciso de `Expression.DatasetRef(` (constructor calls solamente, no type references).
- **Re-anchor de cert/SHA.txt** — el SHA certificado pasó de `7873b723` a `d5f2c2cb` después de las correcciones de detekt en `DatasetPlanner.kt` y `H22SealedPlanTest.kt`. El comentario del marker documenta la razón del movimiento.

### R1 reconciliation (este ciclo)

- **R1.1 preflight** — midió el estado del repo antes de cualquier acción. Registró la tensión cronológica del tag `v0.3.0-alpha.1` (en `427e30f` localmente, vs `origin/main` en `c906988`).
- **R1.2 inventario** — agrupó los 53 commits por H2/H3/H5 + audit-corrections, identificó los 6 archivos de identidad de versión, reconcilió los 9 gaps.
- **R1.3 reproducibilidad de gates** — ejecutó check, certify, mutation-gate, release-admission, falsificación de certificación de fuentes para el módulo plugin. El cert anchor disparó correctamente en la deriva legítima de fuentes y se re-ancló con razón documentada.
- **R1.4 bump de versión** — `0.3.0-alpha.1` → `0.3.0-alpha.2` en los 4 fuentes de identidad + 2 KDoc de consistencia. `ManifestVersionPrereleaseTest` es tag-genérico y no requirió edición.

## Gates y métricas (en `583036a`)

| Gate | Resultado | Detalle |
|---|---|---|
| `./gradle-jdk21.sh --no-daemon check` | **PASS** | 619 tests, 0 fallos, 0 errores, 0 omitidos |
| Mutation gate | **38/0/0** | 38 mutantes matados, 0 supervivientes, 0 inválidos; cada mutante compiló, murió por aserción, y restauró verde |
| Certify | **PASS** | SHA raíz `d5f2c2cb7bbc9b901aba0d29c907d952f6fda8ea` sobre 91 archivos Kotlin; baseline 606, ejecutado 619; 12 jars; SDK 0.47.0 (4 artefactos) |
| Release admission | **ADMISSIBLE** | Versión `0.3.0-alpha.2`, 8 jars para alpha.2, SBOM `pipelinek-policy-0.3.0-alpha.2.sbom.spdx.json` (JSON válido, 622 coordenadas) |
| UAT plugin externo | **PASS** (H4.2) | 16 OK + 4 VIOLATE, 7 pasos verdes (pre-r1, sigue válido) |
| Anchor test | **5/5 PASSED** (H5.1) | SHA `7873b723` en 91 archivos, baseline 42 (pre-r1, sigue válido para el marker) |

## Limitaciones conocidas (declaradas, no silenciadas)

1. **Cert anchor scope es root-only** — la falsificación R1.3.2 demostró que el SHA certificado no captura cambios en el módulo plugin. Decidido: NO extender en R1 (alpha.2); R4.2 (estable) extenderá el cert a todos los módulos distribuidos. **No se afirma cobertura completa de fuentes en este alpha.**
2. **Provenance `NOT_ATTESTED`** — la firma cosign/Sigstore OIDC no se ha emitido; requiere login interactivo del propietario. Marcada como R3.4.
3. **Compatibilidad SDK 0.48.0 `NOT_MEASURED`** — la matriz acredita compilación contra 0.48.0 pero no carga en host vivo. R3.1 ejecutará esta ruta.
4. **Replay hit/miss `NOT_MEASURED`** — la decisión ocurre en `DurableInvocationResolver` (fuera del repo). El fingerprint se verificó, pero el comportamiento real del host no. Marcado como R3.2.
5. **H2.4 design fork (GLOBAL execution path) — PARKED** — la decisión A/B sobre cómo extender el soporte GLOBAL no es técnica, es de producto. 14 sitios afectados, 1 de ellos en producción (`CanonicalPolicyJson:147`). El planner actual rechaza GLOBAL explícitamente (ley 12). R2.1 recuperará la propuesta original desde `M8_SPECIFICATION.md` / `POLICY_IR.md` para presentar la comparación al propietario del producto.

## Compatibilidad

- **SDK PipelineK:** 0.47.0 (verificado en host vivo); 0.48.0 (compilación, no carga en host).
- **JDK:** 21 (LTS, gate) y 25 (toolchain shell).
- **Kotlin:** 2.4.20.

## Identidad de la release

| Fuente | Valor |
|---|---|
| Build version (root) | `0.3.0-alpha.2` |
| Plugin `PLUGIN_VERSION` const | `0.3.0-alpha.2` |
| `.sddk/version-source.json` | `0.3.0-alpha.2` |
| README.md | `v0.3.0-alpha.2` |
| Tag anotado | `v0.3.0-alpha.2` (a crear en R1.5) |
| GitHub Release | prerelease, contendrá 8 assets + SBOM |

## Pasos de la human gate (R1.5)

1. **Crear la rama de integración** (locally, sin push) — ya planeado pero no ejecutado.
2. **Redactar la PR** — pendiente.
3. **Recibir autorización humana** — pendiente. La directiva R1–R4 requiere:
   - *"Las operaciones `git push`, creación de tags y publicación de GitHub Releases están sujetas a la autorización humana establecida por SDDK. Preparar todo y solicitar esa autorización antes de ejecutarlas; nunca saltarse el hook o la política."*
4. **Merge a `origin/main`** — pendiente.
5. **Verificar SHA remoto** — pendiente.
6. **Re-ejecutar gates sobre el SHA integrado** — pendiente.
7. **Tag anotado `v0.3.0-alpha.2`** — pendiente.
8. **GitHub Release prerelease** — pendiente.
9. **Descargar y verificar SHA-256 de los 8 assets publicados** — pendiente.

**Mientras esos pasos no se ejecuten, este alpha.2 es `READY_FOR_HUMAN_GATE`, no `DONE`.**
