package com.pipelinek.policy.cli

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.commands.CompileCmd
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
 * REQ-M9-05/06 · --json-help autodescubrible + exit-code matrix.
 * 05a: root --json-help parses and lists all 9 subcommands.
 * 05b FALSIFICATION: help that diverges from dispatch is caught by
 * cross-checking every listed command against the real dispatch.
 * 06a/06b: matrix ok/violation/refusal/unknown ⇒ 0/2/4/1.
 */
class HelpAndExitCodesTest {

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

    @Test
    fun `05a root json-help lists all nine commands`() {
        val (code, out) = run("--json-help")
        assertEquals(ExitCodes.OK, code)
        // balanced JSON (poor-man's parse guard)
        assertEquals(out.count { it == '{' }, out.count { it == '}' })
        for (cmd in listOf("compile", "check", "test", "diff", "explain", "inspect", "shape", "bundle", "stream")) {
            assertTrue(out.contains("\"$cmd\""), "help must list $cmd")
        }
        assertTrue(out.contains("\"exit_codes\""))
        assertTrue(out.contains("evaluation or configuration error"), out)
    }

    @Test
    fun `05b falsification every listed command dispatches for real`() {
        // Cross-check: every command advertised in --json-help reaches a real
        // handler (usage refusal, NOT "unknown command").
        val (_, help) = run("--json-help")
        val listed = Regex("\"name\": \"([a-z]+)\"").findAll(help).map { it.groupValues[1] }.toList()
        assertEquals(9, listed.size, "help must advertise exactly the command set: $listed")
        for (cmd in listed) {
            val (code, out) = run(cmd)
            assertTrue(
                code == ExitCodes.USAGE && !out.contains("unknown command"),
                "listed command $cmd must dispatch (got $code): $out",
            )
        }
        // And the reverse: dispatch accepts nothing the registry does not declare.
        val (code, out) = run("not-a-command")
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(out.contains("unknown command"))
    }

    @Test
    fun `06a 06b exit code matrix`() {
        val dir = createTempDirectory("m9wu6")
        val bundle = fixtureBundle(dir)
        val ok = dir.resolve("ok.json").also { it.writeText("""{"spec":{"team":"platform"}}""") }
        val bad = dir.resolve("bad.json").also { it.writeText("""{"spec":{"team":"tools"}}""") }
        val refuse = dir.resolve("junk.csv").also { it.writeText("\"unterminated") }

        // ok ⇒ 0
        assertEquals(ExitCodes.OK, run("check", "--policy", bundle.toString(), ok.toString()).first)
        // violation ⇒ 2 (NOT collapsed to 0: falsifies silent-pass)
        assertEquals(ExitCodes.VIOLATIONS, run("check", "--policy", bundle.toString(), bad.toString()).first)
        // decode refusal ⇒ 4
        assertEquals(4, run("check", "--policy", bundle.toString(), refuse.toString()).first)
        // unknown command ⇒ 1
        assertEquals(ExitCodes.USAGE, run("warpipe").first)
    }

    @Test
    fun `exit codes and machine help match the CLI specification`() {
        assertEquals(0, ExitCodes.OK)
        assertEquals(1, ExitCodes.USAGE)
        assertEquals(2, ExitCodes.VIOLATIONS)
        assertEquals(3, ExitCodes.EVALUATION_ERROR)

        val help = run("--json-help").second
        assertTrue(help.contains("\"1\": \"usage or invocation error\""), help)
        assertTrue(help.contains("\"2\": \"policy violation\""), help)
        assertTrue(help.contains("\"3\": \"evaluation or configuration error\""), help)
        assertTrue(help.contains("\"4\": \"bundle or resource admission error\""), help)
        assertTrue(help.contains("\"5\": \"compiler error\""), help)

        val commandHelp = run("check", "--json-help").second
        assertTrue(commandHelp.contains("\"1\": \"usage or invocation error\""), commandHelp)
        assertTrue(commandHelp.contains("\"2\": \"policy violation"), commandHelp)
        assertTrue(commandHelp.contains("\"3\": \"evaluation or configuration error\""), commandHelp)
        assertTrue(commandHelp.contains("\"4\": \"bundle or resource admission error\""), commandHelp)
        assertTrue(commandHelp.contains("\"5\": \"compiler error\""), commandHelp)
    }

    @Test
    fun `every command accepts json-help`() {
        for (cmd in listOf("compile", "check", "test", "diff", "explain", "inspect", "shape", "bundle", "stream")) {
            val (code, out) = run(cmd, "--json-help")
            assertEquals(ExitCodes.OK, code, "$cmd --json-help must exit 0")
            assertTrue(out.contains("\"command\": \"$cmd\""), out)
            assertTrue(out.contains("\"exit_codes\": {\"0\""), out)
            assertTrue(out.contains("\"5\": \"compiler error\""), out)
        }
    }
}
