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
 * REQ-M9-07 · explain.
 * 07a: violated rule shows the culprit expression subtree + location.
 * 07c FALSIFICATION: output without ruleId breaks the contract.
 */
class ExplainCmdTest {

    private val dir = createTempDirectory("m9wu4")

    private fun bundle(): java.nio.file.Path {
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "team-platform", "spec.team must be platform",
                            Expression.Comparison(
                                Expression.FieldRef(DocumentPath.ROOT.child("spec").child("team"), ValueNode.Type.TEXT),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("platform")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return dir.resolve("p.bundle").also { it.writeBytes(PolicyBundle(lower(set)).pack()) }
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf("explain") + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `07a violated rule shows state expression and location`() {
        val b = bundle()
        val res = dir.resolve("res.json").also { it.writeText("""{"spec":{"team":"tools"}}""") }
        val (code, out) = run("--policy", b.toString(), "--rule", "team-platform", "--resource", res.toString())
        assertEquals(ExitCodes.VIOLATIONS, code, "a denied decision must not exit successfully: $out")
        assertTrue(out.contains("\"ruleId\": \"team-platform\""), out)
        assertTrue(out.contains("\"state\": \"violated\""), out)
        assertTrue(out.contains("\"kind\": \"comparison\""), "culprit subtree expected: $out")
        assertTrue(out.contains("\"op\": \"TEXT_EQUALS\""), out)
        assertTrue(out.contains("spec.team"), "violation location expected: $out")
    }

    @Test
    fun `07c falsification ruleId always present`() {
        val b = bundle()
        val (code, out) = run("--policy", b.toString(), "--rule", "team-platform")
        assertEquals(ExitCodes.OK, code)
        assertTrue(out.contains("\"ruleId\""), "ruleId must never be dropped: $out")
    }

    @Test
    fun `unknown rule exits 2 with known list`() {
        val b = bundle()
        val (code, out) = run("--policy", b.toString(), "--rule", "nope")
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(out.contains("unknown rule: nope"))
        assertTrue(out.contains("team-platform"))
    }

    @Test
    fun `passed rule reports passed state`() {
        val b = bundle()
        val res = dir.resolve("ok.json").also { it.writeText("""{"spec":{"team":"platform"}}""") }
        val (code, out) = run("--policy", b.toString(), "--rule", "team-platform", "--resource", res.toString())
        assertEquals(ExitCodes.OK, code)
        assertTrue(out.contains("\"state\": \"passed\""))
    }

    @Test
    fun `evaluation error exits with evaluation error code`() {
        val path = DocumentPath.ROOT.child("spec").child("team")
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "team-type",
                            "spec.team must be text",
                            Expression.Comparison(
                                Expression.FieldRef(path, ValueNode.Type.TEXT),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("platform")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val b = dir.resolve("error.bundle").also { it.writeBytes(PolicyBundle(lower(set)).pack()) }
        val resource = dir.resolve("wrong-type.json").also { it.writeText("""{"spec":{"team":7}}""") }

        val (code, out) = run("--policy", b.toString(), "--rule", "team-type", "--resource", resource.toString())

        assertEquals(ExitCodes.EVALUATION_ERROR, code, "engine errors must not be reported as success: $out")
        assertTrue(out.contains("\"state\": \"error\""), out)
    }
}
