package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ M10-06 · Adversarial limits (06a/06b).
 *
 * 06a: a 1000-rule policy evaluates a medium document without
 *      StackOverflowError and with a bounded, complete report
 *      (1000 results, one per rule).
 * 06b: a 500-level deep-nested JSON document must decode to Refused or
 *      evaluate bounded — never a silent StackOverflow. Jackson has its
 *      own depth guard; we assert OUR contract: decode returns
 *      Refused (Jackson rejects) or Ok, and evaluation over a deep
 *      hand-built tree terminates.
 */
class LargePolicyLimitsTest {

    @Test
    fun `06a - 1000 rules evaluate a medium document without stack overflow`() {
        val rules = (0 until 1000).map { i ->
            Rule(
                id = "rule-$i",
                message = "cert rule $i",
                expression = Expression.Comparison(
                    left = Expression.FieldRef(
                        DocumentPath.ROOT.child("spec").child("replicas"),
                        ValueNode.Type.NUMBER,
                    ),
                    op = Expression.Operator.GTE,
                    right = Expression.Literal(ValueNode.NumberValue(i % 10)),
                ),
            )
        }
        val set = PolicySet("large", listOf(Policy("p", rules)))
        val doc: ValueNode = ValueNode.MappingValue(
            mapOf(
                "metadata" to ValueNode.MappingValue(mapOf("name" to ValueNode.TextValue("svc"))),
                "spec" to ValueNode.MappingValue(mapOf("replicas" to ValueNode.NumberValue(5))),
            ),
        )
        val report = Evaluator.evaluate(set, doc)
        assertEquals(1000, report.results.size, "all 1000 rules must produce a result")
    }

    @Test
    fun `06b - deep nested json is refused or bounded, never silent overflow`() {
        val depth = 500
        val sb = StringBuilder()
        repeat(depth) { sb.append("{\"a\":") }
        sb.append("1")
        repeat(depth) { sb.append("}") }
        val result = JsonResourceDecoder().decode(sb.toString().toByteArray(), DecodeOptions())
        // Contract: Ok or Refused — never an exception escaping decode().
        when (result) {
            is com.pipelinek.policy.decoder.DecodeResult.Ok ->
                assertTrue(result.documents.isNotEmpty(), "Ok must carry documents")
            is com.pipelinek.policy.decoder.DecodeResult.Refused ->
                assertTrue(true, "Refused is the safe outcome for pathological nesting")
        }
    }

    @Test
    fun `06b - deep hand-built tree evaluates bounded`() {
        // 500-level ValueNode chain: evaluation of a shallow field must not
        // walk the whole chain; the report terminates.
        var node: ValueNode = ValueNode.NumberValue(1)
        repeat(500) { node = ValueNode.MappingValue(mapOf("a" to node)) }
        val set = PolicySet(
            "deep",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "top-is-mapping",
                            "root.a must exist",
                            Expression.Comparison(
                                Expression.FieldRef(
                                    DocumentPath.ROOT.child("a"),
                                    ValueNode.Type.NUMBER,
                                ),
                                Expression.Operator.GTE,
                                Expression.Literal(ValueNode.NumberValue(0)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val report = Evaluator.evaluate(set, node)
        assertEquals(1, report.results.size)
    }
}
