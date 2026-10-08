package com.pipelinek.policy.dsl

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.ViolationCode
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.evaluator.RuleId
import com.pipelinek.policy.kernel.value.ValueNode.MappingValue
import com.pipelinek.policy.kernel.value.ValueNode.NumberValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spec REQ §"DSL symbolic API" + §"Parity DSL↔ADT" — the canonical DSL lowers
 * to a `PolicySet` whose `canonicalDigest` is byte-identical to a data-built
 * `PolicySet`. The class graph of the DSL output is restricted to
 * `com.pipelinek.policy.kernel.*` and `com.pipelinek.policy.dsl.*`.
 */
class ParityTest {

    @Test
    fun `target of reference lowers to canonical AST with identical digest`() {
        // Data-built reference: `spec.replicas asNumber gte 3`.
        val dataRule = Rule(
            id = "min-replicas",
            message = "rule min-replicas",
            expression = Comparison(
                left = FieldRef(
                    DocumentPath.ROOT.child("spec").child("replicas"),
                    ValueNode.Type.NUMBER,
                ),
                op = Operator.GTE,
                right = Literal(ValueNode.NumberValue(3)),
            ),
        )
        val dataSet = PolicySet(
            id = "baseline",
            policies = listOf(Policy(id = "k8s-baseline", rules = listOf(dataRule))),
        )

        // DSL-built: same rule via the canonical DSL surface.
        val dslSet: PolicySet = policy("baseline") {
            policy("k8s-baseline") {
                rule("min-replicas") {
                    require {
                        root().field("spec").optionalField("replicas").asNumber()
                            .gte(number(3))
                    }
                }
            }
        }

        assertEquals(dataSet, dslSet)
        assertEquals(dataSet.policies[0].rules[0].expression, dslSet.policies[0].rules[0].expression)
        // The canonical digest of the data-built and DSL-built sets MUST match.
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to NumberValue(3)))),
        )
        assertEquals(
            Evaluator.evaluate(dataSet, tree).digest,
            Evaluator.evaluate(dslSet, tree).digest,
        )
    }

    @Test
    fun `DSL-produced class graph is restricted to kernel and dsl packages`() {
        // Class-graph introspection (architectural law 4: no `kotlin.Function0/1`,
        // `kotlin.reflect.KFunction`, `KClass`, or non-kernel/dsl closures).
        val set = policy("baseline") {
            policy("p") {
                rule("r") {
                    require {
                        root().field("spec").optionalField("replicas").asNumber()
                            .gte(number(3))
                    }
                }
            }
        }
        val classes = collectReachableClasses(set)
        classes.forEach { c ->
            val pkg = c.packageName
            assertTrue(
                pkg.startsWith("com.pipelinek.policy.kernel") || pkg.startsWith("com.pipelinek.policy.dsl"),
                "class $c leaks outside the allowlist (package=$pkg)",
            )
            assertTrue(c != kotlin.jvm.functions.Function0::class.java &&
                c != kotlin.jvm.functions.Function1::class.java,
                "Function0/Function1 must not appear",
            )
            assertTrue(c != kotlin.reflect.KFunction::class.java,
                "KFunction must not appear",
            )
        }
    }

    @Test
    fun `DSL symbolics produce the same Evaluation as data-built`() {
        val set = policy("s") {
            policy("p") {
                rule("min-replicas") {
                    require {
                        root().field("spec").optionalField("replicas").asNumber()
                            .gte(number(3))
                    }
                }
            }
        }
        val tree = MappingValue(
            linkedMapOf("spec" to MappingValue(linkedMapOf("replicas" to NumberValue(2)))),
        )
        val report = Evaluator.evaluate(set, tree)
        val ev = report.results[RuleId("min-replicas")]
        assertNotNull(ev)
        assertTrue(ev is com.pipelinek.policy.kernel.policy.RuleEvaluation.Violated)
        assertEquals(
            ViolationCode.COMPARISON_FAILED,
            (ev as com.pipelinek.policy.kernel.policy.RuleEvaluation.Violated).violations[0].code,
        )
    }

    private fun collectReachableClasses(set: PolicySet): Set<Class<*>> {
        val seen = mutableSetOf<Class<*>>()
        // Walk data-class components via the PolicySet tree directly — no
        // generic reflection (JDK 17+ restricts reflective access to JDK
        // internals like String.value).
        seen += set::class.java
        set.policies.forEach { p ->
            seen += p::class.java
            p.rules.forEach { r ->
                seen += r::class.java
                walkExpression(r.expression, seen)
                r.appliesWhen?.let { walkExpression(it, seen) }
            }
        }
        return seen
    }

    private fun walkExpression(expr: Expression, seen: MutableSet<Class<*>>) {
        when (expr) {
            is Literal -> seen += expr::class.java
            is FieldRef -> seen += expr::class.java
            is Comparison -> {
                seen += expr::class.java
                walkExpression(expr.left, seen)
                walkExpression(expr.right, seen)
            }
            is Expression.CollectionPredicate -> {
                seen += expr::class.java
                walkExpression(expr.source, seen)
                seen += expr.predicate::class.java
            }
            is Expression.Reference -> seen += expr::class.java
        }
    }
}
