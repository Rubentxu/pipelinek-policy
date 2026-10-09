# Layers, waivers, shadow y semantic diff

## 1. Policy layers

Orden de autoridad:

```text
PLATFORM > ORGANIZATION > PROJECT > PIPELINE_LOCAL
```

Composición es monotónica por defecto: lower layers añaden constraints.

## 2. Duplicate IDs

Mismo `(policyId, ruleId)` en dos layers:

```text
without explicit supersession grant -> REFUSED
```

No `last wins`.

## 3. Supersession

Requiere metadata explícita:

```text
supersedes rule ref
reason
authority
scope
validity
```

Y la autoridad debe tener capability para supersede ese layer.

## 4. Waivers

No modifican la rule. Se aplican después de obtener la violation y antes de enforcement final.

```text
Rule violation
   ↓
WaiverMatcher
   ├── no match -> violation active
   └── match
       ├── valid -> waived finding
       └── invalid/expired -> active + waiver diagnostic
```

## 5. Waiver matching

Scope mínimo:

- policy/rule;
- resource selector;
- dataset/project scope;
- optional violation fingerprint;
- expiry.

### 5.1 Scopes sin contexto no hacen match (B4-T3)

`datasetScope` y `projectScope` son dimensiones de match obligatorias.
El contexto llega explícito como `WaiverContext(project, dataset)`; el
kernel NO lo deduce de `PolicyReport`, que no lleva identidad de proyecto ni
de dataset.

Una dimensión que la exención nombra y que el contexto no puede responder
**no hace match**. Un campo null del contexto significa "el contexto no lo
sabe", y NO significa "cualquiera": tratar la ausencia como comodín
permitiría que una exención estrecha silenciara violaciones fuera de su
proyecto. Es el mismo tipo de defecto que una autoridad sin verificar — una
comprobación ausente que en silencio casa con todo.

Una exención SIN scope en una dimensión sigue valiendo con contexto ausente.

### 5.2 Un error de evaluación nunca es eximible (B4-T3)

`RuleEvaluation.Error.violations` contiene el DIAGNÓSTICO del fallo, no una
violación eximible. Una exención no puede convertir un `Error` en `Waived`:
eso escondería una policy rota detrás de una excepción. El error se sigue
reportando como `Active`, se sigue contando y no lleva `waiverId`.

### 5.3 Expirada conserva la violación (B4-T3)

Una exención expirada (o todavía no válida) deja la violación ACTIVA más un
diagnóstico (`WaiverExpired` / `WaiverNotYetValid`). Nunca una caída
silenciosa, nunca un `Waived`.

## 6. Shadow

`SHADOW` evalúa toda la policy, genera findings y diffs, pero no cambia execution decision.

No implementar shadow mediante “catch exception and continue”; es estado explícito.

## 7. Semantic diff

Comparar bundle A/B sobre el mismo corpus:

```text
NEW_VIOLATION
RESOLVED_VIOLATION
ENFORCEMENT_INCREASED
ENFORCEMENT_DECREASED
SEVERITY_CHANGED
ERROR_INTRODUCED
ERROR_RESOLVED
APPLICABILITY_CHANGED
WAIVER_EFFECT_CHANGED
```

## 8. Privilege expansion

`Deny -> Allow` / mandatory violation desaparecida debe resaltarse como posible expansión de privilegio/restricción reducida.

## 9. Diff determinista

Same corpus + same A/B -> same diff digest.
