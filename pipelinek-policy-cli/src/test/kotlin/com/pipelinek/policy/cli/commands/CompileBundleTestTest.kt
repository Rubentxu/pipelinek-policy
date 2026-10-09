package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.PolicyCli
import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ-M9-01a/08 · compile / bundle verify / test.
 * 01a: compile produces a deterministic bundle (same IR in ⇒ same bytes).
 * 08c: test with pass+fail fixtures ⇒ exit 2 with per-fixture results.
 */
class CompileBundleTestTest {

    private val dir = createTempDirectory("m9wu5")

    private fun irJson(): String {
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
        return CanonicalPolicyJson.encode(lower(set)).toString(Charsets.UTF_8)
    }

    private fun typeErrorIrJson(): String {
        val teamPath = DocumentPath.ROOT.child("spec").child("team")
        val rule = Rule(
            "team-type",
            "spec.team must be text",
            Expression.Comparison(
                Expression.FieldRef(teamPath, ValueNode.Type.TEXT),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue("platform")),
            ),
        )
        return CanonicalPolicyJson.encode(
            lower(PolicySet("uat", listOf(Policy("p", listOf(rule))))),
        ).toString(Charsets.UTF_8)
    }

    private fun run(cmd: String, vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf(cmd) + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `01a compile is deterministic and output verifies`() {
        val src = dir.resolve("policy.ir.json").also { it.writeText(irJson()) }
        val out1 = dir.resolve("b1.bundle")
        val out2 = dir.resolve("b2.bundle")
        val (code1, o1) = run("compile", "--policy", src.toString(), "--out", out1.toString())
        val (code2, _) = run("compile", "--policy", src.toString(), "--out", out2.toString())
        assertEquals(ExitCodes.OK, code1, o1)
        assertEquals(ExitCodes.OK, code2)
        assertTrue(out1.toFile().readBytes().contentEquals(out2.toFile().readBytes()), "compile must be deterministic")
        // and the produced bundle verifies
        val (vcode, vout) = run("bundle", "--verify", out1.toString())
        assertEquals(ExitCodes.OK, vcode, vout)
        assertTrue(vout.contains("\"verified\": true"))
    }

    @Test
    fun `bundle verify refuses garbage with admission exit code`() {
        val junk = dir.resolve("junk.bundle").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val (code, out) = run("bundle", "--verify", junk.toString())
        assertEquals(4, code)
        assertTrue(out.contains("\"verified\": false"))
    }

    @Test
    fun `compile rejects invalid canonical IR with compiler exit code`() {
        val src = dir.resolve("invalid.ir.json").also { it.writeText("{") }
        val (code, out) = run("compile", "--policy", src.toString(), "--out", dir.resolve("invalid.bundle").toString())

        assertEquals(5, code, out)
        assertTrue(out.contains("canonical IR refused"), out)
    }

    @Test
    fun `test classifies a missing bundle as admission error`() {
        val fixtures = dir.resolve("missing-bundle-fixtures").toFile().apply { mkdirs() }

        val (code, out) = run(
            "test",
            "--policy", dir.resolve("missing.bundle").toString(),
            "--fixtures", fixtures.absolutePath,
        )

        assertEquals(ExitCodes.ADMISSION_ERROR, code, out)
    }

    @Test
    fun `test classifies a refused bundle as admission error`() {
        val bundle = dir.resolve("refused.bundle").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val fixtures = dir.resolve("refused-bundle-fixtures").toFile().apply { mkdirs() }

        val (code, out) = run(
            "test",
            "--policy", bundle.toString(),
            "--fixtures", fixtures.absolutePath,
        )

        assertEquals(ExitCodes.ADMISSION_ERROR, code, out)
    }

    @Test
    fun `08c test fixtures one pass one fail exits 2 per-fixture`() {
        val src = dir.resolve("policy.ir.json").also { it.writeText(irJson()) }
        val bundle = dir.resolve("t.bundle")
        run("compile", "--policy", src.toString(), "--out", bundle.toString())
        val fixtures = dir.resolve("fixtures").toFile().apply { mkdirs() }
        File(fixtures, "good.allow.json").writeText("""{"spec":{"team":"platform"}}""")
        File(fixtures, "bad.allow.json").writeText("""{"spec":{"team":"tools"}}""")
        File(fixtures, "expected.deny.json").writeText("""{"spec":{"team":"tools"}}""")

        val (code, out) = run("test", "--policy", bundle.toString(), "--fixtures", fixtures.absolutePath)
        assertEquals(ExitCodes.VIOLATIONS, code)
        assertTrue(out.contains("\"fixture\": \"good.allow.json\", \"expectation\": \"allow\", \"violations\": 0, \"status\": \"pass\""))
        assertTrue(out.contains("\"fixture\": \"bad.allow.json\"") && out.contains("\"status\": \"FAIL\""))
        assertTrue(out.contains("\"fixture\": \"expected.deny.json\"") && out.contains("\"status\": \"pass\""))
    }

    @Test
    fun `all green fixtures exit 0`() {
        val src = dir.resolve("policy.ir.json").also { it.writeText(irJson()) }
        val bundle = dir.resolve("g.bundle")
        run("compile", "--policy", src.toString(), "--out", bundle.toString())
        val fixtures = dir.resolve("green").toFile().apply { mkdirs() }
        File(fixtures, "good.allow.json").writeText("""{"spec":{"team":"platform"}}""")
        File(fixtures, "wrong.deny.json").writeText("""{"spec":{"team":"tools"}}""")
        val (code, _) = run("test", "--policy", bundle.toString(), "--fixtures", fixtures.absolutePath)
        assertEquals(ExitCodes.OK, code)
    }

    @Test
    fun `test refuses directory with files but no recognized fixtures`() {
        val src = dir.resolve("policy.ir.json").also { it.writeText(irJson()) }
        val bundle = dir.resolve("unclassified.bundle")
        run("compile", "--policy", src.toString(), "--out", bundle.toString())
        val fixtures = dir.resolve("unclassified").toFile().apply { mkdirs() }
        File(fixtures, "README.md").writeText("No allow or deny fixture is present.")

        val (code, out) = run("test", "--policy", bundle.toString(), "--fixtures", fixtures.absolutePath)

        assertEquals(ExitCodes.USAGE, code, "a directory with no executable fixtures must not pass vacuously: $out")
        assertTrue(out.contains("fixture"), out)
    }

    @Test
    fun `test never accepts evaluator Error as an expected deny`() {
        val src = dir.resolve("error-policy.ir.json").also { it.writeText(typeErrorIrJson()) }
        val bundle = dir.resolve("error-policy.bundle")
        run("compile", "--policy", src.toString(), "--out", bundle.toString())
        val fixtures = dir.resolve("error-fixtures").toFile().apply { mkdirs() }
        File(fixtures, "wrong.deny.json").writeText("""{"spec":{"team":7}}""")

        val (code, out) = run("test", "--policy", bundle.toString(), "--fixtures", fixtures.absolutePath)

        assertEquals(ExitCodes.EVALUATION_ERROR, code, "typed evaluator Error is not a policy deny: $out")
        assertTrue(out.contains("\"status\": \"error\""), out)
    }
}
