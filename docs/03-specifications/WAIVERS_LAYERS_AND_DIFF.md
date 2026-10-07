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
