# M7 Proposal — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** propose
**Path:** A-full · **Roadmap:** §M7 (M7.A layers, M7.B waivers, M7.C shadow, M7.D diff)

## Intent

Permitir rollout empresarial seguro y actualización de policies sin romper
equipos a ciegas: composición de layers con autoridad explícita, waivers
auditables post-violation, shadow sin efecto en PipelineOutcome, y semantic
diff A/B determinista.

## Scope

**IN (kernel, additive-only, IR v1 compatible):**
1. `PolicyLayer` enum (PLATFORM>ORGANIZATION>PROJECT>PIPELINE_LOCAL) +
   `LayeredPolicy.compose(layers) -> PolicySet | LayerCompositionRefusal`:
   monotónica por defecto; duplicate `(policyId,ruleId)` entre layers ⇒ REFUSED
   salvo supersession explícita (metadata: supersedes ref, reason, authority,
   scope, validity).
2. `Waiver` ADT (id, policyId, ruleId, resourceSelector, dataset/project scope,
   optional violationFingerprint, reason, issuer, notBefore/notAfter expiry) +
   `WaiverMatcher.apply(report, waivers, clock: () -> Instant) -> WaiverApplication`
   (violation active | waived finding | active+waiver diagnostic). Puro: clock
   inyectado (law 5).
3. `EnforcementMode { ENFORCED, SHADOW }` en el plugin: en SHADOW el step
   outcome SIEMPRE Success y el evento lleva `wouldDeny`/findings; estado
   explícito, nunca catch-and-continue.
4. `PolicyDiff.of(a: PolicyReport, b: PolicyReport) -> PolicyDiff` con las 9
   categorías del spec + privilege-expansion highlight; digest determinista
   (mismo corpus + mismo A/B ⇒ mismo diff digest).

**OUT (follow-ups, no bloquean):**
- Persistencia/almacenamiento de waivers (formato fichero) más allá del ADT.
- UI/reporting del diff. Integración CLI (M9).
- Backfill de mutation tests M6 (INC-005) salvo que M7 toque esos ficheros.

## Approach

- Kernel puro primero (layers, waivers, diff como funciones puras), luego
  plugin (enforcement mode en PolicyCheckInput/Output + evento), luego DSL
  mínima si procede. Tests quirúrgicos por REQ + mutation tests dedicados
  (falsificación) por cada semántica nueva.
- UAT principal del roadmap: nueva mandatory rule en shadow detecta 20
  would-deny sobre corpus histórico, luego se activa; un waiver válido salva
  exactamente un subject y no otro.

## Acceptance criteria (borrador; la spec los consolida)

- Composición con duplicado sin supersession ⇒ REFUSED (no last-wins).
- Waiver expirado ⇒ violation active + diagnostic (nunca waive silencioso).
- Shadow con violation ⇒ outcome Success + evento would-block registrado.
- Diff A/B determinista: 2 ejecuciones ⇒ mismo digest.
- `./gradle-jdk21.sh check` verde; bundles M5 siguen verificando (IR v1).
