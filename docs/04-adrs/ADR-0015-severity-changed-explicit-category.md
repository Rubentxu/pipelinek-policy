# ADR-0015 — `SEVERITY_CHANGED` es una categoría explícita, no un `else`

- Estado: aceptada
- WorkItem: `6fb5b421-1201-4773-b748-e6510da8b87b` (B4.5 / B4-T4)
- Cierra: B4.5 de `ROADMAP.md`

## Contexto

`DiffCategory.SEVERITY_CHANGED` existía como último `else` de la cadena de
categorización de `PolicyDiff.diffStates`. En el árbol anterior a este ADR esa
rama era **código muerto**.

Demostración (OBSERVED, exhaustiva sobre el modelo de estados): `RuleState` tiene
cuatro estados — `Violated`, `Passed`, `NotApplicable`, `Errored` — y
`diffStates` evalúa las 16 parejas ordenadas. Resultado: **0 de 16 parejas
alcanzaban `SEVERITY_CHANGED`**. Antes de la rama:

- `Errored` en ambos lados o en ninguno: emparejado arriba.
- `Violated` en un lado: emparejado arriba en ambas direcciones.
- `NotApplicable` en un lado: emparejado arriba en ambas direcciones.

Lo que llega al `else` es exactamente `{Passed, NotApplicable}` con un lado
`NotApplicable`, y esas dos direcciones ya se habían capturado. Una categoría
que se nombra en la superficie pública pero que ninguna entrada puede producir
es peor que no existir: invita a un consumidor a construir un switch sobre
`DiffCategory` con una rama que nunca se ejecuta, y sugiere que el diff es más
expresivo de lo que es.

Había un segundo defecto, menos visible. `diffStates` empezaba con
`if (sa == sb) return@flatMap emptyList()`. Un cambio **sólo** de severidad no
altera el veredicto, así que esa guarda lo habría descartado igualmente aunque
existiera una rama que lo reconociera. Es decir: **incluso un `SEVERITY_CHANGED`
correcto habría seguido sin emitirse nunca**. La categoría era doblemente
invisible, por la rama muerta y por el orden de la guarda.

## Decisión

1. `RuleSeverity` es un enum explícito con el conjunto normativo de
   `EVALUATION_SEMANTICS.md` §8 y `KOTLIN_POLICY_DSL.md` §9:
   `INFO | WARNING | ERROR | CRITICAL`. Ningún otro valor.
2. `Rule.severity: RuleSeverity?` es opcional y **sólo existe si el autor lo
   declara**. No se deriva de `ViolationCode`, no se infiere del mensaje, y no
   se deriva del modo de enforcement.
3. `PolicyReport` transporta `severities: Map<RuleKey, RuleSeverity>`. Una clave
   ausente significa «no declarado», y esa ausencia es significativa.
4. `SEVERITY_CHANGED` se emite **sólo cuando ambos lados declaran una severidad
   y difieren**. Una declaración unilateral no es un cambio: el autor del lado
   silencioso nunca afirmó ninguna severidad, así que no hay segundo valor que
   haya cambiado.
5. El resto de transiciones de estado conservan su precedencia. Un cambio de
   veredicto sigue dominando sobre un cambio de severidad.
6. El `else` final devuelve `emptyList()`. Todo cambio de estado está nombrado
   explícitamente; lo que llega ahí no es un cambio y **no** se archiva bajo
   una categoría inventada.

## Consecuencias

- `SEVERITY_CHANGED` pasa de código muerto a categoría alcanzable, y su única
  condición de emisión es la que el nombre anuncia.
- La ausencia de severidad deja de ser un default implícito. La mutación M3
  (defaulting de `null` a `INFO`) está matada por el test 04b precisamente
  porque ese default fabricaría cambios de severidad donde el autor no declaró
  ninguno.
- El diff no se expande: conserva como máximo una entrada por regla como máximo, con
  la misma semántica de conteo que antes.
- La severidad **no** participa todavía del enforcement ni del rollout. Son
  ejes distintos (`EVALUATION_SEMANTICS.md` §8 los lista por separado) y este
  ADR no los mezcla.
- Un bundle con severidad declarada cambia su `PolicyReport.digest`. Ningún
  literal de digest está fijado en los tests, y `PolicyReport` tiene un único
  sitio de construcción (`Evaluator.evaluate`), así que el radio de impacto es
  el esperado.

## Alternativas rechazadas

- **Derivar la severidad de `ViolationCode`.** Rechazado: no hay fuente
  normativa para tal mapeo, y una tabla inventada convertiría un dato del autor
  en una inferencia silenciosa.
- **Tratar la ausencia como `INFO`.** Rechazado por la misma ley: sería un
  default silencioso, y `INFO` es precisamente el valor que el autor no
  escribió.
- **Emitir `SEVERITY_CHANGED` cuando un lado declara y el otro no.** Rechazado:
  no hay transición entre «declarado» y «no declarado»; es un hueco de
  información, no un cambio. La decisión del caller sobre si publica o no ese hueco le
  corresponde, no al comparador.
- **Mantener la categoría pero documentarla como reservada.** Rechazado: deja
  la superficie intacta diciendo algo falso sobre lo que la
  superficie puede expresar.

