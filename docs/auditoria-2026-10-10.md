# Auditoría técnica · pipelinek-policy @ f06e045

**Repositorio** Rubentxu/pipelinek-policy · rama `feat/m10-cert`
**HEAD auditado** `f06e045` (origin idéntico, divergencia 0/0)
**main** `3d35f49` · la rama está 40 commits ahead, 0 behind
**Modo** read-only · 0 ficheros modificados
**Base** código actual, no documentos de estado

---

## 1. Informe ejecutivo

**Veredicto: no admisible para release de producción.**

La base arquitectónica es sólida y poco común. Hexagonal real, kernel puro sin una sola fuga de I/O, 8.505 líneas de producción con el fichero mayor en el 9 %, ratio test/main de 1,26, detekt verde y cero TODOs en producción. Eso es un proyecto bien construido.

El bloqueo no es la calidad del código. Son cuatro cosas concretas y verificables:

1. **El CLI no implementa la mitad del gobierno.** `CheckCmd` tiene **cero** referencias a `WaiverMatcher`, `AuthorityRegistry` o `LayerComposer`, y no expone `--waivers` ni `--enforcement`. El plugin sí usa el intérprete compartido; el CLI no aplica waivers ni capas. Un usuario del CLI obtiene un veredicto que el plugin no.

2. **No existe CI.** Cero workflows en `.github/`. Todo el gate depende de que alguien ejecute gradle a mano. Nada impide publicar una release sin que la suite se ejecute nunca.

3. **El presupuesto de ingress no existe.** `ResourceIngressLimits.kt` no está. El host lee y decodifica `resourceBase64` sin techo. `tasks.md` lo declaraba precondición de B4-T6, y T6 se construyó sin él.

4. **Los gates de certificación M10 son decorativos.** El marcador certifica `5a9162b` con HEAD real `f06e045` y ningún test los compara. El CSV se "pariticea" contra un árbol construido a mano. Una caracterización compara `f(x)` consigo misma.

Además, dos defectos que corrompen en silencio: decodificación UTF-8 byte-a-char, e identidad simple en datasets que colisiona entre policies.

---

## 2. Scorecard

| Área | Nota | Estado |
|---|---|---|
| Arquitectura hexagonal | 9/10 | Frontera real y verificada por test |
| Pureza del kernel (ley 5) | 10/10 | Cero I/O en `kernel/` |
| Profundidad de módulos | 9/10 | Deep modules; el kernel expone 3 declaraciones |
| Solapamiento CLI/plugin | 3/10 | El CLI omite waivers, autoridad y capas |
| CI/CD | 0/10 | Sin workflows |
| Cobertura de código | DESCONOCIDA | Sin JaCoCo ni Kover |
| Integridad de la certificación | 2/10 | Gates que no pueden fallar |
| Calidad estática | 9/10 | Detekt verde, 0 TODO en producción |
| Cantidad de tests | 8/10 | 437 verdes, ratio 1,26 |
| Versiones coherentes | 2/10 | `0.1.0` vs `0.2.0-M2` |

---

## 3. Hallazgos con evidencia

| Sev | Ubicación | Evidencia | Impacto | Recomendación |
|---|---|---|---|---|
| **P0** | `cli/commands/CheckCmd.kt` | 0 refs a `WaiverMatcher`/`AuthorityRegistry`/`LayerComposer`; sin flags `--waivers`/`--enforcement` | CLI y plugin deciden distinto con los mismos inputs. El roadmap lo prohíbe explícitamente | B4-T10: enrutar ambos por `PolicyGovernanceKernel` |
| **P0** | `decoders/csv/CsvRowSource.kt:106` | `sb.append(c.toInt().toChar())` — byte→char 1:1 | Corrompe en silencio todo no-ASCII, sin excepción ni warning | Decodificación UTF-8 estricta; test multibyte que falle antes |
| **P0** | `kernel/governance/` (ausente) | `ResourceIngressLimits.kt` no existe; `readBytes()` en `StreamCmd:155,159` | Sin techo de memoria en el host | B4-T5: 64 MiB defecto, 256 MiB techo, guard O(1) antes de decodificar |
| **P1** | `kernel/dataset/DatasetShape.kt:73` | `linkedMapOf<String, DatasetShape>()` + `shapes[rule.id]` | Dos policies con el mismo `ruleId` comparten contadores | B5.5: propagar `RuleKey` a shape, plan, acumuladores y report |
| **P1** | `cli/cert/CertificationAnchorTest.kt` | marcador `5a9162b` vs HEAD `f06e045`; 0 refs a `certified-sha` | La certificación no corresponde al código publicado | B6.1: manifest generado sobre el SHA real, con invalidación |
| **P1** | `cli/cert/CsvCharacterizationTest.kt:83` | `assertEquals(reportStreamDigest(rows), reportStreamDigest(rows))` | Compara una función consigo misma: verde por construcción | B6.3: referencia independiente o paridad entre dos rutas |
| **P1** | `cli/cert/CsvParityTest.kt:129` | evalúa `expectedTree()` hecho a mano, no el CSV decodificado | No prueba paridad de evaluación multiformato | B6.3: evaluar la salida real del decoder; subconjunto comparable versionado |
| **P1** | `scripts/m6/uat-external-distribution.sh:17` | `PIPELINEK_REPO="${…:-/var/home/rubentxu/…}"` | Ruta local hardcodeada en un gate de release | Parametrizar y exigir el SHA del artefacto |
| **P1** | `build.gradle.kts:16` vs `plugin:63` | `version="0.2.0-M2"` vs `PLUGIN_VERSION="0.1.0"` | Versión y plugin contradicen; riesgo al publicar | Derivar una sola versión y propagarla |
| **P1** | `.github/` | no existe `workflows/` | Nada impide publicar una release sin ejecutar la suite | Workflow con `check` en cada push y bloqueo de tag |
| **P2** | `plugin/PolicyCheckEvent.kt` | `$SEP` sin escape; `toBooleanStrictOrNull() ?: false` | Wire débil; un booleano malformado se vuelve `false` en silencio | B6: escape estructural y rechazo explícito |
| **P2** | proyecto completo | sin JaCoCo ni Kover | La cobertura real es DESCONOCIDA, no "alta" | B6: medir antes de certificar |

---

## 4. Verificación del informe de auditoría previo

Recibí ese mismo informe con HEAD `25d440f`. **Sus cuatro hallazgos principales están cerrados** en `f06e045`, comprobado en el código y no por el historial:

- `BundleIrCanonicality.identify()` prueba CURRENT antes del legacy.
- `Layers.kt` tiene 10 referencias a `AuthorityRegistry` y `AuthorityLacksCapability`.
- `DomainIoPurityTest` usa `walkTopDown()` con assert de cobertura.
- La precedencia `REFUSED > ERRORED > VIOLATED` está restaurada, con 8 falsificaciones.

Lo que **sigue abierto** de ese informe: `readBytes()`, identidad simple en datasets, presupuestos de ingress y los problemas de certificación CERT-01..07.

**Corrección propia.** Mi heurística de "shallow modules" marcó seis ficheros por tener cero exports. Era falso: `ResourceId.kt` tiene 21 líneas y una invariante real. La medida era demasiado cruda; los módulos son profundos.

---

## 5. Riesgos

| Riesgo | Probabilidad | Severidad | Mitigación |
|---|---|---|---|
| Release publicada con la suite nunca ejecutada | Alta | Crítico | CI como prerrequisito de tag |
| Dos veredictos para los mismos datos, según eladaptador | Alta | Crítico | Un solo kernel de gobierno |
| Corrupción silenciosa de datos no-ASCII | Alta | Alto | UTF-8 estricto + test de mutación |
| OOM por entrada no confiable | Media | Alto | Presupuesto de ingress tipado |
| Certificación sobre SHA que no es el publicado | Media | Alto | Manifest generado e invalidado |

---

## 6. Diagnóstico

### Causas raíz

1. **Divergencia de adaptadores.** El enforcement se unificó en el kernel, pero la migración de los adaptadores quedó a medias. El plugin avanzó; el CLI, no. Es la misma clase de defecto que B0.5: la intención documentada es correcta, la implementación se quedó en un lado.

2. **Aceptar un fix sin reconciliar sus precondiciones.** T6 se aprobó con la suite verde y sin sus precondiciones declaradas. La forma era correcta; la cobertura no. Es un fallo de proceso, no de código.

3. **La certificación es declarativa, no ejecutable.** El patrón `cert/SHA.txt` + un test que lo lee existe desde antes y nadie lo questionó hasta ahora. Un gate que no puede fallar no es un gate.

4. **El camino feliz dominionó los tests.** Los defaults son ASCII, un solo policy, un solo bundle. Los defectos de identidad y de UTF-8 viven exactamente en esas fronteras.

---

## 7. Plan de continuación

### Quick wins (esfuerzo bajo, impacto alto)

1. **CI workflow**: `./gradle-jdk21.sh check` en cada push, con el tag bloqueado sin build verde. Cierra el riesgo más alto de la lista.
2. **Derivar la versión** a una sola fuente. Elimina la contradicción `0.1.0` vs `0.2.0-M2`.
3. **Parametrizar** `PIPELINEK_REPO` y eliminar la ruta local del gate.
4. **Anclar la certificación al SHA real**: el test debe fallar si el marcador no coincide con HEAD. Convierte un gate decorativo en uno real con tres líneas.

### Acciones estratégicas

5. **B4-T10 · `PolicyGovernanceKernel`.** Un solo módulo puro al que CLI y plugin delegan, con `now: Instant` y el registro de autoridad por parámetro. Elimina el P0 de divergencia y es la precondición de la release.
6. **B4-T5 · `ResourceIngressLimits`.** 64 MiB defecto, 256 MiB techo, `maxBase64Chars` derivado para que el guard sea O(1) sobre `String.length` antes de decodificar.
7. **UTF-8 estricto** en `CsvRowSource` y `JsonlSource`, con test multibyte que falle antes del fix.
8. **B5.5 · `RuleKey` en el kernel de datasets**, con un test de dos policies que comparten `ruleId`.
9. **B6.1 · certificación real**: manifest generado en el build sobre el SHA ejecutado, con invalidación al cambiar la identidad.
10. **B5.2/B5.3 · streaming incremental**: sustituir `readBytes()` por lectura incremental es el bloque más grande de B5.

### Precondiciones de arranque

Ninguna decisión arquitectónica pendiente. `PolicyGovernanceKernel` no necesita ADR porque tasks.md ya especifica su forma, y la ley 5 y la ley 10 lo fuerzan a ser puro y parametrizado.

---

## 8. Información que falta

- **No hay medición de cobertura.** Cualquier afirmación sobre "tests suficientes" sería suposición.
- **No ejecuté el UAT de PipelineK instalado**: requiere la distribución externa y su SHA.
- **No hay ADR para `PolicyGovernanceKernel`.** tasks.md especifica su forma, pero la frontera deserves registro formal.
- **`DiffCmd` no fue inspeccionado en detalle.** tasks.md afirma que usa `corpus.first()`; no lo he verificado en el árbol.
- **Este informe no ejecutó el full `check`.** Corrí `detekt` en verde y reutilicé los resultados de `test` de la verificación anterior (437 tests, 0 fallos, 0 errores, 0 skips).