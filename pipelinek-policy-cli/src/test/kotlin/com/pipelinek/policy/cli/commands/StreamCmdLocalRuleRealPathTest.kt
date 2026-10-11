package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.PolicyCli
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H2.1 · Real CLI acceptance path for the same-named LOCAL rule case.
 *
 * The unit tests in `DatasetRuleIdentityProbeTest` exercise
 * `StreamingEvaluator.evaluate(...)` directly, which an author never calls.
 * The real consumer is `stream` in `pipelinek-policy-cli`, which packs the
 * policy, parses a CSV, and walks the report. This file is the check that the
 * real path reports the violations the kernel claims it found.
 *
 * The fixture packages a bundle with two policies whose `check` rules both
 * require `temp == "ok"`, streams a CSV that violates each twice, and asserts
 * the CLI reports four violations and exits VIOLATIONS. A rule silently
 * dropped from the per-row subset would show as two violations (one rule's
 * count) instead of four.
 */
class StreamCmdLocalRuleRealPathTest {
    private fun sameNameBundle(): ByteArray {
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "alpha",
                    listOf(
                        Rule(
                            "check",
                            "alpha's check",
                            Expression.Comparison(
                                Expression.FieldRef(DocumentPath.ROOT.child("temp"), ValueNode.Type.TEXT),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("ok")),
                            ),
                        ),
                    ),
                ),
                Policy(
                    "beta",
                    listOf(
                        Rule(
                            "check",
                            "beta's check",
                            Expression.Comparison(
                                Expression.FieldRef(DocumentPath.ROOT.child("temp"), ValueNode.Type.TEXT),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("ok")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return PolicyBundle(lower(set)).pack()
    }

    private fun runStream(vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf("stream") + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `real path - stream of a bundle with two same-named LOCAL rules reports two violations on a violating CSV`() {
        val dir = createTempDirectory("h21-realpath")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(sameNameBundle()) }
        val csv = dir.resolve("data.csv").also { it.writeText("temp\nok\nbad\nok\nbad\n") }

        val (code, out) = runStream("--policy", bundle.toString(), csv.toString())
        assertEquals(ExitCodes.VIOLATIONS, code, "expected VIOLATIONS, got code=$code and:\n$out")
        // Each rule reports 2 violations. The CLI's "violations=" counter sums
        // all rule violation counts, so two rules with 2 violations each give 4.
        // If one rule were silently dropped, the count would be 2.
        assertTrue(
            out.contains("violations=4"),
            "expected violations=4 from two rules each reporting 2; got: $out",
        )
    }
}
