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
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ-M9-02 · check command (02a/02b/02c).
 * Uses the real dispatch via PolicyCli.run so the exit-code contract is
 * exercised end to end.
 */
class CheckCmdTest {

    private fun tmp(name: String): Path = kotlin.io.path.createTempFile(name)

    private fun bundleBytes(): ByteArray {
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "must-have-team", "metadata.team must be platform",
                            Expression.Comparison(
                                Expression.FieldRef(
                                    DocumentPath.ROOT.child("metadata").child("team"),
                                    ValueNode.Type.TEXT,
                                ),
                                Expression.Operator.TEXT_EQUALS,
                                Expression.Literal(ValueNode.TextValue("platform")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return PolicyBundle(lower(set)).pack()
    }

    private fun runCheck(vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf("check") + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `02a mixed corpus aggregates error over violation`() {
        val dir = kotlin.io.path.createTempDirectory("m9check")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        val ok = dir.resolve("ok.json").also { it.writeText("""{"metadata":{"team":"platform"}}""") }
        val bad = dir.resolve("bad.json").also { it.writeText("""{"metadata":{}}""") }
        val refuse = dir.resolve("broken.csv").also { it.writeText("\"unterminated") }

        val (code, out) = runCheck(
            "--policy", bundle.toString(), "--format", "jsonl",
            ok.toString(), bad.toString(), refuse.toString(),
        )
        assertEquals(ExitCodes.USAGE, code, "refusal must dominate: $out")
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "one violation finding + one refusal: $out")
        assertTrue(lines.any { it.contains("\"state\":\"violation\"") && it.contains("bad.json") })
        assertTrue(lines.any { it.contains("\"state\":\"refusal\"") && it.contains("broken.csv") })
    }

    @Test
    fun `02a only violations exits 1`() {
        val dir = kotlin.io.path.createTempDirectory("m9check2")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        val bad = dir.resolve("bad.json").also { it.writeText("""{"metadata":{}}""") }
        val (code, out) = runCheck("--policy", bundle.toString(), bad.toString())
        assertEquals(ExitCodes.VIOLATIONS, code)
        assertTrue(out.contains("must-have-team"))
    }

    @Test
    fun `02b falsification extension decides decoder no sniffing`() {
        val dir = kotlin.io.path.createTempDirectory("m9check3")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        // JSON content with an unknown extension: must REFUSE, not sniff-parse.
        val sneaky = dir.resolve("sneaky.conf").also { it.writeText("""{"metadata":{"team":"x"}}""") }
        val (code, out) = runCheck("--policy", bundle.toString(), sneaky.toString())
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(out.contains("no decoder for extension .conf"))
        // And valid json content under .csv refuses too (csv parser rejects it).
        val fakeCsv = dir.resolve("fake.csv").also { it.writeText("""{"metadata":{"team":"x"}}""") }
        val (code2, out2) = runCheck("--policy", bundle.toString(), fakeCsv.toString())
        assertTrue(code2 == ExitCodes.USAGE || code2 == ExitCodes.VIOLATIONS, out2)
    }

    @Test
    fun `02c falsification repeated resource yields deduped findings`() {
        val dir = kotlin.io.path.createTempDirectory("m9check4")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        val bad = dir.resolve("bad.json").also { it.writeText("""{"metadata":{}}""") }
        val (_, out) = runCheck(
            "--policy", bundle.toString(), "--format", "jsonl",
            bad.toString(), bad.toString(),
        )
        val findings = out.lines().filter { it.contains("\"state\":\"violation\"") }
        assertEquals(1, findings.size, "duplicate findings must be deduped: $out")
    }

    @Test
    fun `clean corpus exits 0`() {
        val dir = kotlin.io.path.createTempDirectory("m9check5")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        val ok = dir.resolve("ok.json").also { it.writeText("""{"metadata":{"team":"platform"}}""") }
        val (code, out) = runCheck("--policy", bundle.toString(), ok.toString())
        assertEquals(ExitCodes.OK, code)
        assertTrue(out.contains("no findings"))
    }

    @Test
    fun `unknown format exits 2`() {
        val (code, out) = runCheck("--policy", "whatever", "--format", "xml", "r.json")
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(out.contains("unknown --format xml"))
    }
}
