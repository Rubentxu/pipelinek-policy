# Specification · b4-b5-b6-remediation

**Cycle** `p-dd1a1c7a7d448b0c/b4-b5-b6-remediation` · path A-min · phase specify
**HEAD de referencia** `aea22a04023198eac1e2c8082870ec79e7c61c74`
**Exploration** `docs/history/B4_B5_B6_EXPLORATION.md` (artifact `art-eecdac30b46d-d8d2b8fb`)

Cada requisito es **testable**: nombra el comportamiento observable y el criterio
de falsificación. Un requisito que solo se puede cerrar leyendo un documento no
es un requisito, es una nota.

---

## R1 · B5.3 · UTF-8 estricto en decoders (P0)

**Comportamiento.** Un payload UTF-8 multibyte se decodifica a la misma cadena
que el mismo texto en UTF-8. Ninguna ruta convierte byte→char 1:1.

**Falsificación.** Un CSV con `café`, un JSONL con `€` y un YAML con `😀` deben
producir exactamente esos caracteres. Con el código actual producen `cafÃ©`,
`â‚¬` y un surrogate suelto. El test debe FALLAR antes del fix por diferencia de
cadena, no por excepción.

**Alcance.** Verificado por `grep`, **cinco** sitios(byte→char 1:1), no dos:

- `policy-decoders-csv/.../csv/CsvRowSource.kt:106` y `:127` (fuente en streaming)
- `policy-decoders-csv/.../csv/CsvResourceDecoder.kt:290` y `:314` (materializado)
- `policy-decoders-json/.../json/JsonlSource.kt:40`

`policy-decoders-yaml` **no** está afectado: `YamlResourceDecoder.kt:70` ya usa
`String(bytes, Charsets.UTF_8)`. Esa es la referencia a seguir, no una excepción
que documentar: es la forma correcta que el resto del código debería tener.

**Nota de alcance.** CSV tiene **dos** rutas (materializada y streaming) con el
mismo defecto. Arreglar solo una deja la corrupción viva en la otra, que es
exactamente el fallo de paridad que B6.3 debe detectar.

**No hacer.** No añadir un flag `assumeLatin1`: eso convierte corrupción
silenciosa en corrupción declarada, no la arregla.

---

## R2 · B4.7 · El presupuesto de ingress se aplica en host

**Comportamiento.** Un resource o bundle cuya longitud codificada exceda el
presupuesto es **REFUSED** antes de decodificar. El refusal viaja por el
vocabulario tipado existente, no como excepción ni como código libre.

**Falsificación.** Un host que reciba un `resourceBase64` de 300 MiB con el
presupuesto por defecto debe devolver `RESOURCE_ENCODED_TOO_LARGE` y no
materializar los bytes. Con el código actual lo materializa y sigue.

**Alcance.** `PolicyCheckPlanRequest` recibe el presupuesto por parámetro
(ley 10); `PolicyCheckPlan.compute` lo consulta antes de `decodeBase64`;
`CheckCmd` y el adapter del plugin dejan de decodificar sin él. Los 5
`readBytes()` del CLI consultan `Files.size` antes de leer.

**Invariante.** El tipo `ResourceIngressLimits` de `aea22a0` no se modifica: este
requisito lo **conecta**, no lo reescribe.

---

## R3 · B5.5 · Identidad compuesta en datasets (P1)

**Comportamiento.** Dos policies distintas que declaren el mismo `ruleId` tienen
shape, plan, acumuladores y resultados separados. La clave es `RuleKey(policySetId,
policyId, ruleId)`.

**Falsificación.** Un `PolicySet` con `policyA.r1` y `policyB.r1` debe producir
dos entradas distintas en `shapesByRule` y contadores independientes. Con el
código actual la segunda sobrescribe la primera y ambos acumuladores suman
juntos.

**Alcance.** Los 8 sitios de `rule.id` en `kernel/dataset`
(`DatasetShape.kt:76`; `StreamingEvaluator.kt:118,138,156,157,164,165,166`).
`RuleKey` ya existe desde B1 y ya se usa en `StreamingEvaluator.kt:117`.

**Invariante.** El digest del reporte **no cambia** para el caso de una sola
policy. Si cambia, es una ruptura de contrato, no una mejora.

---

## R4 · B5.6 · El presupuesto de acumuladores es tipado (P1)

**Comportamiento.** Exceder el cap de un `Accumulator` produce un valor de
presupuesto tipado, no una excepción. El evaluator lo refleja como
`RuleOutcome`, no como crash.

**Falsificación.** Un `Accumulator.Sum` que supera su cap debe devolver
`RuleOutcome.Error` con código de presupuesto y el pipeline debe continuar. Con
el código actual lanza `BudgetExceededException`.

**No hacer.** No crear un segundo patrón de presupuesto: migrar al de R2.

---

## R5 · B4.9 · UAT real con plugin instalado (P1)

**Comportamiento.** 20 ficheros reales se procesan a través del plugin
instalado con `--plugin-jar`. La precondición se verifica **por comportamiento**:
una ruta inválida produce admisión de plugin con RC=2.

**Falsificación.** El harness debe fallar si el plugin no está instalado, hoy en
día no puede distinguirlo de una instalación vieja.

**No hacer.** No parsear `--help` para decidir si el plugin está instalado; un
`installDist` normal puede salir 0 sin reconstruir, así que el build usa
`--rerun-tasks`.

---

## R6 · B6.1 · La certificación se ancla al SHA ejecutado (P1)

**Comportamiento.** El test de certificación **falla** si el SHA del marcador no
coincide con el HEAD ejecutado. Los informes son de la ejecución actual.

**Falsificación.** Cambiar el SHA en `cert/SHA.txt` a un valor arbitrario debe
hacer fallar la suite. Hoy `CertificationAnchorTest` pasa sin comparar nada.

**No hacer.** No contar ficheros `*Test.kt` en disco: eso cuenta ficheros, no
tests ejecutados.

---

## R7 · B6.3 · Paridad multiformato real (P1)

**Comportamiento.** `CsvParityTest` consume la **salida real** de cada decoder.
`CsvCharacterizationTest` compara contra una referencia independiente, no contra
sí mismo.

**Falsificación.** `assertEquals(reportStreamDigest(rows), reportStreamDigest(rows))`
es verde por construcción; debe desaparecer. La paridad debe fallar si el digest
de un decoder cambia.

---

## R8 · Release admission (gate, no ítem de código)

**Comportamiento.** B6.10 es PASS solo con suite verde sobre el SHA candidato
exacto, cero P0/P1 abiertos, UAT ejecutadas, matriz reproducible, mutaciones
críticas detectadas y documentación coherente.

**Regla.** Mientras R1, R2 o R3 estén abiertos, **no hay tag ni release**. Es
el gate global de `ROADMAP.md`, y la auditoría propia lo confirma.

---

## Decisión pendiente (no bloquea R1–R4)

**¿El CLI es superficie de producción o herramienta de desarrollo?** Mueve
B4-T10 entre *release blocker* y *P2*. M9 lo declara superficie de producción,
lo que lo haría bloqueante, pero esa lectura no está confirmada. Hasta que se
decida, B4-T10 se trata como P1 no bloqueante y **no** se cuenta como cerrado.

## Orden de ejecución

R1 → R2 → R3 → R4 → R5 → R6 → R7 → R8.

R1 primero porque corrompe datos en silencio y el resto construye sobre el mismo
capa de decoders. R2 segundo porque el presupuesto ya existe y solo falta
conectarlo: es el ratio valor/esfuerzo más alto del ciclo.
