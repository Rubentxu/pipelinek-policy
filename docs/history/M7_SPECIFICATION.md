# M7 Specification — layers, waivers, shadow y semantic diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** specify

Requirements with testable scenarios. Each REQ maps to at least one
UAT/surgical test. Authority: `docs/03-specifications/WAIVERS_LAYERS_AND_DIFF.md`.

## REQ-M7-01 · Policy layers ADT y composición

`PolicyLayer { PLATFORM, ORGANIZATION, PROJECT, PIPELINE_LOCAL }` con orden de
autoridad PLATFORM > ORGANIZATION > PROJECT > PIPELINE_LOCAL.
`LayeredPolicy(layer, policySet)` y `LayerComposer.compose(layers)` produce un
`PolicySet` plano monotónico (lower layers AÑADEN constraints, nunca relajan).

- Scenario 01a (compose ok): PLATFORM + PROJECT con policyIds distintos ⇒
  PolicySet con todas las rules de ambos, en orden de autoridad.
- Scenario 01b (duplicate refused): mismo `(policyId, ruleId)` en dos layers
  sin supersession ⇒ `LayerCompositionRefusal.DuplicateRuleId` (NO last-wins).
- Scenario 01c (falsificación/mutation): mutar el compositor a "last wins"
  (drop silencioso del duplicado) ⇒ el test 01b FALLA.

## REQ-M7-02 · Supersession explícita

`Supersession(supersedes: RuleRef, reason, authority, scope, validity)` en la
Policy/Rule de la layer superior permite reemplazar la rule referenciada de la
layer inferior. Sin authority válida para ese layer ⇒ REFUSED.

- Scenario 02a: PROJECT supersede una rule ORGANIZATION con metadata completa
  ⇒ compose OK y SOLO la rule superseding queda (la superseded desaparece).
- Scenario 02b: supersession que referencia una rule inexistente ⇒
  `LayerCompositionRefusal.UnknownSupersessionTarget`.
- Scenario 02c (falsificación): mutar para ignorar el target ⇒ 02b FALLA.

## REQ-M7-03 · Waiver ADT y matching post-violation

`Waiver(id, policyId, ruleId, resourceSelector, datasetScope?, projectScope?,
violationFingerprint?, reason, issuer, notBefore, notAfter)`.
`WaiverMatcher.apply(report, waivers, clock: () -> Instant)` produce por
violation: `Waived(waiverId)` | `Active` | `ActiveWithWaiverDiagnostic(waiverId, cause)`.
Nunca modifica la rule ni el evaluator (spec §4).

- Scenario 03a (match exacto): waiver válido (scope policy/rule + selector
  match + ahora en [notBefore, notAfter]) ⇒ la violation queda Waived.
- Scenario 03b (expiry): waiver con notAfter < now ⇒ Active +
  `WaiverExpired` diagnostic (nunca waive silencioso).
- Scenario 03c (not-yet-valid): notBefore > now ⇒ Active +
  `WaiverNotYetValid` diagnostic.
- Scenario 03d (scope aislado): waiver para subject S1 NO salva subject S2
  (UAT roadmap: "un waiver válido salva exactamente un subject y no otro").
- Scenario 03e (rule mismatch): waiver cuya (policyId, ruleId) no coincide
  con ninguna violation del report ⇒ no-op, sin diagnostics falsos.
- Scenario 03f (falsificación): mutar el matcher para ignorar expiry ⇒ 03b FALLA.

## REQ-M7-04 · EnforcementMode SHADOW en el plugin

`EnforcementMode { ENFORCED, SHADOW }` en `PolicyCheckInput` (default ENFORCED,
back-compat M6). En SHADOW: verdict/evento conservan VIOLATED y los findings
(would-deny), pero `PolicyCheckOutput.outcome` SIEMPRE es Success. Estado
explícito; prohibido catch-and-continue (spec §6).

- Scenario 04a (shadow pass-through): mismo bundle violante con
  enforcement=SHADOW ⇒ verdict VIOLATED, outcome Success, evento
  `policy.check.reported` con wouldDeny=true.
- Scenario 04b (enforced unchanged): enforcement=ENFORCED (default) con el
  mismo bundle ⇒ comportamiento M6 bit-identical (outcome Failure).
- Scenario 04c (falsificación): mutar outcome para fallar en SHADOW ⇒ 04a FALLA.

## REQ-M7-05 · Semantic diff A/B determinista

`PolicyDiff.of(a: PolicyReport, b: PolicyReport)` sobre el MISMO corpus
(resourceFingerprint igual; distinto ⇒ `PolicyDiffRefusal.CorpusMismatch`).
Categorías: NEW_VIOLATION, RESOLVED_VIOLATION, ENFORCEMENT_INCREASED,
ENFORCEMENT_DECREASED, SEVERITY_CHANGED, ERROR_INTRODUCED, ERROR_RESOLVED,
APPLICABILITY_CHANGED, WAIVER_EFFECT_CHANGED. Privilege expansion
(Deny→Allow / mandatory violation desaparecida) se marca
`privilegeExpansion=true`.

- Scenario 05a: bundle B añade una rule que A no tenía y el corpus la viola ⇒
  NEW_VIOLATION con privilegeExpansion=false.
- Scenario 05b: mandatory violation presente en A desaparece en B ⇒
  RESOLVED_VIOLATION con privilegeExpansion=true.
- Scenario 05c (determinismo): diff(A,B) ejecutado dos veces ⇒ mismo
  `diffDigest` (sorted categories, canonical rendering).
- Scenario 05d (corpus mismatch): fingerprints distintos ⇒ refusal tipado.
- Scenario 05e (falsificación): mutar el digest a orden de inserción ⇒ 05c FALLA.

## REQ-M7-06 · IR v1 back-compat

Los cambios son additive-only con defaults (igual que M3 sobre M1): bundles
M5/M6 empaquetados antes de M7 siguen verificando con `BundleVerifier` y el
plugin externo instalado los consume sin recompilar.

- Scenario 06a: un bundle M5 packed (fixture golden) verifica byte-identical
  tras el cambio (regression guard).
- Scenario 06b: `PolicyCheckInput` sin campo enforcement (serialización M6)
  decodifica a ENFORCED.

## REQ-M7-07 · UAT principal del roadmap (integración)

Una nueva mandatory rule en shadow detecta 20 would-deny sobre corpus
histórico (20 resources); después se activa (ENFORCED) y las 20 fallan; un
waiver válido salva exactamente 1 subject (el resto siguen failing).

- Scenario 07a: shadow run sobre corpus de 20 ⇒ 20 would-deny findings,
  outcome Success en todos.
- Scenario 07b: enforced run ⇒ 20 failures.
- Scenario 07c: waiver para subject[3] ⇒ 19 failures + 1 waived.
- Scenario 07d (falsificación): waiver con selector que matchee de más
  (p.ej. wildcard mal aplicado) ⇒ 07c detecta >1 waived y FALLA.

## No-silent-coercion / leyes arquitectónicas aplicadas

- Law 5 (pureza): clock inyectado en WaiverMatcher; diff sin I/O.
- Law 9 (no coercion): scope matching es estructural, nunca stringly.
- Law 12: layers/waivers lower JAMÁS debilitan mandatory upper-layer rules
  (composición monotónica; supersession solo con authority explícita).
