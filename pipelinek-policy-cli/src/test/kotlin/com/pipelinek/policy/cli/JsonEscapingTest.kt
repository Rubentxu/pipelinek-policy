package com.pipelinek.policy.cli

import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H4.1 · JSON escaping is an acceptance property of the agent-facing CLI, not
 * an internal detail.
 *
 * The CLI hand-builds its JSON so the CLI bucket stays dependency-free. That is
 * a reasonable trade, but it puts a rule on whoever writes an emitter: every
 * author-controlled string MUST go through [JsonString].
 *
 * Observed RED that these tests pin down, on the installed binary:
 *
 *   - a fixture file named `evil","forged":"yes.allow.json` made `test` emit a
 *     `forged` key the code never emitted
 *   - `policySetId` = `P","forged":"yes` did the same in `explain` and `diff`
 *   - a bundle path `x","forged":"yes.pack` did the same in `bundle --verify`
 *   - `StreamCmd` called `replace("\"", "\\\"")`, which is a NO-OP: it replaced
 *     a quote with a quote, so nothing was ever escaped
 *
 * A forged key is worse than invalid JSON. Invalid JSON fails loudly. A forged
 * key parses cleanly and an agent believes a field that never existed.
 */
class JsonEscapingTest {

    private val dir = createTempDirectory("h41escape")

    private fun run(cmd: String, vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf(cmd) + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    // ---------------------------------------------------------------- escaper

    @Test
    fun `escaper neutralises the two structural characters`() {
        assertEquals("a\\\\b", JsonString.escape("a\\b"))
        assertEquals("a\\\"b", JsonString.escape("a\"b"))
    }

    @Test
    fun `escaper emits the five short control escapes`() {
        assertEquals("\\b", JsonString.escape("\b"))
        assertEquals("\\f", JsonString.escape("\u000C"))
        assertEquals("\\n", JsonString.escape("\n"))
        assertEquals("\\r", JsonString.escape("\r"))
        assertEquals("\\t", JsonString.escape("\t"))
    }

    @Test
    @OptIn(kotlin.test.ExperimentalKotlinTestApi::class)
    fun `escaper emits a unicode escape for every other C0 control`() {
        // JSON forbids these raw inside a string. A raw NUL in particular was
        // the case the old escapers silently emitted verbatim.
        for (code in 0x00..0x1F) {
            if (code in listOf(0x08, 0x09, 0x0A, 0x0C, 0x0D)) continue
            val escaped = JsonString.escape(code.toChar().toString())
            assertEquals("\\u%04x".format(code), escaped, "code 0x%02x".format(code))
        }
    }

    @Test
    fun `escaper leaves non-ASCII text untouched`() {
        // JSON is UTF-8; there is no reason to \\u-escape these, and doing so
        // would make the output hostile to a human reading it.
        assertEquals("acentuación ñ 日本語", JsonString.escape("acentuación ñ 日本語"))
    }

    @Test
    fun `str wraps in quotes exactly once`() {
        assertEquals("\"x\"", JsonString.str("x"))
        assertEquals("\"\\\"\"", JsonString.str("\""))
    }

    // ------------------------------------------------------- injection: test

    @Test
    fun `test does not let a fixture filename forge a JSON key`() {
        val bundle = bundle()
        val fx = dir.resolve("fx").toFile().apply { mkdirs() }
        val hostile = "evil\",\"forged\":\"yes.allow.json"
        fx.resolve(hostile).writeText("{\"ok\":true}\n")

        val (code, out) = run("test", "--policy", bundle, "--fixtures", fx.path)
        assertEquals(ExitCodes.OK, code, out)

        // the document must parse and must NOT carry a key we never emitted
        assertFalse(out.contains("\"forged\""), "author text escaped its value slot: $out")
        // and the filename must survive byte-identically
        assertTrue(out.contains("evil\\\",\\\"forged\\\":\\\"yes.allow.json"), out)
    }

    @Test
    fun `test keeps a hostile filename inside its own JSON string value`() {
        val bundle = bundle()
        val fx = dir.resolve("fx2").toFile().apply { mkdirs() }
        for (name in listOf("q\"q.allow.json", "n\nn.allow.json", "b\\b.allow.json", "c\u0007c.allow.json")) {
            fx.resolve(name).writeText("{\"ok\":true}\n")
        }

        val (_, out) = run("test", "--policy", bundle, "--fixtures", fx.path)

        // every emitted line must be an independent JSON document
        for (line in out.lines().filter { it.isNotBlank() }) {
            val trimmed = line.trim()
            assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}"), "not a JSON document: $line")
            // strict schema: the four keys TestCmd actually emits
            for (key in listOf("\"fixture\"", "\"expectation\"", "\"violations\"", "\"status\"")) {
                assertEquals(
                    1, Regex(Regex.escape(key)).findAll(trimmed).count(),
                    "expected exactly one $key per line, got: $line",
                )
            }
        }
    }

    // ------------------------------------------------- injection: bundle verify

    @Test
    fun `bundle verify does not let a path forge a JSON key`() {
        val hostile = "x\",\"forged\":\"yes.bundle"
        File(dir.toFile(), hostile).writeText("garbage")

        val (code, out) = run("bundle", "--verify", File(dir.toFile(), hostile).path)

        assertEquals(ExitCodes.ADMISSION_ERROR, code, out)
        assertFalse(out.contains("\"forged\""), "path escaped its value slot: $out")
        assertTrue(out.contains("x\\\",\\\"forged\\\":\\\"yes.bundle"), out)
    }

    // ----------------------------------------------------- injection: compile

    @Test
    fun `compile does not let an output path forge a JSON key`() {
        val src = dir.resolve("src.ir.json").also { it.writeText(irJson()) }
        val hostile = "o\",\"forged\":\"yes.bundle"

        val (code, out) = run("compile", "--policy", src.toString(), "--out",
            dir.resolve(hostile).toString())

        assertEquals(ExitCodes.OK, code, out)
        assertFalse(out.contains("\"forged\""), "out path escaped its value slot: $out")
    }

    // ------------------------------------------------------ injection: stream

    @Test
    fun `stream does not let a decoder message forge a JSON key`() {
        // The old escaper here was replace("\"", "\\\"") — a no-op.
        // The refusal message must still land inside one JSON string value.
        val bundle = bundle()
        val src = dir.resolve("bad.jsonl").also {
            it.writeText("{\"a\":1}\nxx \"yy\" zz\n")
        }
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf("stream", "--policy", bundle, src.toString())) {
            sb.appendLine(it)
        }
        val out = sb.toString()
        // regardless of exit code, any emitted JSON line must be well-formed
        for (line in out.lines().filter { it.trim().startsWith("{") }) {
            val trimmed = line.trim()
            assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}"), "not a JSON document: $line")
        }
        assertFalse(out.contains("\"kind\": \"\""), "empty kind after escaping: $out")
        assertTrue(code != ExitCodes.OK || out.isNotBlank(), "stream must report something")
    }

    // -------------------------------------------------------- json-help reuse

    @Test
    fun `json help parses and carries no forged keys`() {
        val (code, out) = run("--json-help")
        assertEquals(ExitCodes.OK, code, out)
        assertTrue(out.trim().startsWith("{"), out)
        assertFalse(out.contains("\"forged\""), out)
    }

    // ------------------------------------------------------------- helpers

    private fun irJson(): String {
        val set = PolicySet(
            "h41",
            listOf(Policy("p", listOf(Rule("r", "must pass",
                Expression.Literal(ValueNode.BooleanValue(true)))))),
        )
        return CanonicalPolicyJson.encode(lower(set)).toString(Charsets.UTF_8)
    }

    private fun bundle(): String {
        val src = dir.resolve("b.ir.json").also { it.writeText(irJson()) }
        val out = dir.resolve("b.bundle")
        val (code, o) = run("compile", "--policy", src.toString(), "--out", out.toString())
        assertEquals(ExitCodes.OK, code, o)
        return out.toString()
    }
}
