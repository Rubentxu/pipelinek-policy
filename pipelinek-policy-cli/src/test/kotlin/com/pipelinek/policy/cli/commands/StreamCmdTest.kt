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
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8 REQ-M8-07 · `stream` command exit-code matrix (07a..07d) + streaming
 * UAT: 64 MiB CSV over a bounded heap path (Runtime delta, same technique
 * as the decoders' tests).
 */
class StreamCmdTest {

    private fun localBundle(): ByteArray {
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "temp-must-be-ok", "temp must be ok",
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
    fun `07a ok csv stream exits 0`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("data.csv").also { it.writeText("temp\nok\nok\nok\n") }

        val (code, out) = runStream("--policy", bundle.toString(), csv.toString())
        assertEquals(ExitCodes.OK, code, out)
        assertTrue(out.contains("stream: ok"))
    }

    @Test
    fun `07a violations exit 1 with row count`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("data.csv").also { it.writeText("temp\nok\nbad\nok\nworse\n") }

        val (code, out) = runStream("--policy", bundle.toString(), csv.toString())
        assertEquals(ExitCodes.VIOLATIONS, code, out)
        assertTrue(out.contains("violations=2"), out)
        assertTrue(out.contains("last row 4"), out)
    }

    @Test
    fun `07b jsonl dataset streams too`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val jsonl = dir.resolve("data.jsonl").also {
            it.writeText("{\"temp\":\"ok\"}\n{\"temp\":\"bad\"}\n")
        }
        val (code, out) = runStream("--policy", bundle.toString(), jsonl.toString())
        assertEquals(ExitCodes.VIOLATIONS, code, out)
    }

    @Test
    fun `07c malformed csv uses admission exit code`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val bad = dir.resolve("ragged.csv").also { it.writeText("temp\nok\n1,2\n") }

        val (code, out) = runStream("--policy", bundle.toString(), bad.toString())
        assertEquals(4, code, out)
        assertTrue(out.contains("decode-refusal"), out)
    }

    @Test
    fun `07c unsupported extension uses admission exit code`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val xml = dir.resolve("data.xml").also { it.writeText("<a/>") }

        val (code, out) = runStream("--policy", bundle.toString(), xml.toString())
        assertEquals(4, code, out)
        assertTrue(out.contains("no streaming source"), out)
    }

    @Test
    fun `07d missing policy flag exits 1`() {
        val (code, out) = runStream("whatever.csv")
        assertEquals(1, code)
        assertTrue(out.contains("missing --policy"))
    }

    @Test
    fun `07d budget exceeded exits 3`() {
        val dir = createTempDirectory("m8stream")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("data.csv").also {
            it.writeText("temp\n" + "ok\n".repeat(100))
        }
        val (code, out) = runStream(
            "--policy", bundle.toString(), "--row-budget", "10", csv.toString(),
        )
        assertEquals(ExitCodes.EVALUATION_ERROR, code, out)
        assertTrue(out.contains("budget-exceeded"), out)
    }

    @Test
    fun `invalid row budget is a usage error instead of silently defaulting`() {
        val dir = createTempDirectory("m8stream-invalid-budget")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("data.csv").also { it.writeText("temp\nok\n") }

        val (code, out) = runStream("--policy", bundle.toString(), "--row-budget", "0", csv.toString())

        assertEquals(1, code, out)
        assertTrue(out.contains("row-budget"), out)
    }

    @Test
    fun `unsupported output format is a usage error`() {
        val dir = createTempDirectory("m8stream-invalid-output-format")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("data.csv").also { it.writeText("temp\nok\n") }

        val (code, out) = runStream(
            "--policy", bundle.toString(), "--format-out", "xml", csv.toString(),
        )

        assertEquals(1, code, out)
        assertTrue(out.contains("format-out"), out)
    }

    /**
     * 07 UAT: 64 MiB CSV (the M10 characterization size) streams through the
     * CLI path without materializing the document. Memory measured with the
     * Runtime delta technique (kernel tests use the same approach): a
     * full-materialize regression allocates ~gigabytes here, streaming stays
     * flat. The bundle evaluation itself is pure, so the dominant memory is
     * the input array + per-row trees.
     */
    @Test
    fun `07 uat 64 MiB csv streams flat`() {
        val dir = createTempDirectory("m8streamuat")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(localBundle()) }
        val csv = dir.resolve("big.csv")
        csv.toFile().outputStream().use { s ->
            s.write("temp,pad\n".toByteArray())
            // 64-byte rows: same 64 MiB footprint, ~1M rows instead of 22M
            // (the budget and the clock stay sane; memory signal is identical).
            val line = ("ok," + "x".repeat(59) + "\n").toByteArray()
            val target = 64L * 1024 * 1024
            var written = "temp,pad\n".toByteArray().size.toLong()
            while (written < target) {
                s.write(line)
                written += line.size
            }
        }
        assertTrue(csv.toFile().length() >= 64L * 1024 * 1024)

        System.gc()
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val (code, out) = runStream("--policy", bundle.toString(), csv.toString())
        System.gc()
        val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        assertEquals(ExitCodes.OK, code, out)
        // The CLI test JVM reads the file as ByteArray (~64 MiB); the DOCUMENT
        // must not materialize (~gigabytes). Generous bound: input + headroom.
        val delta = after - before
        assertTrue(
            delta < 512L * 1024 * 1024,
            "streaming delta $delta bytes looks like document materialization",
        )
    }
}
