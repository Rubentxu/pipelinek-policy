# H1.1 · La severidad declarada sobrevive al round-trip canónico

WorkItem: `ae702d1d-5aed-4e95-a9bb-b4bf87ae71a3` · cierra el P0 semántico que la
medición de H0 marcó como `DEFECTO VIVO` · commit `e44a1ae`.

## El defecto

`Rule.severity` es dato declarado por el autor, no algo que el evaluator
deriva. El kernel lo honraba en memoria:

- `Evaluator.kt` lo propaga al reporte,
- `PolicyDiff.kt` emite `SEVERITY_CHANGED` cuando cambia.

Pero el codec canónico no lo conocía. `CanonicalPolicyJsonWriter.policies()`
serializaba `id`, `message`, `expression`, `appliesWhen`, `code`, `expected`,
`actual`, `params` y `supersession`, y **no** `severity`. El decoder tampoco lo
leía.

La consecuencia era un reporte que mentía sobre su propia severidad:

| Momento | `severity` de una regla `CRITICAL` |
|---|---|
| En el proceso del autor, sobre el `PolicySet` en memoria | `CRITICAL` |
| Empaquetado en un bundle y decodificado por el plugin step | `null` ("no declarada") |

Y la forma más grave de la misma pérdida: **dos reglas que solo difieren en
severidad codificaban a bytes idénticos**. La información no estaba mal
ordenada ni mal formada: no estaba.

Un test que hubiera exigido "CRITICAL sobrevive" habría pasado igual con un
encoder que ignorase la severidad en ambos lados del round-trip, porque ambos
lados serían `null` sin más. Por eso los tests son asimétricos.

## RED observado antes del fix

```
$ ./gradle-jdk21.sh :test --tests 'com.pipelinek.policy.ir.PolicyIrSeverityRoundTripTest'
PolicyIrSeverityRoundTripTest > every normative severity survives ... FAILED
    severity INFO must survive encode/decode; got null ==> expected: <INFO> but was: <null>
PolicyIrSeverityRoundTripTest > two rules differing only in severity ... FAILED
    severity is not represented in the canonical encoding, so a CRITICAL rule and an INFO rule are the same bytes
PolicyIrSeverityRoundTripTest > severity appears in the encoded bytes ... FAILED
    {"irVersion":1,"languageVersion":"m3","policySetId":"set",...
PolicyIrSeverityRoundTripTest > an out-of-vocabulary severity is refused ... FAILED
    Expected an exception of class IrRefusal$CorruptEncoding to be thrown
5 tests completed, 4 failed
```

El quinto test (ausencia que se queda ausente) pasaba ya, y pasando estaba: sin
campo, no hay nada que defaulted.

## El fix

Dos cambios, uno por lado del codec.

**Writer.** Emite `severity` **solo cuando está declarada**, nunca como
default. La ausencia es dato significante: `PolicyDiff` solo reporta
`SEVERITY_CHANGED` cuando *ambos* lados declaran una, así que inventar un `INFO`
aquí haría que toda regla no declarada differiera de toda regla declarada.

**Decoder.** Lee `severity` como vocabulario cerrado y rechaza un nombre
desconocido con `IrRefusal.CorruptEncoding`. Ese rechazo es el punto, no un
adorno: el comportamiento que reemplaza degradaba a `null`, y `null` es
exactamente lo que significa "el autor nunca declaró severidad", así que una
severidad irreconocible habría podido disfrazarse de ausente. Sin
coerción silenciosa (ley arquitectónica 9).

## GREEN

```
$ ./gradle-jdk21.sh :test --tests 'com.pipelinek.policy.ir.PolicyIrSeverityRoundTripTest' --rerun-tasks
5 actionable tasks: 5 executed
BUILD SUCCESSFUL
```

`--rerun-tasks` es deliberado: una primera pasada green salió `FROM-CACHE`, y un
verde de caché no es un verde.

## Falsificación: mutantes D1 y D2

Añadidos al gate de B6.5, que ya distingue "muere por aserción" de "no compila"
de "sobrevive":

| id | mutación | por qué es la mutación interesante |
|----|----------|------------------------------|
| D1 | el writer deja de emitir `severity` | reinstroduce exactamente la pérdida original |
| D2 | el decoder degrada nombre desconocido a `null` | reinstroduce el enmascaramiento "irreconocible == ausente" |

```
== mutant: D1 declared severity must be encoded, not dropped in transit
    KILLED: assertion failure in TEST-com.pipelinek.policy.ir.PolicyIrSeverityRoundTripTest.xml
== mutant: D2 an unknown severity must be refused, never silently degraded to absent
    KILLED: assertion failure in TEST-com.pipelinek.policy.ir.PolicyIrSeverityRoundTripTest.xml

== B6.5 mutation summary
   killed:     9
   survived:   0
   invalid:    0
   PASS: every mutant compiled, died by assertion, and restored green
```

## Un defecto del gate de mutación que salió a la luz

El gate de mutación **borró el fix**. Su paso de restauración usa
`git checkout --`, que revierte el archivo a `HEAD`. Con el fix de severidad sin
commitear en ese mismo fichero, el run lo revirtió y lo eliminó; no estaba en
ningún stash y hubo que reaplicarlo a mano.

Dos correcciones, ambas **observadas** y no supuestas:

1. `assert_mutable_files_are_committed` aborta con exit 3 **antes de mutar** si el
   fichero objetivo tiene cambios sin commitear: un gate no puede destruir
   trabajo sin avisar.
2. La primera versión de ese guard dejaba un mutante A3 a medias en
   `Evaluator.kt` (`if (outcome || !outcome)`), porque `RESET` ya estaba a 1
   cuando el guard disparaba. Ese fichero llegó a `git add` como si fuera
   trabajo real. El guard ahora restaura antes de salir.

Efecto colateral útil: la condición que detecta "el fichero está sucio" resultó
ser exactamente la que distingue "el gate puede correr" de "el gate destruiría
tu trabajo".

## Recalibración del ancla

`cert/SHA.txt` → `certified-sha: 44a5d89c93a633f765f70b0a193f7a44b8bb0d89` y
`baseline-tests: 508 → 513`. Los cinco tests nuevos son lo que movió el árbol, y
`CertificationAnchorTest.01b` falló con su mensaje previsto ("find out what
moved"). El valor se escribió desde la salida real de la pasada, no desde un
script que sobrescribe lo que encuentra.

```
$ ./gradle-jdk21.sh check
BUILD SUCCESSFUL in 2m 1s
36 actionable tasks: 3 executed, 33 up-to-date

$ bash scripts/m6/certify.sh
· source tree: 44a5d89c93a633f765f70b0a193f7a44b8bb0d89 (82 files)
· executed: 513 tests, 0 failures, 0 errors, 0 skipped
· marker baseline-tests: 513, executed: 513
```

## Lo que este receipt NO afirma

- **No** afirma compatibilidad hacia atrás con bundles ya publicados: un bundle
  previo a este commit no lleva `severity` y sigue decodificando a `null`, que
  es el valor correcto para "nunca declarado". Es aditivo por construcción, pero
  eso no es lo mismo que haber medido un bundle viejo real contra este decoder.
- **No** afirma que la severidad llegue hasta el **reporte** del plugin. Lo
  certificado aquí es el round-trip del IR canónico. Que `Evaluator` la propague
  desde un documento decodificado es el siguiente eslabón y sigue sin medirse
  de extremo a extremo.
- **No** cierra H1 entero. H1.2 (gobierno extremo a extremo) sigue `PARCIAL`.
- **No** cubre el resto de la superficie de mutación: 9 mutantes nombrados no
  son una garantía general de cobertura (H5.3).
