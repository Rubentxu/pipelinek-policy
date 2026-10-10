package com.pipelinek.policy.cli

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B4.7 · Oversized inputs are refused at the host edge, before decoding.
 *
 * [BoundedReadTest] proves the reader's decision in isolation. This proves
 * the thing an operator actually experiences: pointing a command at an
 * oversized file produces a refusal and a refusal exit code, rather than an
 * out-of-memory kill or a decode that quietly spent the memory the bound
 * claimed to protect.
 *
 * These run through [PolicyCli.run], not the reader, because a bound that
 * only works when called correctly is not a bound.
 */
class OversizedInputRefusalTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    private fun fixtureBundle(dir: java.nio.file.Path): java.nio.file.Path {
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

    /** Write [bytes] worth of data without the test itself holding it all. */
    /**
     * Materialise a file past the budget without the test itself holding the
     * whole payload in memory, and prove it really is over the line.
     */
    private fun Path.writeOversized(suffix: String) {
        val chunk = ByteArray(1024 * 1024)
        Files.newOutputStream(this).use { out ->
            repeat((BoundedRead.DEFAULT_RESOURCE_BUDGET / chunk.size + 2).toInt()) { out.write(chunk) }
        }
        val size = Files.size(this)
        assertTrue(
            size > BoundedRead.DEFAULT_RESOURCE_BUDGET,
            "fixture $suffix must exceed the budget or this proves nothing",
        )
    }

    @Test
    fun `01 an oversized resource is refused, not decoded`() {
        val dir = createTempDirectory("b47-check")
        val bundle = fixtureBundle(dir)
        val resource = dir.resolve("huge.json").also { it.writeOversized("huge.json") }

        val (code, out) = run("check", "--policy", bundle.toString(), resource.toString())

        assertTrue(
            code == ExitCodes.VIOLATIONS || code == ExitCodes.ADMISSION_ERROR,
            "an oversized resource must take a refusal path, got exit $code: $out",
        )
        assertTrue(
            out.contains("too large") || out.contains("budget"),
            "the operator must be told the input was too large, got: $out",
        )
    }

    @Test
    fun `02 an oversized bundle is refused with the admission exit code`() {
        val dir = createTempDirectory("b47-bundle")
        val bundle = dir.resolve("p.bundle").also { it.writeOversized("p.bundle") }

        val (code, out) = run("check", "--policy", bundle.toString(), dir.resolve("x.json").toString())

        assertEquals(
            ExitCodes.ADMISSION_ERROR,
            code,
            "an oversized bundle must be an admission failure, got exit $code: $out",
        )
        assertTrue(
            out.contains("too large") || out.contains("budget"),
            "the operator must be told the bundle was too large, got: $out",
        )
    }

    @Test
    fun `03 an oversized fixture is refused by the test command`() {
        val dir = createTempDirectory("b47-fixture")
        val bundle = fixtureBundle(dir)
        val fixtures = dir.resolve("fixtures").also { it.createDirectories() }
        fixtures.resolve("big.deny.json").also { it.writeOversized("big.deny.json") }

        val (code, out) = run(
            "test",
            "--policy", bundle.toString(),
            "--fixtures", fixtures.toString(),
        )

        assertTrue(
            code == ExitCodes.ADMISSION_ERROR || code == ExitCodes.VIOLATIONS,
            "an oversized fixture must take a refusal path, got exit $code: $out",
        )
        assertTrue(
            out.contains("refused"),
            "the fixture must be reported as refused, got: $out",
        )
    }

    @Test
    fun `04 a normal resource in the same shape still evaluates`() {
        // The counterpart, and the one that stops the refusal path from
        // "fixing" the bug by refusing everything: a file well inside the
        // budget must decode and evaluate exactly as before.
        val dir = createTempDirectory("b47-ok")
        val bundle = fixtureBundle(dir)
        val resource = dir.resolve("ok.json").also {
            it.writeText("""{"spec": {"team": "platform"}}""")
        }

        val (code, out) = run("check", "--policy", bundle.toString(), resource.toString())

        assertEquals(ExitCodes.OK, code, "a legal resource must still pass: $out")
    }
}