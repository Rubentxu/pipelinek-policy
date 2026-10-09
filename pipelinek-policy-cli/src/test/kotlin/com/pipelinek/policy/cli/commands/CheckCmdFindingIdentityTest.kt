package com.pipelinek.policy.cli.commands

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.IrRuntimeAdapter
import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.PolicyCli
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.ir.PolicyIrLowerer.lower
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.WaiverMatcher
import com.pipelinek.policy.kernel.value.ValueNode
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3.6 falsification · a finding must publish the identity of the rule that
 * produced it, not a composite of it.
 *
 * `RuleKey` is `policySetId + policyId + ruleId`, and `CheckCmd` published the
 * length-prefixed composite `RuleKey.value` under the field `ruleId`, while
 * publishing the POLICY SET id under `policyId`. The internal digest kept the
 * real identity; the agent-facing JSONL surface did not, so an agent could
 * neither address a rule nor match a waiver against a finding.
 */
class CheckCmdFindingIdentityTest {

    private fun teamRule(id: String, expected: String): Rule = Rule(
        id,
        "metadata.team must be $expected",
        Expression.Comparison(
            Expression.FieldRef(
                DocumentPath.ROOT.child("metadata").child("team"),
                ValueNode.Type.TEXT,
            ),
            Expression.Operator.TEXT_EQUALS,
            Expression.Literal(ValueNode.TextValue(expected)),
        ),
    )

    /** Two policies, each with a rule carrying the SAME simple id. */
    private fun collidingBundle(): ByteArray = PolicyBundle(
        lower(
            PolicySet(
                "uat",
                listOf(
                    Policy("alpha", listOf(teamRule("shared-rule", "platform"))),
                    Policy("beta", listOf(teamRule("shared-rule", "payments"))),
                ),
            ),
        ),
    ).pack()

    private fun run(dir: Path, bundle: Path, resource: Path): Pair<Int, List<String>> =
        run("jsonl", bundle, resource)

    private fun run(format: String, bundle: Path, resource: Path): Pair<Int, List<String>> {
        val out = mutableListOf<String>()
        val code = PolicyCli.run(
            listOf("check", "--policy", bundle.toString(), "--format", format, resource.toString()),
        ) { out.add(it) }
        return code to out.flatMap { it.lines() }.filter { it.isNotBlank() }
    }

    private fun field(line: String, name: String): String =
        Regex("\"$name\":\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            ?: error("field $name absent from finding: $line")

    @Test
    fun `json publishes the three identity components as separate fields`() {
        val dir = createTempDirectory("b36-json")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(collidingBundle()) }
        val resource = dir.resolve("r.json").also {
            it.writeText("""{"metadata":{"team":"tools"}}""")
        }

        val (code, out) = run("json", bundle, resource)

        assertEquals(ExitCodes.VIOLATIONS, code)
        // json and jsonl share one emitter, but the pretty printer is a second
        // code path: assert the identity survives it rather than assume it.
        assertTrue(out.any { it.contains("\"policySetId\": \"uat\"") }, "policySetId must be emitted in json: $out")
        assertEquals(2, Regex("\"ruleId\": \"shared-rule\"").findAll(out.joinToString("\n")).count())
        assertEquals(
            setOf("alpha", "beta"),
            Regex("\"policyId\": \"([^\"]*)\"").findAll(out.joinToString("\n")).map { it.groupValues[1] }.toSet(),
            "json must name the owning policy, never the policy set",
        )
        assertTrue(
            Regex("\"ruleId\": \"3:uat").find(out.joinToString("\n")) == null,
            "the length-prefixed RuleKey composite must never be published: $out",
        )
    }

    @Test
    fun `jsonl publishes the simple rule id and the owning policy id`() {
        val dir = createTempDirectory("b36-identity")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(collidingBundle()) }
        val resource = dir.resolve("r.json").also {
            it.writeText("""{"metadata":{"team":"tools"}}""")
        }

        val (code, findings) = run(dir, bundle, resource)

        assertEquals(ExitCodes.VIOLATIONS, code)
        assertEquals(2, findings.size, "both policies must report: $findings")
        findings.forEach { line ->
            assertEquals("shared-rule", field(line, "ruleId"), "the SIMPLE rule id must be published: $line")
            assertEquals("uat", field(line, "policySetId"), "the policy set id is its own field: $line")
            assertTrue(
                setOf("alpha", "beta").contains(field(line, "policyId")),
                "policyId must be the OWNING policy, not the policy set: $line",
            )
        }
        assertEquals(
            setOf("alpha", "beta"),
            findings.map { field(it, "policyId") }.toSet(),
            "the two policies must be distinguishable",
        )
    }

    @Test
    fun `two policies sharing a rule id produce distinct findings`() {
        val dir = createTempDirectory("b36-collision")
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(collidingBundle()) }
        val resource = dir.resolve("r.json").also {
            it.writeText("""{"metadata":{"team":"tools"}}""")
        }

        val (_, findings) = run(dir, bundle, resource)

        val fingerprints = findings.map { field(it, "fingerprint") }
        assertEquals(2, fingerprints.distinct().size, "same ruleId in two policies must not collide: $findings")
        assertEquals(2, findings.map { field(it, "violationId") }.distinct().size)
    }

    @Test
    fun `the published identity reproduces the kernel waiver fingerprint`() {
        val dir = createTempDirectory("b36-waiver")
        val bundleBytes = collidingBundle()
        val bundle = dir.resolve("p.bundle").also { it.writeBytes(bundleBytes) }
        val resource = dir.resolve("r.json").also {
            it.writeText("""{"metadata":{"team":"tools"}}""")
        }

        val (_, findings) = run(dir, bundle, resource)
        val line = findings.first { field(it, "policyId") == "alpha" }

        // A waiver pins a ViolationFingerprint, and the matcher computes it
        // from the kernel's own (policySetId, policyId, ruleId, location,
        // resource fingerprint). Reproducing that digest from the PUBLISHED
        // fields is what makes a waiver able to address this finding at all.
        //
        // B4-T3: this must go through the REAL matcher, not a second
        // hand-rolled computation. Recomputing the digest the same way the
        // CLI already computes it proves CLI-vs-CLI agreement, which is
        // self-fulfilling and hid the fact that the kernel was passing the
        // composite RuleKey while the CLI passed the simple rule id.
        val verified = BundleVerifier.verifyPacked(bundleBytes)
        val decoded = JsonResourceDecoder().decode(resource.readBytes())
        val tree = (decoded as DecodeResult.Ok).documents.single().root
        val report = IrRuntimeAdapter.evaluate(verified, tree).report

        val kernelFingerprints = WaiverMatcher.apply(
            report = report,
            waivers = emptyList(),
            now = Instant.EPOCH,
            subjectResolver = { null },
        ).outcomes.map { it.fingerprint.value }.toSet()

        assertTrue(
            field(line, "fingerprint") in kernelFingerprints,
            "the published fingerprint must equal one the kernel matcher computes; " +
                "kernel=${kernelFingerprints} published=${field(line, "fingerprint")}",
        )
    }
}
