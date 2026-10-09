package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.PolicyCli
import com.pipelinek.policy.ir.CanonicalPolicyJson
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicyDiff
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
 * REQ-M9-07/08 · inspect / shape / diff.
 * 07b: inspect output re-parses as a valid PolicyIrDocument.
 * 08a: CLI diff digest == direct PolicyDiff API digest.
 * 08b: shape counts match the IR.
 * 08d FALSIFICATION: a CLI that reimplements diff (digest diverges from
 * the API) is caught by comparing digests.
 */
class InspectShapeDiffTest {

    private val dir = createTempDirectory("m9wu3")

    private fun rule(id: String, literal: String) = Rule(
        id, "$id must be $literal",
        Expression.Comparison(
            Expression.FieldRef(DocumentPath.ROOT.child("spec").child("team"), ValueNode.Type.TEXT),
            Expression.Operator.TEXT_EQUALS,
            Expression.Literal(ValueNode.TextValue(literal)),
        ),
    )

    private fun bundle(vararg rules: Rule): java.nio.file.Path {
        val set = PolicySet("uat", listOf(Policy("p", rules.toList())))
        return dir.resolve("b${System.nanoTime() % 100000}.bundle")
            .also { it.writeBytes(PolicyBundle(lower(set)).pack()) }
    }

    private fun resource(): java.nio.file.Path =
        dir.resolve("res-${System.nanoTime() % 100000}.json")
            .also { it.writeText("""{"spec":{"team":"platform"}}""") }

    private fun run(cmd: String, vararg args: String): Pair<Int, String> {
        val sb = StringBuilder()
        val code = PolicyCli.run(listOf(cmd) + args.toList()) { sb.appendLine(it) }
        return code to sb.toString()
    }

    @Test
    fun `07b inspect output reparses as PolicyIrDocument`() {
        val b = bundle(rule("r1", "platform"))
        val (code, out) = run("inspect", "--policy", b.toString())
        assertEquals(ExitCodes.OK, code)
        val doc = CanonicalPolicyJson.decode(out.toByteArray())
        assertEquals("uat", doc.policySet.id)
        assertEquals(1, doc.policySet.policies[0].rules.size)
    }

    @Test
    fun `08b shape counts match the IR`() {
        val b = bundle(rule("r1", "platform"), rule("r2", "tools"))
        val (code, out) = run("shape", "--policy", b.toString())
        assertEquals(ExitCodes.OK, code)
        assertTrue(out.contains("\"policies\": 1"))
        assertTrue(out.contains("\"rules\": 2"))
        assertTrue(out.contains("\"rulesWithParams\": 0"))
    }

    @Test
    fun `08a and 08d diff digest equals the API digest`() {
        val a = bundle(rule("r1", "platform"))
        val b = bundle(rule("r1", "platform"), rule("r2", "tools"))
        val res = resource()
        val (code, out) = run("diff", "--a", a.toString(), "--b", b.toString(), res.toString())
        assertEquals(ExitCodes.VIOLATIONS, code, "new violation r2 expected: $out")

        // Direct API digest over the same inputs.
        val va = com.pipelinek.policy.bundle.BundleVerifier.verifyPacked(a.toFile().readBytes())
        val vb = com.pipelinek.policy.bundle.BundleVerifier.verifyPacked(b.toFile().readBytes())
        val decoded = com.pipelinek.policy.decoders.json.JsonResourceDecoder()
            .decode(res.toFile().readBytes(), com.pipelinek.policy.decoder.DecodeOptions())
            as com.pipelinek.policy.decoder.DecodeResult.Ok
        val doc = decoded.documents.first()
        val ra = com.pipelinek.policy.bundle.IrRuntimeAdapter.evaluate(va, doc.root).report
        val rb = com.pipelinek.policy.bundle.IrRuntimeAdapter.evaluate(vb, doc.root).report
        val apiDigest = PolicyDiff.of(ra, rb).diffDigest

        assertTrue(out.contains("\"digest\": \"$apiDigest\""), "CLI digest must equal API digest: $out")
        assertTrue(out.contains("NEW_VIOLATION"))
        assertTrue(out.contains("\"policyId\": \"p\""))
        assertTrue(out.contains("\"ruleId\": \"r2\""))
    }

    @Test
    fun `identical bundles diff to empty and exit 0`() {
        val a = bundle(rule("r1", "platform"))
        val b = bundle(rule("r1", "platform"))
        val res = resource()
        val (code, out) = run("diff", "--a", a.toString(), "--b", b.toString(), res.toString())
        assertEquals(ExitCodes.OK, code)
        assertTrue(out.contains("\"entries\": 0"))
    }
}
