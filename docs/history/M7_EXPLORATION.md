# M7 Exploration — layers, waivers, shadow y diff

**Cycle:** `p-dd1a1c7a7d448b0c/m7-layers-waivers-shadow-diff` · **Phase:** explore
**Executed by:** orchestrator (inline). Reason: spawn routes exhausted en ciclos
previos (MiniMax 429, OpenAI usage_limit_reached); patrón M3/M5/M6. Read-only.

## 1. Estado del arte observado (OBSERVED)

### Kernel (`src/main/kotlin/com/pipelinek/policy/kernel/`)
- `PolicySet(id, policies: List<Policy>)`, `Policy(id, rules: List<Rule>)`,
  `Rule(id, message, expression, appliesWhen, code, expected, actual, params)`.
  **No existe layer, enforcement mode ni waiver en el ADT.**
- `RuleEvaluation`: Passed | Violated | NotApplicable | Error.
- `Evaluator.evaluate(set, tree) -> PolicyReport` — puro, sin I/O.
- `PolicyReport(policySetId, resourceFingerprint, results: Map<RuleId, RuleEvaluation>)`.

### IR / Bundle (`ir/`, `bundle/`)
- `PolicyIrDocument` versiona el ADT del kernel (IR v1, M5).
- `PolicyBundle.pack()` determinista; `BundleVerifier` fail-closed.

### Plugin (`pipelinek-policy-plugin/`)
- `PolicyCheckVerdict { PASSED, VIOLATED, REFUSED }`.
- `PolicyCheckOutput.outcome` DERIVA verdict→StepOutcome (VIOLATED ⇒ Failure).
  Este es el punto de enforcement único: shadow mode vive aquí o en el kernel
  como dato, nunca como "catch and continue".

### DSL / Spec
- `docs/03-specifications/WAIVERS_LAYERS_AND_DIFF.md` (DOCUMENTED) define:
  layers PLATFORM>ORG>PROJECT>PIPELINE_LOCAL (composición monotónica),
  duplicate (policyId,ruleId) ⇒ REFUSED sin supersession explícita,
  waiver matching post-violation pre-enforcement (scope: policy/rule,
  resource selector, dataset/project, fingerprint opcional, expiry),
  shadow como estado explícito, semantic diff con 9 categorías
  (NEW/RESOLVED_VIOLATION, ENFORCEMENT_INCREASED/DECREASED,
  SEVERITY_CHANGED, ERROR_INTRODUCED/RESOLVED, APPLICABILITY_CHANGED,
  WAIVER_EFFECT_CHANGED), privilege expansion highlight, diff determinista.

## 2. Problema-taxonomy discovery

- **Taxonomía A (composición)**: no hay tipo LayeredPolicySet ni
  compositor con colisión (policyId,ruleId) ⇒ REFUSED.
- **Taxonomía B (waivers)**: no existe Waiver ADT ni WaiverMatcher;
  el evaluator no aplica waivers (correcto: spec los pone DESPUÉS de
  obtener violation, ANTES de enforcement).
- **Taxonomía C (shadow)**: verdict enum sin modo; el enforcement point
  es `PolicyCheckOutput.outcome` (plugin). Shadow = dato en la request/config
  del step, no en el evaluator.
- **Taxonomía D (diff)**: no existe BundleDiff; PolicyReport es comparable
  por digest pero falta diff categorizado A/B sobre corpus.

## 3. Decisiones de diseño (DERIVED, para proponer)

1. Layers y waivers viven en el **kernel** como ADTs puros (law 1/5);
   composición y matching son funciones puras.
2. Shadow NO toca el evaluator: añade `enforcement: ENFORCED|SHADOW` al
   input del step del plugin; en SHADOW el outcome SIEMPRE es Success
   pero el verdict/evento conserva VIOLATED + findings would-block.
3. Diff es función pura kernel-level: `PolicyDiff.of(reportA, reportB)`
   sobre el mismo corpus; digest determinista (sorted categories).
4. IR v1 compat: additive-only (defaults), igual que M3 hizo con M1.
   Los bundles M5 existentes siguen verificando.

## 4. Riesgos / bloqueos

- Ninguno bloqueante. Ley de falsificación: cada semántica nueva necesita
  mutation test dedicado (M6 lo dejó DEFERRED como INC-005; M7 NO debe
  repetir el patrón en sus propias features).

## 5. Preguntas resueltas sin ambigüedad

- ¿Waivers pueden modificar la rule? NO (spec §4 explícito).
- ¿Shadow vía excepciones? NO (spec §6 explícito).
- ¿Last-wins en duplicados? NO (spec §2: REFUSED).
