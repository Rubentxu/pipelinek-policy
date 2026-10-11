# R1.1 — Preflight del repositorio

**Fecha:** 2026-10-11 · **Agente:** Jcode · **Work-item:** `6fb5b421-1201-4773-b748-e6510da8b87b`

Esta nota es la primera parada del hito **R1 — Reconciliación, integración remota y alpha.2** definido en la directiva `R1–R4` del usuario. Documenta el estado medido del repositorio antes de cualquier acción de integración. **No se ha ejecutado ningún cambio destructivo.**

## R1.1.1 — Estado medido

### Repositorio y remotos

| Concepto | Valor medido | Comando |
|---|---|---|
| `origin` URL | `https://github.com/Rubentxu/pipelinek-policy.git` | `git remote -v` |
| `origin/HEAD` | `refs/remotes/origin/main` | `git symbolic-ref refs/remotes/origin/HEAD` |
| `origin/main` SHA | `c906988582ea16085bb6ec0154559e5a30c770c6` | `git rev-parse origin/main` |
| `main` (local) SHA | `fa365077b3f7a055b28388dbe36dd89358678557` | `git rev-parse HEAD` |
| `merge-base` | `c906988582ea16085bb6ec0154559e5a30c770c6` | `git merge-base origin/main HEAD` |
| Ahead / behind | `0 / 52` (HEAD ahead de origin/main) | `git rev-list --left-right --count origin/main...HEAD` |
| Working tree | Limpio (sin cambios sin commitear) | `git status --porcelain` |
| Stash list | Vacío | `git stash list` |

### Tags y branches locales

| Ref | Apunta a | Asunto |
|---|---|---|
| `v0.3.0-alpha.1` (tag anotado) | `427e30f722e7e26d0adafc4b3da3024aced953c4` | `pipelinek-policy 0.3.0-alpha.1` |
| `main` (branch) | `fa36507` | HEAD local post-auditoría |
| `feat/m10-cert` | `1d933fc` | rama de M10 |
| `feat/m7-layers-waivers-shadow-diff` | `ca9fe6c` | rama de M7 |
| `feat/m9-cli-agent-dx` | `5a9162b` | rama de M9 |
| `m4-fir-spike` | `9b2384d` | spike de M4 (FIR) |

**Tensión R1 detectada (no resuelta en este hito):** el tag local `v0.3.0-alpha.1` apunta a `427e30f`, pero el `origin/main` actual es `c906988` (sin tag encima). Esto significa que o bien (a) el release de GitHub `v0.3.0-alpha.1` se publicó desde `427e30f` y `origin/main` avanzó después por otro motivo, o (b) el tag en el remoto no fue fetcheado. R1.1 no intenta resolverlo: la directiva pide `git fetch --prune` (sin `--tags`) y eso es exactamente lo aplicado. **No se reconstruirán tags intermedios para fingir cronología.**

### Divergencia entre build version, plugin version y release identity

| Fuente de identidad | Valor actual | Archivo |
|---|---|---|
| Build version (root) | `0.3.0-alpha.1` | `build.gradle.kts:16` (`version = "0.3.0-alpha.1"`) |
| Plugin version | (heredada — módulo `pipelinek-policy-plugin`) | `pipelinek-policy-plugin/build.gradle.kts` |
| CLI version | (heredada — módulo `pipelinek-policy-cli`) | `pipelinek-policy-cli/build.gradle.kts` |
| Release identity (tag) | `0.3.0-alpha.1` | `v0.3.0-alpha.1` (tag anotado en `427e30f`) |
| Gradle / Kotlin / JDK | ver `gradle.properties` y `build.gradle.kts:123` (Kotlin 2.4.10 cached) | varios |

**Tensión R1 detectada:** la versión declarada en `build.gradle.kts:16` está en `0.3.0-alpha.1`, mientras el HEAD local es 52 commits posterior. La directiva R1.4 pide actualizar la versión a `0.3.0-alpha.2` en *todas* las fuentes de identidad. Inventario completo de archivos a tocar se elaborará en R1.2 (no antes).

### Estado SDDK

Cada commit de los 52 lleva `git sddk-align --ack` y `git sddk-close` satisfechos. El estado de SDDK vive en `.git/sddk-agent-gate/` (fuera del repo según las reglas del workspace). **R1.1 no toca estado SDDK.**

### Reglas estructurales (verificación rápida)

| Regla arquitectónica | Estado |
|---|---|
| `ValueTree` autoridad runtime | mantenida (sin cambios en este turno) |
| `PolicyIR` autoridad ejecutable | mantenida |
| Decoders aislados del kernel | módulos `policy-decoders-{json,yaml,csv,map}` (ver `settings.gradle.kts:30-37`) |
| Sin I/O en el evaluador | regla arquitectónica activa; se verificará en R1.3 con AAT-003 |
| Sin GitHub Actions | confirmado (no hay `.github/workflows/`) |
| Sin segundo CI | confirmado (gates reproducibles por scripts en `scripts/`) |

## R1.1.2 — Decisiones tomadas en este hito

1. **No se ejecuta `git fetch --prune --tags`.** La directiva R1.1 menciona solo `git fetch --prune`; ampliar a `--tags` puede cambiar la lista de refs remotas. Si el usuario autoriza `--tags`, lo haré antes de R1.2.
2. **No se crea la rama de integración todavía.** R1.5 step 1 ("preparar una rama de integración o PR revisable") es la primera acción que modifica refs. Se ejecutará después de R1.3 verde.
3. **No se commitea nada en este hito.** R1.1 es *medición*, no *acción*. Esta nota se publica como nuevo fichero en `docs/history/` siguiendo la convención del proyecto, sin tocar otras refs.

## R1.1.3 — Riesgos y limitaciones pendientes

- **Cronología tag vs origin/main** — sin resolver. Se requiere `git fetch --prune --tags` (autorización) o consulta a GitHub Releases API.
- **H2.4 design fork A/B** — la directiva R2.1 pide recuperar la propuesta original. He buscado `docs/history/H24_*` y no existe; las fuentes candidatas son `docs/history/M8_*.md` y `docs/03-specifications/POLICY_IR.md` (verificado con grep que ambos mencionan `GLOBAL`). R2.1 (no R1) abrirá esta búsqueda.
- **Compatibilidad SDK 0.48.0** — la directiva R3.1 advierte que la matriz actual solo acredita compilación, no carga en host vivo. Pendiente para R3.
- **Provenance** — pendiente para R3.4.

## R1.1.4 — Próximo paso solicitado

Esperar luz verde del usuario para:

1. Autorizar `git fetch --prune --tags` (ver R1.1.2 punto 1).
2. Proceder a **R1.2 — Inventario de los 52 commits** (read-only, agrupación por H1–H5 + correcciones, sin tocar árbol).
3. Tras R1.2 y R1.3 verde, preparar **R1.4 — bump de versión a `0.3.0-alpha.2`** en las fuentes de identidad inventariadas.
4. **R1.5 pasos 1–2** (crear rama `integration/r1-alpha.2` + redactar PR revisable + solicitar autorización humana explícita).
5. **R1.5 paso 3+** sigue siendo **human-gate**: merge, push, tag, GitHub Release. **No se ejecutarán sin autorización humana explícita.**

## R1.1.5 — Salida R1.1

- ✅ Medición de SHAs (origin/main, local, merge-base) y divergencia (52 ahead / 0 behind).
- ✅ Working tree limpio confirmado.
- ✅ Inventario de tags y branches locales.
- ✅ Identificación de fuentes de versión de build.
- ✅ Tensiones y limitaciones registradas.
- ✅ Decisión explícita: no tocar refs ni estado SDDK en este hito.
- ❌ R1.1.5 cerrado **en estado `READY_FOR_HUMAN_GATE`** (no `DONE`): las acciones R1.1.2 punto 1 (`fetch --tags`) y R1.1.4 punto 4 (crear rama de integración) requieren autorización humana explícita antes de ejecutarse, conforme a la regla 10 de la directiva.
