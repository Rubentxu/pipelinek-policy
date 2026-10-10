# Estrategia de testing

## 1. Pirámide orientada a propiedades

```text
             installed UAT / external plugin
           architecture acceptance tests
        integration / differential fixtures
      property-based + mutation / fuzz
   unit laws sobre ADTs y funciones puras
```

## 2. Regla: afirmar comportamiento, no un proxy

Una prueba sólo vale si falla cuando el **comportamiento** falla. Prohibido
escribir una prueba que reimplemente la fórmula, vuelva a derivar el valor, o
inspeccione el código que intenta verificar, y luego llame "verde" a esa
coincidencia.

La trampa tiene siempre la misma forma: el test vuelve a calcular la respuesta
con la misma lógica que el código de producción, así que ambos se equivocan
juntos. Un proxy no es una prueba débil, es una prueba que no falla.

### Cómo se reconoce

- El test **reproduce** la fórmula en vez de **invocar** al sujeto.
- El test comprueba la **firma**, el **cuerpo**, o una **estructura**, sin
  ejecutar el camino que el usuario recorre.
- El test pasa igual cuando el defecto está presente, porque afirma algo
  distinto de lo que el defecto rompe.
- El test sólo afirma el **veredicto**, y no el **valor**, cuando el valor es
  lo que el cambio altera.

### Cómo se cumple

1. Ejecutar el sujeto real contra entradas reales. Si el sujeto es una
   fachada, invocarla contra un contexto real, no reflectarla.
2. Si la propiedad es de tiempo de compilación (una firma, una aridad, una
   llamada existente), la prueba puede ser de compilación, y entonces la
   evidencia es «esto no compila», no «el test falló».
3. Si la propiedad es de tiempo de ejecución, afirmar el valor observable en
   el límite más externo que la contiene.
4. Antes de aceptar una prueba nueva, mutar el cuerpo que vigila y comprobar
   que **muere**. Si sobrevive, la prueba está midiendo un proxy.

### Casos trabajados

Estos cuatro casos están vividos, no hipotéticos.

**B3.6 — el digest re-derivado en el propio test.** La prueba de coherencia
entre la huella de la CLI y la del kernel **recomputaba** el digest de la CLI
en lugar de invocar a `WaiverMatcher`. Habría pasado aunque los dos digests
divergieran, que es justo lo que pasaban. Con el digest re-derivado, la
propiedad era tautológica. Arreglado: el test invoca al matcher. Y la mutación
que cambió `RuleKey.value` mató la prueba reescrita, lo que prueba que ahora
la propiedad es real.

**B4-T4 — la categoría muerta detrás de un `return` temprano.** La categoría
`SEVERITY_CHANGED` tenía su propio test, y ese test pasaba. Motivo: el
`if (sa == sb) return` descartaba los cambios de severidad **antes** de la
categorización. El test afirmaba «la categoría existe»; no afirmaba «la
categorización se alcanza para un cambio sólo de severidad». Bajo la primera
lectura la cobertura era aparente. Al enumerar exhaustivamente 4 estados × 16
pares ordenados, el `else` se alcanzaba **0** veces. Arreglado: la severidad se
comprueba antes del cortocircuito por estado.

**B4-T5 — la firma reflectada sin ejecutar la fachada.** La prueba de que
`StageScope.policyCheck(...)` expone `enforcement` usaba reflexión y leía la
firma y el cuerpo del método. Pasaba con el cuerpo que fuerza `ENFORCED`
literalmente, y pasaba con el valor por defecto eliminado. Afirmaba que la
firma tenía el parámetro, no que la fachada lo usara. Arreglado: el test
invoca la fachada contra un `StageScope` real. La mutación que fija `ENFORCED`
en el cuerpo mata la prueba 05d; quitar el valor por defecto rompe las
llamadas DSL existentes.

**B4-T6 — el veredicto afirmado y el valor no.** El test «unknown format is
refused» afirmaba `verdict == REFUSED`. Pasaría con cualquier cadena, incluida
la prosa antigua, que es exactamente lo que el cambio de contrato tenía que
eliminar. Añadido: la prueba afirma también `refusalReason`, y la mutación que
revierte a la prosa en el cable la mata.

## 3. Unit laws

### ValueTree

- canonical equality;
- digest independent of map insertion order;
- integer/decimal distinction;
- null preserved.

### Selector

- composition associative donde aplica;
- field path deterministic;
- `each` never reorders arrays.

### Evaluator

- purity;
- same input -> same output;
- no mutation of input;
- missing/null/type mismatch laws.

## 4. Property-based tests

Generar árboles arbitrarios y comprobar:

- encode/decode round trip;
- canonical digest stability;
- explicit path select equivalence;
- renderer no altera semantics;
- order invariants.

## 5. Differential tests

### Format differential

Mismo semantic object serializado a JSON/YAML produce mismo ValueTree/report.

### DSL differential

Explicit API vs FIR sugar produce mismo IR digest.

### Evaluator differential

Reference simple interpreter vs optimized interpreter si aparece una optimización futura.

## 6. Mutation testing

Targeted mutants por propiedad crítica. No convertir porcentaje global en requisito de producto.

Esta sección **depende** de la regla de la sección 2: un mutante que sobrevive
no es un mutante pendiente, es la prueba de que el test vigente mide un proxy.
La cuenta que importa no es el porcentaje, es si algún mutante plausible
sigue vivo.

Ejemplos:

- GTE→GT;
- Missing→False;
- drop violation;
- waiver expiry ignored;
- closed shape accepts typo;
- purity checker permits File I/O;
- digest ignores parameter.

## 7. Compiler tests

- diagnostics golden;
- FIR dump;
- IR dump sólo si backend extension existe;
- box tests;
- incremental compile;
- daemon/no-daemon;
- supported Kotlin version matrix;
- supported IDE smoke.

## 8. Parser fuzzing

Especialmente:

- YAML anchors/aliases;
- duplicate keys;
- huge depth;
- weird unicode keys;
- malformed CSV quotes;
- decimals/exponents;
- surrogate/UTF-8 boundaries.

## 9. Testing cadence

Durante desarrollo:

- focused module/test class;
- nearest architecture tests.

Integración/release:

- full `./gradlew check` exact SHA;
- installed UAT;
- external PipelineK plugin consumer cuando M6+.
