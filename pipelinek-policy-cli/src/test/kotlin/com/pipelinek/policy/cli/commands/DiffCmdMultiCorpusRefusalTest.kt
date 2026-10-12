package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.CliArgs
import com.pipelinek.policy.cli.ExitCodes
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
 * RC-S6 · `diff` must refuse >1 resource and >1 decoded document.
 *
 * The previous behaviour selected `corpus.first()` and silently dropped
 * every other resource, and `documents.firstOrNull()` did the same for
 * multi-document decoders. A diff that is asked for the union of two
 * corpora MUST observe a typed refusal, not a single-corpus digest that
 * the caller would mistake for a complete result.
 */
class DiffCmdMultiCorpusRefusalTest {

    private fun emptyBundle(): ByteArray {
        val set = PolicySet(
            "uat",
            listOf(
                Policy(
                    "p",
                    listOf(
                        Rule(
                            "floor",
                            "spec.replicas must be >= 1",
                            Expression.Comparison(
                                Expression.FieldRef(
                                    DocumentPath.ROOT.child("spec").child("replicas"),
                                    ValueNode.Type.NUMBER,
                                ),
                                Expression.Operator.GTE,
                                Expression.Literal(ValueNode.NumberValue(1)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return PolicyBundle(lower(set)).pack()
    }

    private fun runDiff(args: List<String>): Pair<Int, String> {
        val buf = StringBuilder()
        val code = DiffCmd.run(CliArgs.parse(args), buf::append)
        return code to buf.toString()
    }

    @Test
    fun `multiple positional corpora is refused with EVALUATION_ERROR`() {
        val dir: Path = createTempDirectory(prefix = "diff-multi-")
        val a = dir.resolve("a.bundle").also { it.writeBytes(emptyBundle()) }
        val b = dir.resolve("b.bundle").also { it.writeBytes(emptyBundle()) }
        val r1 = dir.resolve("r1.json").also { it.writeText("""{"spec":{"replicas":1}}""") }
        val r2 = dir.resolve("r2.json").also { it.writeText("""{"spec":{"replicas":2}}""") }

        val (code, message) = runDiff(
            listOf("--a", a.toString(), "--b", b.toString(), r1.toString(), r2.toString()),
        )

        assertEquals(ExitCodes.EVALUATION_ERROR, code, "diff must refuse multi-resource corpus; was: $message")
        assertTrue(
            message.contains("multiple resources", ignoreCase = true) ||
                message.contains("multi-resource", ignoreCase = true),
            "diff refusal must name the multi-resource limitation; was: $message",
        )
    }

    @Test
    fun `single positional corpus is admitted`() {
        val dir: Path = createTempDirectory(prefix = "diff-single-")
        val a = dir.resolve("a.bundle").also { it.writeBytes(emptyBundle()) }
        val b = dir.resolve("b.bundle").also { it.writeBytes(emptyBundle()) }
        val r1 = dir.resolve("r1.json").also { it.writeText("""{"spec":{"replicas":1}}""") }

        val (code, _) = runDiff(
            listOf("--a", a.toString(), "--b", b.toString(), r1.toString()),
        )

        // OK (no entries) or VIOLATIONS (if the rule fires) — both are valid
        // success exits for the single-corpus case. The refusal exits are
        // USAGE / ADMISSION_ERROR / EVALUATION_ERROR, none of which is in {0,2}.
        assertTrue(
            code == ExitCodes.OK || code == ExitCodes.VIOLATIONS,
            "single corpus must run; was code=$code",
        )
    }
}
