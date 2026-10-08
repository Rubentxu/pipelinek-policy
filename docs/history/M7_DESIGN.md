# M7 Design — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** design
**Path:** A-full · Authority: spec M7 + `docs/03-specifications/WAIVERS_LAYERS_AND_DIFF.md`

## 1. Colocación de módulos (law 1/5/6)

Todo el core nuevo va en el **kernel puro** (`src/main/kotlin/.../kernel/`),
sin dependencias nuevas (kotlin-stdlib only):

```text
kernel/policy/Layers.kt        PolicyLayer, LayeredPolicy, RuleRef,
                                Supersession, LayerComposer, LayerCompositionRefusal
kernel/policy/Waivers.kt       Waiver, WaiverMatcher, WaiverApplication,
                                WaiverDiagnosticCause, ViolationFingerprint
kernel/policy/PolicyDiff.kt    DiffCategory, DiffEntry, PolicyDiff,
                                PolicyDiffRefusal
```

El plugin (`pipelinek-policy-plugin`) solo añade `EnforcementMode` al
input/output y la propagación al evento. El evaluator NO se toca.

## 2. Diseños clave

### 2.1 Layers (REQ-01/02)

- `LayerComposer.compose(layers: List<LayeredPolicy>): ComposeResult` donde
  `ComposeResult = Composed(PolicySet) | Refused(LayerCompositionRefusal)`.
- Orden de entrada irrelevante: se normaliza por autoridad (ordinal del enum).
- El `PolicySet` resultante preserva procedencia vía `policyId` único por
  layer-repo; la colisión se detecta por par `(policyId, ruleId)` en un mapa
  acumulador: duplicado sin supersession registrada ⇒ REFUSED con ambas capas
  implicadas en el mensaje.
- Supersession: `Rule.supersession: Supersession?` (default null, additive).
  La rule superseding declara `RuleRef(layer-agnostic: policyId+ruleId)`.
  Compose: si la rule entrante trae supersession y el target existe ⇒ el
  target se elimina y entra la nueva; target inexistente ⇒ REFUSED
  `UnknownSupersessionTarget`. Authority/scope/validity son metadata
  verificable (presence + no-vacío) en M7; la verificación contra un
  registry de autoridades queda como follow-up (spec no define el registry).

### 2.2 Waivers (REQ-03)

- Matching puro: `WaiverMatcher.apply(report: PolicyReport, waivers, now)`
  donde `now: Instant` llega inyectado (law 5; el plugin lo obtiene del
  host runtime, el test de un clock fijo).
- Resultado por violation: `WaivedFinding(waiverId)` |
  `ActiveViolation` | `ActiveWithDiagnostic(waiverId, cause)` con
  `cause ∈ { WaiverExpired, WaiverNotYetValid }`.
- Fingerprint: derivación determinista de (policyId, ruleId, location,
  resourceFingerprint) — misma función que usa el diff para identidad de
  violation. Si el waiver declara fingerprint y no coincide ⇒ no matchea
  (sin diagnostic: es un waiver para otra instancia).
- `resourceSelector`: reutiliza `Selector` del kernel (structural match sobre
  el ValueTree del resource; law 9).

### 2.3 Shadow (REQ-04)

- `PolicyCheckInput.enforcement: EnforcementMode = ENFORCED` (@Serializable
  con default ⇒ los payloads M6 sin el campo decodifican a ENFORCED).
- `PolicyCheckOutput.outcome`:
  `VIOLATED && SHADOW -> StepOutcome.Success` (mensaje lleva
  "shadow would-deny: N"). Verdict/violationsCount/reportDigest intactos.
- Evento `policy.check.reported`: payload añade `enforcement` +
  `wouldDeny: Boolean` (schemaVersion bump a 2 si el SDK exige; si el SDK
  permite payload aditivo con mismo schemaVersion, se queda en 1 — se decide
  en implementación contra el contrato real del SDK).

### 2.4 Diff (REQ-05)

- `PolicyDiff.of(a, b)`: primero compara `resourceFingerprint`; distinto ⇒
  `PolicyDiffRefusal.CorpusMismatch`.
- Identidad de violation = fingerprint (§2.2). Categorías por transición de
  estado por ruleId: Violated→Passed = RESOLVED_VIOLATION
  (privilegeExpansion=true si la rule era mandatory), Passed→Violated =
  NEW_VIOLATION, NotApplicable↔aplicable = APPLICABILITY_CHANGED,
  Error↔no-Error = ERROR_INTRODUCED/RESOLVED, y WAIVER_EFFECT_CHANGED /
  ENFORCEMENT_INCREASED/DECREASED / SEVERITY_CHANGED reservados a diff de
  applications (report+waivers) — `PolicyDiff.of(a, b, waA, waB)` overload
  con waivers aplicados.
- `diffDigest`: sha256 sobre render canónico (categorías sorted, entries
  sorted por fingerprint). Determinista por construcción.

## 3. Estrategia de tests

- `LayersTest`, `WaiversTest`, `PolicyDiffTest` (kernel, quirúrgicos por
  escenario de spec), `EnforcementShadowTest` (plugin), `M7UatTest`
  (integración 20-subject corpus del roadmap, escenarios 07a-d).
- Mutation tests dedicados por REQ (01c, 02c, 03f, 04c, 05e, 07d): tests que
  FALLAN bajo la mutación descrita. No se repite el deferral de INC-005.
- Golden regression: bundle M5 packed fixture verifica byte-identical.

## 4. Alternativas descartadas

- Layers en el IR/bundle en vez del kernel: rechazado — el IR versiona el
  ADT; añadir layers al IR v1 forzaría bump de versión de bundle y rompería
  REQ-M7-06. Los layers componen ANTES de empaquetar.
- Shadow dentro del evaluator (flag global): rechazado — law 10 (no global
  mutable) y spec §6 (estado explícito por request).

## 5. Riesgos

- SDK event payload contract (schemaVersion) — se resuelve contra el
  contrato real en implementación; ambas salidas son aditivas.
- `Selector` reuse para resourceSelector: si el Selector actual no cubre
  matching por subject-id, se añade un `SubjectSelector` mínimo estructural.
