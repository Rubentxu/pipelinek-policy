package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleEvaluation
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Combinators + params substitution end-to-end tests. Exercises the
 * `policy { }` macro, the rule combinators (`require`/`forbid`/`appliesWhen`)
 * and the `${name}` parameter substitution via `ref("name")`.
 *
 * These tests would have FAILED under the M3 build.complete state because
 * `ParamSubstitutor.substitute` was a no-op when params was non-empty. They
 * PASS now because the substitutor walks the expression tree and replaces
 * every `Expression.Reference(name)` with the matching `Literal(ValueNode)`
 * before handing the rule to the kernel.
 */
class CombinatorsTest {

    private val tree = ValueNode.MappingValue(
        linkedMapOf(
            "spec" to ValueNode.MappingValue(linkedMapOf("replicas" to ValueNode.NumberValue(3))),
        ),
    )

    // ----- Param substitution ----------------------------------------------

    @Test
    fun `param substitution replaces ref with literal at compile-DSL time`() {
        val set: PolicySet = policy("baseline") {
            policy("k8s-baseline") {
                rule("min-replicas") {
                    require {
                        root().field("spec").field("replicas").asNumber()
                            .gte(ref("min"))
                    }
                    params(mapOf("min" to DslParamValue.IntV(3)))
                }
            }
        }
        val rule = set.policies.single().rules.single()
        // The kernel-facing Rule.expression MUST carry a Literal, NOT a
        // Reference, so the evaluator never sees a Reference node.
        val cmp = rule.expression as Comparison
        val right = cmp.right
        assertIs<Literal>(right)
        assertEquals(ValueNode.NumberValue(3), (right as Literal).value)
        // The DSL-side Reference node is no longer reachable from Rule.
        val references = mutableListOf<Expression.Reference>()
        fun walk(e: Expression) {
            when (e) {
                is Expression.Reference -> references += e
                is Literal -> {}
                is FieldRef -> {}
                is Comparison -> { walk(e.left); walk(e.right) }
                is Expression.CollectionPredicate -> walk(e.source)
            }
        }
        walk(rule.expression)
        assertEquals(
            0,
            references.size,
            "Reference must NOT reach the kernel after substitution (got ${references.size})",
        )
    }

    @Test
    fun `param substitution on replicas rule with param min=3 evaluates to Passed`() {
        val set: PolicySet = policy("baseline") {
            policy("k8s-baseline") {
                rule("min-replicas") {
                    require {
                        root().field("spec").field("replicas").asNumber()
                            .gte(ref("min"))
                    }
                    params(mapOf("min" to DslParamValue.IntV(3)))
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results.values.first())
    }

    @Test
    fun `param substitution on replicas rule with param min=5 evaluates to Violated`() {
        // Same DSL; only `params` differs (min=5 instead of 3 ⇒ replicas=3 fails).
        val set: PolicySet = policy("baseline") {
            policy("k8s-baseline") {
                rule("min-replicas") {
                    require {
                        root().field("spec").field("replicas").asNumber()
                            .gte(ref("min"))
                    }
                    params(mapOf("min" to DslParamValue.IntV(5)))
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        val eval = report.results.values.first()
        assertIs<RuleEvaluation.Violated>(eval)
    }

    @Test
    fun `param substitution supports String and Boolean values`() {
        val tree = ValueNode.MappingValue(
            linkedMapOf("kind" to ValueNode.TextValue("Deployment")),
        )
        val set: PolicySet = policy("kind-match") {
            policy("p") {
                rule("r") {
                    require {
                        root().field("kind").asText().eqText(ref("expected"))
                    }
                    params(mapOf("expected" to DslParamValue.StringV("Deployment")))
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results.values.first())
    }

    // ----- require / forbid ----------------------------------------------

    @Test
    fun `forbid wraps body in canonical EQ(false) at compile-DSL time`() {
        val set: PolicySet = policy("forbid-test") {
            policy("p") {
                rule("r") {
                    forbid {
                        root().field("spec").field("replicas").asNumber().gte(number(3))
                    }
                }
            }
        }
        val rule = set.policies.single().rules.single()
        // forbid body should be wrapped: outer is Comparison, inner is the
        // original body, right is Literal(BooleanValue(false)).
        val outer = rule.expression as Comparison
        assertEquals(Operator.EQ, outer.op)
        val rightLit = outer.right as Literal
        assertEquals(ValueNode.BooleanValue(false), rightLit.value)
    }

    @Test
    fun `require body passes when assertion is true`() {
        val set: PolicySet = policy("require-pass") {
            policy("p") {
                rule("r") {
                    require {
                        root().field("spec").field("replicas").asNumber().gte(number(3))
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.Passed, report.results.values.first())
    }

    @Test
    fun `appliesWhen false yields NotApplicable for a body that would have passed`() {
        val set: PolicySet = policy("applies-when") {
            policy("p") {
                rule("r") {
                    appliesWhen { boolean(false) }
                    require {
                        root().field("spec").field("replicas").asNumber().gte(number(3))
                    }
                }
            }
        }
        val report = Evaluator.evaluate(set, tree)
        assertEquals(RuleEvaluation.NotApplicable, report.results.values.first())
    }
}
