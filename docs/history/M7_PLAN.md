# M7 Tasks — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** tasks
**Path:** A-full · Authority: M7_SPECIFICATION.md + M7_DESIGN.md

Work units con dependencias. Cada WU cierra con tests quirúrgicos verdes.

## WU-1 · Kernel Layers + supersession (REQ-01, REQ-02)

- `kernel/policy/Layers.kt`: `PolicyLayer`, `LayeredPolicy`, `RuleRef`,
  `Supersession`, `LayerComposer.compose`, `LayerCompositionRefusal`.
- `Rule.supersession: Supersession? = null` (additive en PolicySet.kt).
- Tests: `LayersTest` escenarios 01a/01b, 02a/02b + mutation 01c/02c.
- Deps: ninguna.

## WU-2 · Kernel Waivers (REQ-03)

- `kernel/policy/Waivers.kt`: `Waiver`, `ViolationFingerprint.of(...)`,
  `WaiverMatcher.apply(report, waivers, now)`, resultados y diagnostics.
- Tests: `WaiversTest` 03a-f (clock fijo inyectado).
- Deps: WU-1 (nada de código, solo convención de fingerprint; puede paralelizar).

## WU-3 · Kernel PolicyDiff (REQ-05)

- `kernel/policy/PolicyDiff.kt`: `PolicyDiff.of(a,b)`, overload con waivers,
  9 categorías, privilegeExpansion, `diffDigest` canónico,
  `PolicyDiffRefusal.CorpusMismatch`.
- Tests: `PolicyDiffTest` 05a-e (incl. determinismo doble ejecución).
- Deps: WU-2 (fingerprint y waiver application).

## WU-4 · Plugin EnforcementMode (REQ-04, REQ-M7-06 parcial)

- `EnforcementMode` en plugin types; `PolicyCheckInput.enforcement` default
  ENFORCED (serialización back-compat: payload M6 sin campo ⇒ ENFORCED).
- `PolicyCheckOutput.outcome`: VIOLATED+SHADOW ⇒ Success con mensaje
  would-deny. Evento añade enforcement/wouldDeny (contra contrato SDK real).
- Tests: `EnforcementShadowTest` 04a-c + round-trip M6 06b.
- Deps: WU-2 (para UAT con waivers), compile contra SDK M6.

## WU-5 · UAT integración roadmap (REQ-07)

- Corpus 20 subjects + mandatory rule; escenarios 07a (shadow 20 would-deny,
  Success), 07b (enforced 20 failures), 07c (waiver subject[3] ⇒ 19+1),
  07d (mutation wildcard over-match ⇒ FALLA).
- Golden regression M5 bundle (06a).
- Deps: WU-1..4.

## WU-6 · Full gate + docs

- `./gradle-jdk21.sh check` completo (regresión 191+ tests M6 intactos).
- ROADMAP §M7 → DONE; M7_UAT_EVIDENCE.md.
- Deps: WU-5.

## Order

WU-1 → (WU-2 ∥ WU-3 tras fingerprint) → WU-4 → WU-5 → WU-6.
