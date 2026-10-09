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

    private fun errorAndViolationBundleBytes(): ByteArray {
        val teamPath = DocumentPath.ROOT.child("metadata").child("team")
        val errorRule = Rule(
            "team-text",
            "metadata.team must be text",
            Expression.Comparison(
                Expression.FieldRef(teamPath, ValueNode.Type.TEXT),
                Expression.Operator.TEXT_EQUALS,
                Expression.Literal(ValueNode.TextValue("platform")),
            ),
        )
        val violationRule = Rule(
            "team-minimum",
            "metadata.team must be at least 10",
            Expression.Comparison(
                Expression.FieldRef(teamPath, ValueNode.Type.NUMBER),
                Expression.Operator.GTE,
                Expression.Literal(ValueNode.NumberValue(10)),
            ),
        )
        val set = PolicySet("uat", listOf(Policy("p", listOf(errorRule, violationRule))))
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
        assertEquals(4, code, "admission refusal must dominate: $out")
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
    fun `typed evaluator error is distinct from violation and takes precedence`() {
        val dir = kotlin.io.path.createTempDirectory("m9check-error")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(errorAndViolationBundleBytes()) }
        val resource = dir.resolve("wrong-type.json")
            .also { it.writeText("""{"metadata":{"team":7}}""") }

        for (format in listOf("text", "json", "jsonl")) {
            val (code, out) = runCheck(
                "--policy", bundle.toString(), "--format", format, resource.toString(),
            )

            assertEquals(ExitCodes.EVALUATION_ERROR, code, "typed engine error must outrank a violation: $out")
            when (format) {
                "text" -> {
                    assertTrue(out.contains("[ERROR]") && out.contains("team-text"), out)
                    assertTrue(out.contains("[VIOLATION]") && out.contains("team-minimum"), out)
                }
                "json" -> {
                    assertTrue(out.contains("\"state\": \"error\"") && out.contains("team-text"), out)
                    assertTrue(out.contains("\"state\": \"violation\"") && out.contains("team-minimum"), out)
                }
                else -> {
                    assertTrue(out.contains("\"state\":\"error\"") && out.contains("team-text"), out)
                    assertTrue(out.contains("\"state\":\"violation\"") && out.contains("team-minimum"), out)
                }
            }
        }
    }

    @Test
    fun `decode refusal still outranks typed evaluator error`() {
        val dir = kotlin.io.path.createTempDirectory("m9check-refusal-error")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(errorAndViolationBundleBytes()) }
        val resource = dir.resolve("wrong-type.json")
            .also { it.writeText("""{"metadata":{"team":7}}""") }
        val refused = dir.resolve("broken.csv").also { it.writeText("\"unterminated") }

        val (code, out) = runCheck(
            "--policy", bundle.toString(), "--format", "jsonl", resource.toString(), refused.toString(),
        )

        assertEquals(4, code, "admission refusal must remain the highest-priority outcome: $out")
        assertTrue(out.contains("\"state\":\"error\""), out)
        assertTrue(out.contains("\"state\":\"violation\""), out)
        assertTrue(out.contains("\"state\":\"refusal\""), out)
    }

    @Test
    fun `02b falsification extension decides decoder no sniffing`() {
        val dir = kotlin.io.path.createTempDirectory("m9check3")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        // JSON content with an unknown extension: must REFUSE, not sniff-parse.
        val sneaky = dir.resolve("sneaky.conf").also { it.writeText("""{"metadata":{"team":"x"}}""") }
        val (code, out) = runCheck("--policy", bundle.toString(), sneaky.toString())
        assertEquals(4, code)
        assertTrue(out.contains("no decoder for extension .conf"))
        // And valid json content under .csv refuses too (csv parser rejects it).
        val fakeCsv = dir.resolve("fake.csv").also { it.writeText("""{"metadata":{"team":"x"}}""") }
        val (code2, out2) = runCheck("--policy", bundle.toString(), fakeCsv.toString())
        assertTrue(code2 == 4 || code2 == ExitCodes.VIOLATIONS, out2)
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
    fun `02c falsification dedup keeps distinct occurrences of the same rule`() {
        val dir = kotlin.io.path.createTempDirectory("m9check-dedup-occ")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes()) }
        // Two DIFFERENT files with the same shape: the JSON decoder derives
        // resourceId from a node counter, so both documents get the SAME
        // resourceId. Old dedup (policyId|ruleId|resourceId) collapsed the
        // second file's violation into the first, hiding a real violation.
        val bad1 = dir.resolve("bad1.json").also { it.writeText("""{"metadata":{"team":"tools"}}""") }
        val bad2 = dir.resolve("bad2.json").also { it.writeText("""{"metadata":{"team":"other"}}""") }

        val (code, out) = runCheck(
            "--policy", bundle.toString(), "--format", "jsonl",
            bad1.toString(), bad2.toString(),
        )

        assertEquals(ExitCodes.VIOLATIONS, code, out)
        val findings = out.lines().filter { it.contains("\"state\":\"violation\"") }
        assertEquals(
            2, findings.size,
            "distinct resources (same resourceId, different fingerprint) must survive dedup: $out",
        )
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
