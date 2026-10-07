# Semántica de evaluación

## 1. Rule lifecycle

```text
select subject(s)
  -> applicability
      false       -> NotApplicable
      error       -> Error
      true
        -> assertion
            true  -> Passed
            false -> Violated
            error -> Error
```

`FORBID` normaliza assertion semánticamente para mantener un único evaluator.

## 2. RuleEvaluation

```kotlin
sealed interface RuleEvaluation {
    data class Passed(...): RuleEvaluation
    data class Violated(val violation: PolicyViolation): RuleEvaluation
    data class NotApplicable(val reason: NotApplicableReason): RuleEvaluation
    data class Error(val error: PolicyEvaluationError): RuleEvaluation
}
```

## 3. PolicyReport

Debe conservar:

- bundle identity/digest;
- engine/IR version;
- resource fingerprints;
- rule evaluations o resumen según report mode;
- violations;
- applied waivers;
- evaluation errors;
- static/dynamic cost stats;
- deterministic report digest.

## 4. Missing

### En `appliesWhen`

Una optional path ausente normalmente produce `NotApplicable`, si la expression no pidió explícitamente `missing()`.

### En `require`

Una field necesaria ausente produce violation o evaluation error según operador.

Regla recomendada:

```text
missing value required by a typed assertion -> Violation(MISSING_REQUIRED_VALUE)
invalid type -> Error(TYPE_MISMATCH)
```

No mezclar ambos.

## 5. Null

`Null` es valor presente. Sólo satisface reglas que explícitamente lo permitan.

## 6. Type mismatch

No coerción implícita.

```text
Text("3") gte Integer(3)
-> Error(TYPE_MISMATCH)
```

## 7. Errors y enforcement

Un `PolicyEvaluationError` en una policy activa:

- `ADVISORY`: report + configurable continue;
- `OVERRIDABLE`: no puede convertirse automáticamente en pass;
- `MANDATORY`: fail closed/reject.

La policy deployment config define mapping; nunca silenciosamente pass.

## 8. Severity vs Enforcement vs Rollout

```text
Severity:     INFO | WARNING | ERROR | CRITICAL
Enforcement:  ADVISORY | OVERRIDABLE | MANDATORY
Rollout:      SHADOW | ACTIVE
```

`SHADOW + MANDATORY`: calcula qué bloquearía, pero no bloquea todavía.

## 9. Evaluation order

El verdict semántico no depende del orden de reglas independientes.

Sin embargo, para reporting determinista se usa orden canónico por `(policyId, ruleId, subjectId)`.

No short-circuit global que oculte findings salvo modo `FAIL_FAST` explícito para casos operativos.

Default: acumular violations con límites.

## 10. Resource identity

Subject identity debe ser estable dentro del input y derivable:

```text
source ref + document ordinal + optional domain attributes
```

No usar object identity JVM.

## 11. Evaluation fingerprints

Incluyen todo input semántico:

- PolicyIR semantic digest;
- policy parameters;
- ResourceDocument semantic digests;
- dataset composition;
- waiver set;
- explicit context facts.

No incluyen renderer/color/console settings.
