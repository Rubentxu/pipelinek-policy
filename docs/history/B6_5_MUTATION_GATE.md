# B6.5 · Mutation testing gate — cierre

WorkItem: `b4-b5-b6-remediation` · H5.2 · cierra el ítem que la medición de H0
dejó "EN CURSO" y que bloqueaba el trabajo siguiente.

## Qué se certifica

`scripts/m6/mutation-gate.sh` no es un medidor de cobertura. Es un gate que
exige, para cada mutante semántico, cuatro cosas en orden:

1. el mutante **compila** (un mutante que no compila no es un mutante
   semántico, es un mutante inválido, y se cuenta como fallo del gate);
2. el test dirigido **falla por aserción** (no por error de compilación, no
   por excepción de infraestructura);
3. la fuente original queda **restaurada**;
4. el test vuelve a **verde** sobre el código restaurado.

El paso 4 es el que da valor al 2: si la restauración no devolviera el árbol a
verde, el "7/7 muerto" de la pasada anterior no significaría nada, porque
estaríamos certificando un árbol que nunca volvimos a ver limpio.

## Resultado del run de cierre

```
== B6.5 mutation summary
   killed:     7
   survived:   0
   invalid:    0
   PASS: every mutant compiled, died by assertion, and restored green
```

Los 7 mutantes:

| id | mutación | test dirigido |
|----|----------|---------------|
| A1 | `appliesWhen` devuelve siempre `true` | `RuleEvaluationTest` |
| A2 | `appliesWhen` devuelve siempre `false` | `RuleEvaluationTest` |
| A3 | veredicto del evaluator invertido | `RuleEvaluationTest` |
| B1 | `BigDecimal` → `Double` (pérdida de precisión) | `CanonicalDigestTest` |
| B2 | se aceptan claves duplicadas (last-wins) | `CanonicalDigestTest` |
| C1 | mappings sin orden canónico de claves | `CanonicalDigestTest` |
| C2 | sequences sin orden canónico | `CanonicalDigestTest` |

## Falsificación del gate (negative controls)

Un gate que solo se ha ejecutado sobre código correcto no está verificado: puede
estar siempre verde por construcción. Se registraron dos controles negativos,
cada uno sobre una copia del script, restaurado después.

### Control 1 — el test dirigido no mira lo que dice mirar

Se reapuntó el test objetivo de C2 (`*CanonicalDigestTest*`) a una clase de test
que no cubre canonicalización de sequences (`*PolicyDiffTest*`), dejando la
mutación intacta.

```
== mutant: C2 canonical form must preserve sequence order
    SURVIVED: test still green
   killed:     6
   survived:   1
   FAIL: 1 mutant(s) survived — the targeted test does not detect them
GATE EXIT=1
```

El gate detecta que el test dirigido no detecta. Un gate que declarara 7/7
aquí estaría mintiendo.

### Control 2 — una mutación inválida no es una muerte

Se reemplazó el cuerpo del mutante C1 por una llamada que no existe, de modo que
el mutante no compila:

```
== mutant: C1 canonical form must sort mapping keys
    INVALID: mutant does not compile
   killed:     6
   survived:   0
   invalid:    1
   FAIL: 1 mutant(s) were not authentic semantic mutants
GATE EXIT=1
```

El gate no acepta "no compila" como "detectado". Esta era exactamente la
debilidad por la que los 2 inválidos del run anterior podían pasar inadvertidos.

## Cobertura que hubo que añadir durante el cierre

Falsificar el gate contra el código es lo que destapó dos huecos reales:

1. **`TypedRefusal` en `appliesWhen`.** Los mutantes A1–A3 estaban cubiertos
   solo por la rama que devuelve `Boolean`. Una `TypedRefusal` (fallo tipado)
   producida por `appliesWhen` no estaba cubierta por ningún test. Añadido test
   dirigido que exige `Error` en ese caso.
2. **Orden canónico de sequences.** El primer intento fue **simétrico**
   (comparaba el resultado consigo mismo bajo dos órdenes), y por eso
   **sobrevivió** al mutante C2: una aserción simétrica no puede distinguir un
   orden canónico de su inverso. Reemplazado por una aserción asimétrica sobre
   el canonical string y su SHA-256, que sí muere en el método exacto.

Que el primer test sobreviviera al mutante es la evidencia de que el gate
sirve para algo: la cobertura previa declaraba más de la que tenía.

## Recalibración del ancla

`cert/SHA.txt` pasó a `certified-sha: 83289c59c36133dd9b3edd116d704800b8716388`
y `baseline-tests: 505 → 508`. Los dos tests nuevos movieron el árbol de
fuentes, y `CertificationAnchorTest.01b` falló con el mensaje que está diseñado
para dar ("find out what moved"): el árbol movido eran los tests añadidos en
este mismo ítem. La pasada fresca reportaba 508 ejecutados, 0 fallos, 0
errores, 0 skips, así que `baseline-tests` se subió a 508 y
`scripts/m6/certify.sh` lo acepta sin que el ancla sea una mentira.

## Evidencia

```
$ ./gradle-jdk21.sh check
BUILD SUCCESSFUL in 1m 40s
36 actionable tasks: 4 executed, 32 up-to-date

$ bash scripts/m6/certify.sh
· source tree: 83289c59c36133dd9b3edd116d704800b8716388 (81 files)
· executed: 508 tests, 0 failures, 0 errors, 0 skipped
· marker baseline-tests: 508, executed: 508
CERTIFY EXIT=0
```

## Lo que este receipt NO afirma

- No afirma que el kernel esté libre de mutantes supervivientes. Afirma que
  siete mutantes nombrados, que cubren `appliesWhen`, el veredicto, la precisión
  decimal, las claves duplicadas y los dos órdenes canónicos, mueren. El resto
  de mutantes posibles no se ha medido (H5.3).
- No afirma que `detekt` esté limpio por mérito propio: `check` lo ejecuta como
  parte del gate, y el run de cierre fue `BUILD SUCCESSFUL`.
