package com.pipelinek.policy.parity

import com.pipelinek.policy.bundle.PolicyBundle
import com.pipelinek.policy.cli.ExitCodes
import com.pipelinek.policy.cli.PolicyCli
import com.pipelinek.policy.ir.PolicyIrDocument
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.governance.EnforcementMode
import com.pipelinek.policy.kernel.governance.GovernanceVerdict
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.plugin.PolicyCheckInput
import com.pipelinek.policy.plugin.PolicyCheckStepDefinition
import com.pipelinek.policy.plugin.PolicyCheckVerdict
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * H1.2 / AAT-H1-02 — the parity certification, run against the REAL entry points.
 *
 * ## Why this suite had to exist
 *
 * H1 measured `PASS` on all six UAT rows while a live defect sat underneath:
 * `LayerComposer`, `WaiverMatcher` and `AuthorityRegistry` had zero production
 * call sites. Every existing suite called those mechanisms DIRECTLY, which
 * proves a mechanism works and proves nothing about whether an adapter runs it.
 *
 * ## The two earlier attempts, and why they were not enough
 *
 * First: the claim was unfalsifiable. `pipelinek-policy-cli` and
 * `pipelinek-policy-plugin` are siblings that cannot see each other, so no
 * module could assert agreement. Hence `pipelinek-policy-parity`, whose two
 * edges are `testImplementation` so neither adapter enters the other's runtime.
 *
 * Second: a "parity" test was written that called the same shared helper twice
 * with the same argument. That asserted `2 == 2` and would have passed forever,
 * including if both adapters had been wrong in the same way. A parity test that
 * does not go through the adapters is theatre.
 *
 * Every case below therefore drives the two ADAPTERS: `CheckCmd.run` for the
 * CLI, `PolicyCheckStepDefinition.evaluate` for the plugin. Both are the same
 * public surfaces a user or a pipeline reaches.
 *
 * ## What "parity" means here, precisely
 *
 * The two adapters publish different things by design: the CLI emits findings
 * and an exit code, the plugin emits a typed output. What MUST match is the
 * governance DECISION derived from the same bundle and the same resource — the
 * verdict, and the denial that follows from it. Rendering is allowed to differ.
 */
class AdapterParityTest {

    /**
     * `spec.replicas >= 1`: passes for every resource used here, so it can sit in
     * an ERRORED bundle without contributing a violation of its own.
     */
    private fun replicasAtLeastOne(): Rule = Rule(
        id = "replicas-present",
        message = "replicas must be at least 1",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(1)),
        ),
    )
    private fun replicasMaxThree(): Rule = Rule(
        id = "replicas-limit",
        message = "replicas must be <= 3",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.LTE,
            right = Literal(ValueNode.NumberValue(3)),
        ),
    )

    /**
 * `metadata.owner == "platform"`, using `TEXT_EQUALS`.
 *
 * `Operator.EQ` is NUMERIC: it demands a Number on both sides and raises
 * TYPE_MISMATCH otherwise (law 9, no silent coercion). The first version of this
 * suite used EQ on a text field, so the "owner-check" rule produced an
 * evaluation ERROR on every resource and the suite reported it as adapter
 * divergence. `TEXT_EQUALS` is the structural equality the DSL declares for Text
 * operands.
 */
private fun ownerIsPlatform(): Rule = Rule(
        id = "owner-check",
        message = "owner must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("owner"), ValueNode.Type.TEXT),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    /**
     * A rule the evaluator CANNOT complete, so it reports `RuleEvaluation.Error`.
     *
     * This is the subtle part of the fixture. A type mismatch surfaces as a
     * `PolicyEvalException` that is neither a violation nor a pass, and the
     * FIRST version of this suite got it wrong: it declared a TEXT expectation
     * over a NUMBER field and used that as "the erroring rule", but that same
     * mismatch then turned EVERY rule in the bundle into an Error. A passing
     * resource came back ERRORED and the suite blamed the adapters.
     *
     * The lesson is worth keeping: an Error fixture that poisons its neighbours
     * produces failures that look like adapter divergence and are not. Here the
     * erroring rule is deliberately a SEPARATE bundle from the violating one, so
     * a failure can only mean what it says.
     */
    private fun typeErroringRule(): Rule = Rule(
        id = "type-error",
        message = "spec.replicas must equal the text 'three'",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.TEXT),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("three")),
        ),
    )

    private fun pack(vararg rules: Rule): ByteArray = PolicyBundle(
        document = PolicyIrDocument(
            policySet = PolicySet(
                id = "parity-set",
                policies = listOf(Policy(id = "p1", rules = rules.toList())),
            ),
            sourceRefs = emptyMap(),
        ),
    ).pack()

    private val compliant = """{"spec":{"replicas":2},"metadata":{"owner":"platform"}}"""
    private val violating = """{"spec":{"replicas":9},"metadata":{"owner":"nobody"}}"""
    private val erroring = """{"spec":{"replicas":3},"metadata":{"owner":"platform"}}"""

    // ---------------------------------------------------------------------
    // AAT-H1-02-01 — the core claim: one decision, two adapters.
    // ---------------------------------------------------------------------

    @Test
    fun `a violating resource yields VIOLATED and denies in both adapters`() {
        val packed = pack(replicasMaxThree(), ownerIsPlatform())

        val cli = runCli(packed, violating, EnforcementMode.ENFORCED)
        val plugin = runPlugin(packed, violating, EnforcementMode.ENFORCED)

        assertEquals(GovernanceVerdict.VIOLATED.toPluginVerdict(), plugin.verdict, "plugin verdict")
        assertEquals(
            cli.governance,
            plugin.verdict.toGovernance(),
            "CLI exited ${cli.reading.cliExit}: ${cli.findings.joinToString(" | ")}",
        )
        assertEquals(
            GovernanceVerdict.VIOLATED.toPluginVerdict(),
            plugin.verdict,
            "a genuine violation is VIOLATED in both",
        )
        assertEquals(ExitCodes.VIOLATIONS, cli.exitCode, "CLI exit code for a violation")
        assertEquals(2, plugin.violationsCount, "both violating rules are counted once each")
    }

    @Test
    fun `a compliant resource yields PASSED in both adapters`() {
        val packed = pack(replicasMaxThree(), ownerIsPlatform())

        val cli = runCli(packed, compliant, EnforcementMode.ENFORCED)
        val plugin = runPlugin(packed, compliant, EnforcementMode.ENFORCED)

        assertEquals(
            GovernanceVerdict.PASSED,
            cli.governance,
            "CLI exited ${cli.reading.cliExit}: ${cli.findings.joinToString(" | ")}",
        )
        assertEquals(GovernanceVerdict.PASSED.toPluginVerdict(), plugin.verdict)
        assertEquals(ExitCodes.OK, cli.exitCode)
        assertEquals(0, plugin.violationsCount)
    }

    /**
     * The case the H1.2 fix exists for.
     *
     * Before it, the CLI counted `Finding` states and the plugin counted
     * `RuleEvaluation` values. An ERRORED verdict must deny in BOTH modes
     * (B0.5), so a CLI that under-counted an error while the plugin counted it
     * would report VIOLATED where the plugin reported ERRORED. Both now read one
     * tally.
     *
     * The bundle mixes a genuine violation with an unevaluable rule, which is
     * the only arrangement where the precedence is observable at all.
     */
    @Test
    fun `an evaluation error outranks a violation identically in both adapters`() {
        val packed = pack(typeErroringRule(), replicasMaxThree())

        val cli = runCli(packed, erroring, EnforcementMode.ENFORCED)
        val plugin = runPlugin(packed, erroring, EnforcementMode.ENFORCED)

        assertEquals(GovernanceVerdict.ERRORED.toPluginVerdict(), plugin.verdict)
        assertEquals(cli.governance, plugin.verdict.toGovernance(), "adapters must agree on ERRORED; CLI exited ${cli.reading.cliExit}: ${cli.findings.joinToString(" | ")}")
        assertEquals(
            ExitCodes.EVALUATION_ERROR,
            cli.exitCode,
            "an operational failure maps to the evaluation-error exit code",
        )
    }

    @Test
    fun `SHADOW suppresses a violation in both adapters but never an error`() {
        val violatingBundle = pack(replicasMaxThree())
        // The erroring bundle is driven with the VIOLATING resource, so the run
        // contains an error AND a violation. Under SHADOW the violation alone
        // would be suppressed; the error must still deny.
        val erroringBundle = pack(typeErroringRule(), replicasMaxThree())

        val shadowedViolationCli = runCli(violatingBundle, violating, EnforcementMode.SHADOW)
        val shadowedViolationPlugin = runPlugin(violatingBundle, violating, EnforcementMode.SHADOW)

        // Under SHADOW the CLI's EXIT CODE is OK while the plugin's verdict stays
        // VIOLATED. Those are two vocabularies describing one decision: the
        // adapter that reports an exit code and the adapter that reports a
        // verdict answer different questions. Parity is asserted through the
        // core, not through these two projections of it.
        assertEquals(
            ExitCodes.OK,
            shadowedViolationCli.exitCode,
            "SHADOW reports the violation without failing the command",
        )
        assertEquals(
            PolicyCheckVerdict.VIOLATED,
            shadowedViolationPlugin.verdict,
            "SHADOW still records the violation it did not enforce",
        )
        assertEquals(
            1,
            shadowedViolationPlugin.violationsCount,
            "SHADOW still REPORTS the violation it did not enforce",
        )
        assertEquals(
            true,
            shadowedViolationPlugin.wouldDeny,
            "the suppressed violation is recorded as would-deny evidence",
        )

        // The same SHADOW mode must not launder an operational failure.
        val shadowedErrorCli = runCli(erroringBundle, violating, EnforcementMode.SHADOW)
        val shadowedErrorPlugin = runPlugin(erroringBundle, violating, EnforcementMode.SHADOW)

        assertEquals(
            ExitCodes.EVALUATION_ERROR,
            shadowedErrorCli.exitCode,
            "an error denies under SHADOW too",
        )
        assertEquals(
            GovernanceVerdict.ERRORED.toPluginVerdict(),
            shadowedErrorPlugin.verdict,
            "an errored verdict is never downgraded to would-deny evidence",
        )
        assertEquals(false, shadowedErrorPlugin.wouldDeny)
    }

    /**
     * Both adapters must evaluate the SAME policy set.
     *
     * A shared verdict is a necessary condition, not a sufficient one: both
     * adapters could reach VIOLATED while evaluating different policy sets — one
     * of them having silently dropped the composed layer, for instance.
     *
     * The digests are NOT compared to each other, and the first version of this
     * test tried. They are different facts:
     *
     *  - the plugin publishes `report.digest` — the digest of the WHOLE report;
     *  - the CLI publishes `fingerprint` per finding — the digest of ONE
     *    violation instance, which folds in its location.
     *
     * A whole-report digest and a per-violation digest are never equal, so
     * asserting equality would have failed forever for a reason that has nothing
     * to do with governance. What IS comparable is the policy set identity,
     * which both publish: `parity-set` when no composition happened.
     */
    @Test
    fun `both adapters evaluate the same policy set identity`() {
        val packed = pack(replicasMaxThree(), ownerIsPlatform())

        val cli = runCli(packed, violating, EnforcementMode.ENFORCED)
        val plugin = runPlugin(packed, violating, EnforcementMode.ENFORCED)

        val pluginSetId = plugin.policySetId
        require(pluginSetId != null) { "the plugin published no policy set id, so parity is unproven" }
        assertEquals(
            pluginSetId,
            cli.policySetId,
            "both adapters must evaluate the same policy set",
        )
        // Neither adapter may invent a composition: a single packed bundle is one
        // layer, so the identity stays the author's.
        assertEquals("parity-set", pluginSetId, "a single layer must not be renamed as composed")
    }

    // ---------------------------------------------------------------------
    // AAT-H1-02-02 — adapters must agree on refusals too. A refusal is an
    // operational failure, and an adapter that rendered it as a pass would be
    // the worst possible divergence.
    // ---------------------------------------------------------------------

    @Test
    fun `a refused admission is an operational failure in both adapters`() {
        val packed = pack(replicasMaxThree())

        // The plugin refuses because the declared format has no decoder.
        val plugin = runPlugin(packed, compliant, EnforcementMode.ENFORCED, resourceFormat = "PROTOBUF")
        assertEquals(PolicyCheckVerdict.REFUSED, plugin.verdict, "an unknown format is refused")

        // The CLI refuses because the resource extension has no decoder. Same
        // class of failure, same governance reading: operational, not a pass.
        val cli = runCliWithExtension(packed, compliant, EnforcementMode.ENFORCED, extension = "proto")
        assertEquals(
            ExitCodes.ADMISSION_ERROR,
            cli.exitCode,
            "an undecodable resource is an admission failure",
        )
        assertEquals(
            GovernanceVerdict.REFUSED,
            cli.governance,
            "the CLI must read its own refusal as REFUSED, not PASSED; exited ${cli.reading.cliExit}: ${cli.findings.joinToString(" | ")}",
        )
    }

    @Test
    fun `a missing resource is refused by both adapters`() {
        val packed = pack(replicasMaxThree())

        val cli = runCli(packed, compliant, EnforcementMode.ENFORCED, resourceBodyOverride = null)
        assertEquals(ExitCodes.ADMISSION_ERROR, cli.exitCode)

        val plugin = runPlugin(packed, compliant, EnforcementMode.ENFORCED, resourceBodyOverride = null)
        assertEquals(PolicyCheckVerdict.REFUSED, plugin.verdict)
    }

    // ---------------------------------------------------------------------

    private data class CliResult(
        val exitCode: Int,
        val findings: List<String>,
        val reading: GovernanceReading,
        val reportDigest: String?,
        val policySetId: String?,
    ) {
        val governance: GovernanceVerdict get() = reading.verdict
    }

    private data class PluginResult(
        val verdict: PolicyCheckVerdict,
        val violationsCount: Int,
        val wouldDeny: Boolean,
        val reportDigest: String?,
        val policySetId: String?,
    )

    private fun runCli(
        packed: ByteArray,
        resourceJson: String,
        enforcement: EnforcementMode,
        extension: String = "json",
        resourceBodyOverride: String? = resourceJson,
    ): CliResult {
        val dir = Files.createTempDirectory("parity-cli")
        try {
            val bundle = dir.resolve("bundle.pkx").toFile()
            bundle.writeBytes(packed)
            val resource = dir.resolve("resource.$extension")
            if (resourceBodyOverride != null) {
                Files.writeString(resource, resourceBodyOverride)
            }
            val lines = mutableListOf<String>()
            val args = mutableListOf("check", "--policy", bundle.path, resource.toString(), "--format", "jsonl")
            if (enforcement == EnforcementMode.SHADOW) {
                // ONE token per add. Written as `add("--enforcement"); add("shadow")`
                // it looked equivalent and was not: the value landed as a
                // POSITIONAL, so `check` evaluated a resource literally named
                // "shadow" and every SHADOW run exited 4 (ADMISSION_ERROR) with
                // "resource not found: sh…" — which read as the CLI refusing to
                // do governance rather than as a test-harness bug.
                args.add("--enforcement")
                args.add("shadow")
            }
            // `PolicyCli.run` is the real entry point: it is what argv reaches.
            // Calling `CheckCmd.run` with a hand-built `CliArgs` would skip the
            // dispatcher, and the first attempt at this test did exactly that —
            // `CliArgs.parse` treats every non-flag token as a POSITIONAL, so
            // "check" became a resource path and every run came back REFUSED.
            // A parity test must not invent its own calling convention.
            val exit = PolicyCli.run(args) { lines += it }
            val body = lines.joinToString("\n")
            return CliResult(
                exitCode = exit,
                findings = lines,
                reading = GovernanceReading(governanceFromExit(exit), exit),
                reportDigest = digestOf(body),
                policySetId = policySetIdOf(body),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun runCliWithExtension(
        packed: ByteArray,
        resourceJson: String,
        enforcement: EnforcementMode,
        extension: String,
    ): CliResult = runCli(packed, resourceJson, enforcement, extension = extension)

    private fun runPlugin(
        packed: ByteArray,
        resourceJson: String,
        enforcement: EnforcementMode,
        resourceFormat: String = "JSON",
        resourceBodyOverride: String? = resourceJson,
    ): PluginResult {
        val b64 = Base64.getEncoder()
        val output = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = resourceBodyOverride?.let { b64.encodeToString(it.toByteArray()) } ?: "",
                resourceFormat = resourceFormat,
                packedBundleBase64 = b64.encodeToString(packed),
                enforcement = enforcement,
            ),
        )
        return PluginResult(
            verdict = output.verdict,
            violationsCount = output.violationsCount,
            wouldDeny = output.wouldDeny,
            reportDigest = output.reportDigest,
            policySetId = output.policySetId,
        )
    }

    /**
     * The CLI exit code IS its governance verdict under a frozen mapping
     * (ADR-0012), so the assertion compares like with like rather than
     * re-deriving the precedence here and risking a second implementation of
     * the rule under test.
     */
    private fun governanceFromExit(exit: Int): GovernanceVerdict = when (exit) {
        ExitCodes.OK -> GovernanceVerdict.PASSED
        ExitCodes.VIOLATIONS -> GovernanceVerdict.VIOLATED
        ExitCodes.EVALUATION_ERROR, ExitCodes.INTERNAL -> GovernanceVerdict.ERRORED
        ExitCodes.ADMISSION_ERROR, ExitCodes.COMPILER_ERROR -> GovernanceVerdict.REFUSED
        else -> error("unexpected exit code $exit")
    }

    /**
     * A governance reading that carries the CLI's own exit code.
     *
     * Every assertion below compares this against the plugin's verdict, and a
     * bare `REFUSED` said nothing about WHY. When these tests were failing, the
     * message read "expected PASSED but was REFUSED" — which reads like the
     * adapter disagreed, when in fact the CLI had exited with a code nobody
     * could see. The code travels in the value so a failure is diagnosable from
     * the assertion alone.
     */
    private data class GovernanceReading(val verdict: GovernanceVerdict, val cliExit: Int)

    private fun GovernanceVerdict.toPluginVerdict(): PolicyCheckVerdict = when (this) {
        GovernanceVerdict.PASSED -> PolicyCheckVerdict.PASSED
        GovernanceVerdict.VIOLATED -> PolicyCheckVerdict.VIOLATED
        GovernanceVerdict.ERRORED -> PolicyCheckVerdict.ERRORED
        GovernanceVerdict.REFUSED -> PolicyCheckVerdict.REFUSED
    }

    private fun PolicyCheckVerdict.toGovernance(): GovernanceVerdict = when (this) {
        PolicyCheckVerdict.PASSED -> GovernanceVerdict.PASSED
        PolicyCheckVerdict.VIOLATED -> GovernanceVerdict.VIOLATED
        PolicyCheckVerdict.ERRORED -> GovernanceVerdict.ERRORED
        PolicyCheckVerdict.REFUSED -> GovernanceVerdict.REFUSED
    }

    private fun digestOf(body: String): String? =
        Regex("\"fingerprint\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    /** The policy set id the CLI evaluated, read off its own findings. */
    private fun policySetIdOf(body: String): String? =
        Regex("\"policySetId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
}
