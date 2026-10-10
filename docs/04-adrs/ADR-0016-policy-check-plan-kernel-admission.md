# ADR-0016 — `PolicyCheckPlan`: el kernel decide la admisibilidad, el host ejecuta el decode

- Estado: aceptada
- WorkItem: `6fb5b421-1201-4773-b748-e6510da8b87b` (B4.6 / B4-T6)
- Cierra: B4.6 de `ROADMAP.md`
- Precedente: ADR-0015 (misma disciplina: una categoría nombrada debe poder emitirse)

## Contexto

La secuencia de admisión de `policy.check` vivía íntegra en el **adaptador de
plugin**, dentro de `PolicyCheckStepDefinition.evaluate(input)`:

```text
Base64(resource) → Base64(bundle) → ResourceFormat.valueOf → búsqueda de decoder
→ decode → BundleVerifier.verifyPacked → IrRuntimeAdapter.evaluate
```

Cada fallo devolvía `PolicyCheckOutput.refused("...")` con una cadena libre. Es
decir: **el host decidía qué es admisible**, y esa decisión era irrevocable para el
resto de la comprobación.

`ROADMAP` B4.6 exige lo contrario: el plan se **calcula** en el kernel y nunca
viaja serializado en el input, «si viajara, el host afirmaría su propia
validez».

## Por qué (a) era posible sin violar la ley 6

La pregunta «¿el plan necesita un decoder JSON?» tiene la respuesta equivocada.
El kernel nunca necesita una implementación: necesita el **contrato**.

El grafo de módulos ya lo garantiza (OBSERVED en `settings.gradle.kts` y los
`build.gradle.kts` de cada submódulo):

| módulo | depende de |
|---|---|
| core (`:`) | `kotlin-stdlib` y nada más |
| `policy-decoders-json` | core + `com.fasterxml.jackson.core` |
| `policy-decoders-yaml` | core + SnakeYAML engine |
| `policy-decoders-csv` | core, stdlib |
| `policy-decoders-map` | core, stdlib |

La flecha apunta **en un solo sentido**: los decoders dependen del core, nunca
al revés. Por eso la ley 6 no obliga a recortar la secuencia: todo lo que la
secuencia necesita, salvo la llamada a `decode(bytes)`, ya vive en el core y es
stdlib puro.

Lo que el core ya posee:

- `com.pipelinek.policy.decoder`: el SPI completo — `ResourceDecoder`,
  `DecodeResult`, `DecodeRefusalCode`, `DecoderDescriptor`, `DecodeOptions`.
- `ResourceFormat`: enum plano en `decoder/ResourceDocument.kt`.
- `com.pipelinek.policy.bundle`: `BundleVerifier`, `PolicyBundle`,
  `VerifiedBundle`, `BundleIrCanonicality`. `verifyPacked` es puro: aritmética
  de `ByteBuffer`, UTF-8 estricto vía `Charsets.UTF_8.newDecoder()` y
  `CanonicalPolicyJson`. Todo stdlib.
- Base64 es `java.util.Base64`, del JDK.

### La frontera que importa

No es «el core no puede conocer a los decoders». Es:

> **El core puede conocer el contrato del decoder; no puede SELECCIONAR una
> implementación de decoder.**

El core posee la lista de formatos admitidos y decide si un formato es
admisible. El host posee las implementaciones y ejecuta el decode que el plan
le ordenó ejecutar, sobre entradas que el kernel ya bendijo. La ley 6 sigue
satisfecha sin excepción, y no por cortesía: la flecha del grafo de módulos la
impone estructuralmente.

## La afirmación que el plan NO hace

Este ADR es explícito sobre un límite que fácilmente se pierde al escribir el
código:

> **El plan NO valida el payload del recurso.**

Un plan que sólo puede afirmar que los bytes son Base64 válido, que el bundle
está admitido y que el formato declarado es conocido, **no** ha comprobado que
los bytes sean JSON bien formado. Eso es la respuesta del decoder, y llega
después. Por eso:

- El plan NO lleva una comprobación de número de documentos. Esa comprobación
  requiere el resultado del decode.
- El nombre del tipo no puede sugerir más de lo que su cuerpo entrega. Un tipo
  llamado como si validara el payload sería `wouldDeny` otra vez: un nombre que
  miente sobre su cuerpo.

La afirmación honesta es: *«estas entradas son admisibles, y esto es
exactamente lo que el host debe hacer ahora»*. El resultado del decode llega
después como éxito o como rechazo tipado (`DecodeRefusalCode`).

### Corrección registrada

Al plantear esta decisión se afirmó primero que «el kernel decide la
admisibilidad», sin más matiz. Eso **sobreafirmaba el alcance del kernel**: la
admisibilidad de la *forma* no es la validez del *contenido*. La versión
corrigida es la de este ADR, y la corrección forma parte de su historia para
que no se repita.

## Decisión

1. `PolicyCheckPlan` es un sealed interface en `kernel/governance`, **puro y
   stdlib-only** (ley 5, ADR-0011 D11.1). Sin reloj, sin I/O.
2. Sus dos estados son explícitos y exhaustivos:
   - `PlanReady`: las entradas son admisibles y el host **debe** ejecutar
     exactamente el decode indicado, sobre los bytes indicados.
   - `PlanRefused`: el plan no es calculable. Es `REFUSED` también en `SHADOW`,
     porque un plan no calculable es un fallo operacional, no un veredicto de
     política.
3. El plan se **calcula siempre** a partir de las entradas admitidas. Nunca se
   lee del input. No existe campo de plan en `PolicyCheckInput`, y si existiera
   sería ignorado (ver «Falsificación» más abajo).
4. Los rechazos del plan son **tipados**: un enum cerrado, no cadenas libres
   (ley 9).
5. El adaptador **renderiza** el enum a la cadena existente en el límite, y esa
   cadena **se llama por el nombre del enum**.

### El enum de rechazos y el mapeo de cadenas

`PolicyCheckPlanRefusal`, con el valor de cadena igual a `name`:

| constante | antigua cadena (prosa) | significado |
|---|---|---|
| `RESOURCE_NOT_BASE64` | `resource is not valid Base64` | el recurso no decodifica de Base64 |
| `BUNDLE_NOT_BASE64` | `packed bundle is not valid Base64` | el bundle no decodifica de Base64 |
| `UNKNOWN_RESOURCE_FORMAT` | `unknown resource format '<F>'` | el formato declarado no existe |
| `NO_DECODER_FOR_FORMAT` | `no decoder for format <F>` | ningún decoder registrado cubre el formato |
| `BUNDLE_REFUSED` | `bundle refused: <mensaje>` | la admisión del bundle falló |
| `RESOURCE_DECODE_REFUSED` | `decode refused: <CODE>` | el decoder rechazó el contenido |
| `MULTI_DOCUMENT_RESOURCE` | `expected exactly one resource document, got <N>` | el decode produjo un número de documentos distinto de uno |

El recuento `<N>` sigue apareciendo en la cadena, **detrás del nombre del enum**,
como diagnóstico. No es una segunda voz: el prefijo identifica la causa y cualquier
consumidor puede compararlo contra el enum. La alternativa —dejar el recuento fuera
— perdería el diagnóstico que el rechazo portaba.

**Distinción de competencia.** Los rechazos se emiten en dos sitios, y la tabla no
debe sugerir lo contrario:

- Los **calcula el plan**: `RESOURCE_NOT_BASE64`, `BUNDLE_NOT_BASE64`,
  `UNKNOWN_RESOURCE_FORMAT`, `NO_DECODER_FOR_FORMAT`, `BUNDLE_REFUSED`.
- Los **emite el host, después del decode**, porque su respuesta llega tarde y el
  plan no puede verla: `RESOURCE_DECODE_REFUSED`, `MULTI_DOCUMENT_RESOURCE`.

Viven en el mismo enum para que la superficie de rechazo de una comprobación tenga
una sola voz, pero no son del plan. `NO_DECODER_FOR_FORMAT` sí es del plan: es la
comprobación que el plan hace sobre el **conjunto declarado** de formatos que el host
afirma soportar, y no sobre las implementaciones registradas.

### Cambio visible en el cable

`PolicyCheckOutput.refused(reason: String)` **no cambia**: ni su firma, ni su
tipo de cable, ni su forma JSON. Lo que cambia son los **valores** de las cadenas
de rechazo que ahora llevan el nombre del enum como prefijo.

La auditoría de los puntos de llamada en `5f4352e` da **siete** rechazos
condicionales, no ocho: las dos últimas ramas (`bundle refused: <mensaje>` y
`bundle refused: <clase>: <mensaje>`) eran la misma causa alcanzada por dos rutas,
y ambas colapsan en el único `BUNDLE_REFUSED` que emite el plan. La afirmación
anterior de «ocho cadenas» era una cuenta sin verificar.

La prosa antigua **no** se conserva como alternativa. Dos vocabularios vivos
se vuelven ambos load-bearing, y ese es exactamente el fallo que este ADR
evita. La tabla anterior es la auditoría del mapeo; el vocabulario nuevo es el
único que existe a partir de este commit.

Los códigos de rechazo visibles en el cable son un cambio aparte, con sus
consumidores y su historia de compatibilidad, y pertenecen a B6.

### El reloj

**No hay reloj inyectado.** No existe ninguna decisión dependiente del tiempo
en el plan en el momento de escribir esto: `verifyPacked` no tiene reloj, Base64
no lo tiene, `ResourceFormat` no lo tiene. Lo más cercano es
`BundleAdmissionLimits`, que es un **presupuesto**, no una ventana.

Un reloj inyectado sin una decisión que lo use es un parámetro que aparenta
ser evidencia de un requisito que nadie tiene. Si aparece una decisión
dependiente del tiempo — una ventana de admisión, una expiración, un intervalo
de validez que el plan deba evaluar — entonces hará falta el reloj, inyectado.

## Falsificación

La propiedad central es «el plan se calcula, nunca se acepta del host». Un test
que sólo comprueba que el kernel produce un plan correcto para entradas
buenas **pasaría mientras la ruta del plan suministrado por el host siguiera
viva y permisiva**. Por eso la prueba debe demostrar que un plan suministrado
por el host se **ignora**.

La batería de mutación debe matar, como mínimo:

1. Cortocircuitar el plan cuando el host suministra uno (usar el del host).
2. Aceptar un formato no conocido en lugar de rechazar.
3. Perder el tipo del rechazo y devolver una cadena libre.

## Alternativas rechazadas

- **(b) Calcular sólo la mitad de política.** Rechazado: el host seguiría
  decidiendo la admisibilidad, que es la mitad que importa. «Calculado por el
  kernel, nunca suministrado por el host» sólo sería cierto para la mitad que
  el host no podía influenciar.
- **Un plan que afirme validar el payload.** Rechazado: no se puede construir
  sin arrastrar un decoder al core, violando la ley 6, y el nombre mentiría
  aunque se construyera.
- **Conservar la prosa antigua junto a los nombres del enum.** Rechazado: dos
  vocabularios, ambos load-bearing.
- **Añadir el reloj «porque el roadmap lo menciona».** Rechazado: un parámetro
  sin uso es evidencia falsa de un requisito.
