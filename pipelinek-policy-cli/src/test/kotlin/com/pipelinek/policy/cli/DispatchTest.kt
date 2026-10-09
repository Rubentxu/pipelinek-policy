package com.pipelinek.policy.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ-M9-01 · dispatch + usage contract.
 * 01b: unknown command => exit 1 (usage/invocation error) with usage on stdout.
 * 01c FALSIFICATION: a dispatch that ignores argv[0] and falls back to a
 * default command would NOT produce "unknown command" for a bogus name;
 * this test pins the refusal so that mutation breaks it.
 */
class DispatchTest {

    private fun run(vararg args: String): Pair<Int, List<String>> {
        val lines = mutableListOf<String>()
        val code = PolicyCli.run(args.toList()) { lines += it }
        return code to lines
    }

    @Test
    fun `01b unknown command exits 2 with usage`() {
        val (code, lines) = run("frobnicate")
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(lines.any { it.contains("unknown command: frobnicate") })
        assertTrue(lines.any { it.contains("usage:") })
    }

    @Test
    fun `01c falsification dispatch must consume argv0 not default to check`() {
        // A mutated dispatch that ignores the subcommand and always runs
        // `check` would return INTERNAL ("not implemented yet") instead of
        // USAGE for this bogus command.
        val (code, _) = run("definitely-not-a-command")
        assertEquals(ExitCodes.USAGE, code)
    }

    @Test
    fun `empty argv prints usage and exits 2`() {
        val (code, lines) = run()
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(lines.any { it.contains("usage:") })
    }

    @Test
    fun `known command reaches its handler`() {
        // All M9 commands are implemented: each reports its own usage error
        // (USAGE) which proves dispatch routes to the real handler.
        for (cmd in listOf("compile", "check", "test", "diff", "explain", "inspect", "shape")) {
            val (code, lines) = run(cmd)
            assertEquals(ExitCodes.USAGE, code, "expected usage refusal for $cmd")
            assertTrue(lines.any { it.contains(cmd) }, "expected $cmd message")
        }
        // bundle without operation also refuses with usage.
        val (code, lines) = run("bundle")
        assertEquals(ExitCodes.USAGE, code)
        assertTrue(lines.any { it.contains("bundle") })
    }

    @Test
    fun `root --json-help exits 0 with json`() {
        val (code, lines) = run("--json-help")
        assertEquals(ExitCodes.OK, code)
        val json = lines.joinToString("\n")
        assertTrue(json.contains("\"commands\""))
        assertTrue(json.contains("\"compile\""))
    }

    @Test
    fun `command level json-help exits 0`() {
        val (code, lines) = run("check", "--json-help")
        assertEquals(ExitCodes.OK, code)
        assertTrue(lines.joinToString("\n").contains("\"command\": \"check\""))
    }
}
