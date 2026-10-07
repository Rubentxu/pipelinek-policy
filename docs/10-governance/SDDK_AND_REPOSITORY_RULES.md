# Gobernanza SDDK y reglas del repositorio

## 1. SDDK obligatorio

Cada bloque de trabajo debe tener:

- hypothesis/problem statement;
- evidence/characterization;
- decision;
- implementation;
- focused verification;
- exit criteria;
- receipt.

## 2. Estado fuera del repo

No guardar estado operacional/pipeline de SDDK dentro del repo. Usar directorio de usuario por proyecto.

## 3. Roadmap

Root `ROADMAP.md` es autoridad única.

Docs que describen planes históricos se mueven a `docs/history/` cuando quedan superados.

## 4. Testing cadence

- quirúrgico durante desarrollo;
- full suite en integración/release;
- exact SHA en receipts.

## 5. Commits

Atomic Conventional Commits.

Ejemplos:

```text
feat(domain): add canonical structured value tree
feat(decoder): preserve source anchors for json and yaml
feat(dsl): add pure numeric policy expressions
feat(compiler): resolve symbolic open-shape properties
test(compiler): falsify impure calls in policy blocks
```

## 6. Completion

“Done” requiere criterios de aceptación verificados. Código escrito sin UAT/AAT pertinente no cierra milestone.

## 7. Architecture emergence

No abstraer dos cosas porque “se parecen”. Primero demostrar la ley común.

Si un spike falsifica una premisa central, STOP y ADR antes de continuar.
