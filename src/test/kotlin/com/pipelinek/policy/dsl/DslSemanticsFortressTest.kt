package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.RuleId
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import com.pipelinek.policy.kernel.value.ValueNode.TextValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLOQUE B1 · Falsification gates para DSL: forbid evaluable (B1.3),
 * params en appliesWhen (B1.6), Missing/Null (B1.5).
 */
class DslSemanticsFortressTest {

    private val tree = MappingValue(
        linkedMapOf(
            "spec" to MappingValue(
                linkedMapOf(
                    "team" to TextValue("evil"),
                    "replicas" to NumberValue(2),
                ),
            ),
        ),
    )

    // --- B1.3: forbid es negación declarativa EVALUABLE ---

    @Test
    fun `B1-3a forbid with satisfied body yields Violated`() {
        val set = policy("s") {
            policy("p") {
                rule("no-evil-team") {
                    forbid {
                        root().field("spec").field("team").asText() eqText text("evil")
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", "no-evil-team")]
        assertTrue(
            ev is RuleEvaluation.Violated,
            "forbid(body=true) must Violate, got $ev (negation lost?)",
        )
    }

    @Test
    fun `B1-3b forbid with unsatisfied body yields Passed`() {
        val set = policy("s") {
            policy("p") {
                rule("no-good-team") {
                    forbid {
                        root().field("spec").field("team").asText() eqText text("good")
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", "no-good-team")]
        assertEquals(RuleEvaluation.Passed, ev, "forbid(body=false) must Pass, got $ev")
    }

    // --- B1.6: params en appliesWhen ---

    @Test
    fun `B1-6 param reference in appliesWhen is substituted`() {
        val set = policy("s") {
            policy("p") {
                rule("gated") {
                    params(mapOf("enabled" to DslParamValue.BooleanV(true)))
                    appliesWhen { ref("enabled") }
                    require {
                        root().field("spec").field("replicas").asNumber() gte number(3)
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", "gated")]
        // replicas=2 < 3 with the gate ON ⇒ Violated (not Error, not NotApplicable).
        assertTrue(
            ev is RuleEvaluation.Violated,
            "param in appliesWhen must substitute: expected Violated, got $ev",
        )
    }

    @Test
    fun `B1-5a explicit JSON null under a required numeric field is not Missing`() {
        // Law 8 + spec §5: Null is a PRESENT value of type NULL; it is NOT
        // Missing and must not produce MISSING_REQUIRED_VALUE. A numeric
        // comparison over it is a TYPE_MISMATCH (Error) — no coercion.
        val set = policy("s") {
            policy("p") {
                rule("r") {
                    require {
                        root().field("spec").field("replicas").asNumber() gte number(3)
                    }
                }
            }
        }
        val treeWithNull = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to ValueNode.Null))),
        )
        val report = Evaluator.evaluate(set, treeWithNull)
        val ev = report.results[RuleId.of("s", "p", "r")]
        assertTrue(
            ev is RuleEvaluation.Error,
            "Null is a present NULL-typed value; numeric compare must be Error(TYPE_MISMATCH), got $ev",
        )
        if (ev is RuleEvaluation.Error) {
            assertEquals(
                com.pipelinek.policy.kernel.policy.ViolationCode.TYPE_MISMATCH,
                (ev as RuleEvaluation.Error).violations[0].code,
            )
        }
    }

    @Test
    fun `B1-5b absent optional field yields NotApplicable`() {
        // optionalField must survive DSL -> FieldRef -> evaluator. Missing is
        // preserved as optional absence and skips the rule, not a violation,
        // TypeMismatch, or implicit default.
        val set = policy("s") {
            policy("p") {
                rule("opt") {
                    require {
                        root().field("spec").optionalField("replicas").asNumber() gte number(3)
                    }
                }
            }
        }
        val emptyTree = MappingValue(linkedMapOf("spec" to MappingValue(linkedMapOf())))
        val report = Evaluator.evaluate(set, emptyTree)
        val ev = report.results[RuleId.of("s", "p", "opt")]
        assertEquals(RuleEvaluation.NotApplicable, ev)
    }
    @Test
    fun `B1-6b unresolved param in appliesWhen never silently passes`() {
        val set = policy("s") {
            policy("p") {
                rule("dangling") {
                    appliesWhen { ref("missing-param") }
                    require {
                        root().field("spec").field("replicas").asNumber() gte number(3)
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId.of("s", "p", "dangling")]
        // An unresolved reference is a typed refusal: Error, never a silent
        // NotApplicable that skips the rule.
        assertTrue(
            ev !is RuleEvaluation.Passed && ev !is RuleEvaluation.NotApplicable,
            "unresolved param must be a loud failure, got $ev",
        )
    }
}
