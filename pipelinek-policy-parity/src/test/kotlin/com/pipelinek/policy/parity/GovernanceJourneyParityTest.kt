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
import com.pipelinek.policy.kernel.governance.GovernanceVerdict
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.AuthorityRegistry
import com.pipelinek.policy.kernel.policy.ComposeResult
import com.pipelinek.policy.kernel.policy.LayerComposer
import com.pipelinek.policy.kernel.policy.LayeredPolicy
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicyLayer
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.policy.RuleRef
import com.pipelinek.policy.kernel.policy.Supersession
import com.pipelinek.policy.kernel.policy.SupersessionAuthority
import com.pipelinek.policy.kernel.value.ValueNode
import com.pipelinek.policy.plugin.GrantClaim
import com.pipelinek.policy.plugin.LayerPayload
import com.pipelinek.policy.plugin.PolicyCheckInput
import com.pipelinek.policy.plugin.PolicyCheckStepDefinition
import com.pipelinek.policy.plugin.PolicyCheckVerdict
import com.pipelinek.policy.plugin.PolicyGovernancePayload
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * H1.2 / AAT-H1-02-07 — the journey that could NOT pass while the plugin bypassed
 * the governance service.
 *
 * ## Why the existing parity suite did not catch it
 *
 * Every case in [AdapterParityTest] passes ONE bundle as ONE layer. On a single
 * layer the shared service and the old inline plugin path are
 * behaviourally identical: `LayerComposer.compose` returns the author's set
 * unchanged, there are no waivers to apply and no grant to check. So all seven
 * tests stayed green while `PolicyCheckStepDefinition` was still calling
 * `IrRuntimeAdapter.evaluate` directly — which is exactly what happened, after a
 * `git checkout` reverted the wiring and left the suite "proving" parity between
 * two implementations that no longer shared a path.
 *
 * Parity of the DECISION is not enough. What must be asserted is that both
 * adapters run the same governance, and the only way to observe that is a case
 * where running governance and skipping it produce DIFFERENT answers.
 *
 * ## The four cases
 *
 * Each one flips a single governance input and requires the adapters to track
 * it TOGETHER. A bypass cannot follow the flip, which is the property under
 * test.
 */
class GovernanceJourneyParityTest {

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private fun replicasMaxFive(): Rule = Rule(
        id = "replicas-limit",
        message = "replicas must be <= 5",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.LTE,
            right = Literal(ValueNode.NumberValue(5)),
        ),
    )

    private fun replicasMaxTen(): Rule = Rule(
        id = "replicas-limit",
        message = "replicas must be <= 10",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("spec").child("replicas"), ValueNode.Type.NUMBER),
            op = Operator.LTE,
            right = Literal(ValueNode.NumberValue(10)),
        ),
    )

    private fun pack(setId: String, policyId: String, vararg rules: Rule): ByteArray = PolicyBundle(
        document = PolicyIrDocument(
            policySet = PolicySet(
                id = setId,
                policies = listOf(Policy(id = policyId, rules = rules.toList())),
            ),
            sourceRefs = emptyMap(),
        ),
    ).pack()

    /**
     * The composed id.
     *
     * Written out rather than computed, because the exact string IS part of
     * what is being asserted: a bypass cannot invent it. `LayerComposer`
     * produces `layered:<outer>+<inner>...`, and the first version of this
     * test guessed `layered:<project>` and failed against a value no bypass
     * could have produced either — which is exactly why guessing was wrong.
     */
    private fun composedId(vararg setIds: String): String = "layered:" + setIds.joinToString("+")
    private fun ownerIsPlatform(): Rule = Rule(
        id = "owner-check",
        message = "owner must be platform",
        expression = Comparison(
            left = FieldRef(DocumentPath.ROOT.child("metadata").child("owner"), ValueNode.Type.TEXT),
            op = Operator.TEXT_EQUALS,
            right = Literal(ValueNode.TextValue("platform")),
        ),
    )

    /** 8 replicas: over the project limit (5), under the org limit (10). */
    private val overProjectUnderOrg = """{"spec":{"replicas":8}}"""

    /**
     * Both layers carry the SAME `policyId`.
     *
     * This is the detail that makes the journey work, and the first version
     * missed it. `LayerComposer` identifies a rule by the pair
     * `(policyId, ruleId)`, so two rules sharing a `ruleId` under DIFFERENT
     * policy ids are two distinct rules and compose happily — no
     * `DuplicateRuleId`, no refusal, and both policies evaluated. The
     * unauthorized-duplicate case only exists when the pair collides, which is
     * the case architectural law 12 actually protects.
     */
    private val sharedPolicyId = "shared-policy"

    /**
     * The layer that sits ABOVE the project's.
     *
     * `LayerComposer` orders by `PolicyLayer.ordinal`, and the enum is declared
     * `PLATFORM, ORGANIZATION, PROJECT, PIPELINE_LOCAL` — so `ORGANIZATION`
     * (1) composes BEFORE `PROJECT` (3) and is therefore the OUTER layer. The
     * first version of this test read the enum names as a priority ordering and
     * put the outer layer second, so the superseding rule was placed first and
     * every case refused with `UnknownSupersessionTarget`. The composer was
     * right; the fixture was guessing at the semantics instead of reading them.
     *
     * The plugins and the CLI pass layers through unchanged, so this ordering
     * requirement is now load-bearing for BOTH adapters rather than a detail of
     * one test.
     */
    private val outerLayer = "ORGANIZATION"

    // ---------------------------------------------------------------------
    // Case 1 — RED: composition must reach BOTH adapters.
    // ---------------------------------------------------------------------

    /**
     * Two layers, same rule id, the inner one WEAKENING the outer one.
     *
     * Without an explicit supersession the composer REFUSES (duplicate rule id,
     * last-wins is not allowed), so the observation is a refusal both adapters
     * must report. That refusal is only reachable through `LayerComposer`, which
     * the bypassing plugin never called — it evaluated the project layer alone
     * and would have reported VIOLATED.
     *
     * This is the load-bearing assertion of the whole milestone.
     */
    @Test
    fun `an unauthorized duplicate rule id is refused by both adapters`() {
        val org = pack("org-set", sharedPolicyId, replicasMaxTen())
        val project = pack("project-set", sharedPolicyId, replicasMaxFive())

        val cli = runCli(project, overProjectUnderOrg, layers = listOf("ORGANIZATION" to org))
        val plugin = runPlugin(project, overProjectUnderOrg, layers = listOf("ORGANIZATION" to org))

        assertEquals(
            GovernanceVerdict.REFUSED,
            cli.governance,
            "the CLI must refuse an un-authorized composition; findings=${cli.findings.joinToString(" | ")}",
        )
        assertEquals(
            PolicyCheckVerdict.REFUSED,
            plugin.verdict,
            "the plugin must reach the SAME refusal — a path that never composes " +
                "layers cannot produce it",
        )
    }

    // ---------------------------------------------------------------------
    // Case 2 — authorized supersession CHANGES the outcome, in both adapters.
    // ---------------------------------------------------------------------

    /**
     * The same two layers, now with the project rule superseding the org one
     * under a host grant.
     *
     * Two independent facts are asserted, and they pull in opposite directions
     * on purpose:
     *
     *  - with the grant the composition succeeds and the tightening holds, so
     *    8 replicas VIOLATES;
     *  - against case 1, the identical layers now do NOT refuse.
     *
     * A test that only asserted the first would pass even if `AuthorityRegistry`
     * were ignored entirely. It is the PAIR that proves the grant is consulted:
     * an implementation that never checks authority refuses both ways, and one
     * that never composes passes both ways.
     */
    @Test
    fun `an authorized supersession is honoured identically by both adapters`() {
        val digest = "sha256:grant-for-security"
        val org = pack("org-set", sharedPolicyId, replicasMaxTen())
        val project = packSuperseding("project-set", sharedPolicyId)

        val payload = PolicyGovernancePayload(
            grants = listOf(
                GrantClaim(
                    issuer = "security",
                    layers = listOf("PROJECT"),
                    digest = digest,
                ),
            ),
        )

        val cli = runCli(
            project,
            overProjectUnderOrg,
            layers = listOf("ORGANIZATION" to org),
            grants = listOf("security" to ("PROJECT" to digest)),
        )
        val plugin = runPlugin(
            project,
            overProjectUnderOrg,
            layers = listOf("ORGANIZATION" to org),
            governance = payload,
        )

        assertEquals(
            GovernanceVerdict.VIOLATED,
            cli.governance,
            "the CLI must apply the supersession and enforce the tighter rule; " +
                "findings=${cli.findings.joinToString(" | ")}",
        )
        assertEquals(
            PolicyCheckVerdict.VIOLATED,
            plugin.verdict,
            "the plugin must reach the same VIOLATED through composition",
        )
        assertEquals(cli.governance, plugin.verdict.toGovernance())
    }

    // ---------------------------------------------------------------------
    // Case 3 — the grant digest is the authority, not the issuer name.
    // ---------------------------------------------------------------------

    /**
     * Same issuer, same layers, WRONG digest.
     *
     * `AuthorityRegistry.covers` requires the digest to MATCH, so a bundle
     * attaching a real issuer name to a fabricated authorization is REFUSED.
     * This is the case that distinguishes "the host grant was consulted" from
     * "an issuer string was present", which is the whole point of B4-T2.
     */
    @Test
    fun `a supersession with the right issuer and the wrong digest is refused by both adapters`() {
        val org = pack("org-set", sharedPolicyId, replicasMaxTen())
        val project = packSuperseding("project-set", sharedPolicyId)

        val cli = runCli(
            project,
            overProjectUnderOrg,
            layers = listOf("ORGANIZATION" to org),
            // The host holds digest A; the bundle CLAIMS digest B.
            grants = listOf("security" to ("PROJECT" to "sha256:a-different-grant")),
        )
        val plugin = runPlugin(
            project,
            overProjectUnderOrg,
            layers = listOf("ORGANIZATION" to org),
            governance = PolicyGovernancePayload(
                grants = listOf(
                    GrantClaim(
                        issuer = "security",
                        layers = listOf("PROJECT"),
                        digest = "sha256:a-different-grant",
                    ),
                ),
            ),
        )

        assertEquals(
            GovernanceVerdict.REFUSED,
            cli.governance,
            "a digest mismatch is an authority failure, not a string match",
        )
        assertEquals(
            PolicyCheckVerdict.REFUSED,
            plugin.verdict,
            "the plugin must apply the same authority check",
        )
    }

    // ---------------------------------------------------------------------
    // Case 4 — RED: composition must change the POLICY SET IDENTITY.
    // ---------------------------------------------------------------------

    /**
     * The cheapest structural detector of a bypass, and the one that would have
     * caught the reverted wiring immediately.
     *
     * A single layer keeps the author's id (`parity-set` in the sibling suite).
     * A REAL composition publishes `layered:<id>`. So the policy set id alone
     * tells you whether the composer ran at all — and a plugin that skipped it
     * could not produce a different id no matter what it did with the tally.
     */
    @Test
    fun `composed layers change the published policy set identity in both adapters`() {
        val org = pack("org-set", sharedPolicyId, replicasMaxTen())
        // This case composes two layers WITHOUT a duplicate rule, so the
        // composition genuinely succeeds and both adapters publish an id.
        //
        // The first version reused the unauthorized-duplicate fixture, and it
        // asserted on `policySetId` of a REFUSED composition — which is null by
        // definition. It "failed" while proving nothing about composition: the
        // refusal, not the missing composition, was what it measured. Here the
        // rules do not collide, so the composer really runs and really renames.
        val project = pack("project-set", "project-only-policy", ownerIsPlatform())

        val single = runPlugin(pack("project-set", "project-only-policy", ownerIsPlatform()), overProjectUnderOrg)
        val composed = runPlugin(
            project,
            overProjectUnderOrg,
            layers = listOf(outerLayer to org),
        )

        assertEquals("project-set", single.policySetId, "one layer keeps the author's id")
        assertEquals(
            composedId("org-set", "project-set"),
            composed.policySetId,
            "a real composition publishes the composed id — the plugin cannot " +
                "reach this without running LayerComposer",
        )
        assertNotEquals(
            single.policySetId,
            composed.policySetId,
            "composing must be observable in the output identity",
        )

        // And the CLI must agree on that identity, which is the cross-adapter
        // half: both publish it, so both had to go through the same composer.
        val cli = runCli(project, overProjectUnderOrg, layers = listOf(outerLayer to org))
        assertEquals(
            composed.policySetId,
            cli.policySetId,
            "both adapters must publish the composed policy set identity",
        )
        assertTrue(
            cli.policySetId != null && cli.policySetId!!.startsWith("layered:"),
            "the CLI refused or never composed: ${cli.findings.joinToString(" | ")}",
        )
    }

    // ---------------------------------------------------------------------
    // Guards: the fixtures must actually exercise the mechanism.
    // ---------------------------------------------------------------------

    /**
     * Without this, every case above could pass because both adapters refused a
     * fixture that is invalid for some unrelated reason.
     *
     * This asserts the mechanism DIRECTLY through the core, which is the only
     * place the answer is authoritative: composing these two layers with this
     * registry REFUSES, and with the matching grant it does NOT.
     */
    @Test
    fun `the fixture distinguishes refused from honoured at the core`() {
        val orgPolicy = PolicySet(
            id = "org-set",
            policies = listOf(Policy(id = sharedPolicyId, rules = listOf(replicasMaxTen()))),
        )
        val projectPolicy = PolicySet(
            id = "project-set",
            policies = listOf(Policy(id = sharedPolicyId, rules = listOf(replicasMaxFive()))),
        )

        // No grant at all: the composition must refuse.
        val refused = LayerComposer.compose(
            listOf(
                LayeredPolicy(PolicyLayer.ORGANIZATION, orgPolicy),
                LayeredPolicy(PolicyLayer.PROJECT, projectPolicy),
            ),
            AuthorityRegistry.EMPTY,
        )
        assertTrue(
            refused is ComposeResult.Refused,
            "a duplicate rule id with no supersession must refuse; got $refused",
        )

        // A real supersession with no grant is still refused: the CLAIM is not
        // the GRANT (B4-T2). This is asserted here so the journey above cannot
        // pass with a fixture whose supersession was never wired at all.
        //
        // Two constraints the first version of this fixture got wrong, both
        // learned by reading `placementOf` instead of guessing:
        //
        //  - `supersession` hangs off the RULE, not the Policy.
        //  - the superseding rule must carry the SAME `(policyId, ruleId)` as
        //    the rule it replaces; `placementOf` matches `supersedes` against
        //    the INCOMING rule's own key. So a bundle that supersedes
        //    `shared-policy/replicas-limit` must itself BE
        //    `shared-policy/replicas-limit`, otherwise there is nothing placed
        //    under the key to replace and the composition refuses with
        //    `UnknownSupersessionTarget` — which is what it did.
        val superseding = PolicySet(
            id = "project-set",
            policies = listOf(
                Policy(
                    id = sharedPolicyId,
                    rules = listOf(
                        replicasMaxFive().copy(
                            supersession = Supersession(
                                supersedes = RuleRef(policyId = sharedPolicyId, ruleId = "replicas-limit"),
                                reason = "project needs a tighter bound",
                                authority = SupersessionAuthority(
                                    issuer = "security",
                                    grantedLayers = setOf(PolicyLayer.PROJECT),
                                    grantDigest = "sha256:grant-for-security",
                                ),
                                scope = "all",
                                validity = "permanent",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val granted = LayerComposer.compose(
            listOf(
                LayeredPolicy(PolicyLayer.ORGANIZATION, orgPolicy),
                LayeredPolicy(PolicyLayer.PROJECT, superseding),
            ),
            AuthorityRegistry.of(
                SupersessionAuthority(
                    issuer = "security",
                    grantedLayers = setOf(PolicyLayer.PROJECT),
                    grantDigest = "sha256:grant-for-security",
                ),
            ),
        )
        assertTrue(
            granted is ComposeResult.Composed,
            "with a matching grant the composition must succeed; got $granted",
        )
    }

    // ---------------------------------------------------------------------
    // Helpers — both go through the REAL entry points.
    // ---------------------------------------------------------------------

    private fun packSuperseding(setId: String, policyId: String): ByteArray = PolicyBundle(
        document = PolicyIrDocument(
            policySet = PolicySet(
                id = setId,
                policies = listOf(
                    Policy(
                        id = policyId,
                        rules = listOf(
                            replicasMaxFive().copy(
                                supersession = Supersession(
                                    supersedes = RuleRef(policyId = sharedPolicyId, ruleId = "replicas-limit"),
                                    reason = "project needs a tighter bound",
                                    authority = SupersessionAuthority(
                                        issuer = "security",
                                        grantedLayers = setOf(PolicyLayer.PROJECT),
                                        grantDigest = "sha256:grant-for-security",
                                    ),
                                    scope = "all",
                                    validity = "permanent",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            sourceRefs = emptyMap(),
        ),
    ).pack()

    private data class CliResult(
        val exitCode: Int,
        val findings: List<String>,
        val governance: GovernanceVerdict,
        val policySetId: String?,
    )

    private data class PluginResult(
        val verdict: PolicyCheckVerdict,
        val violationsCount: Int,
        val wouldDeny: Boolean,
        val reportDigest: String?,
        val policySetId: String?,
    )

    private fun PolicyCheckVerdict.toGovernance(): GovernanceVerdict = when (this) {
        PolicyCheckVerdict.PASSED -> GovernanceVerdict.PASSED
        PolicyCheckVerdict.VIOLATED -> GovernanceVerdict.VIOLATED
        PolicyCheckVerdict.ERRORED -> GovernanceVerdict.ERRORED
        PolicyCheckVerdict.REFUSED -> GovernanceVerdict.REFUSED
    }

    private fun runCli(
        packed: ByteArray,
        resourceJson: String,
        layers: List<Pair<String, ByteArray>> = emptyList(),
        grants: List<Pair<String, Pair<String, String>>> = emptyList(),
    ): CliResult {
        val dir = Files.createTempDirectory("journey-cli")
        try {
            val bundle = dir.resolve("bundle.pkx").toFile()
            bundle.writeBytes(packed)
            val resource = dir.resolve("resource.json")
            Files.writeString(resource, resourceJson)

            // The structured format, because `policySetId` is only published in JSON
            // output. The CLI's identity assertion reads that field off the body,
            // and in the default text format there is nothing to read — which
            // made it look like the CLI had not composed, when it had composed
            // and simply not said so.
            val args = mutableListOf("check", "--policy", bundle.path, resource.toString(), "--format", "jsonl")
            // The CLI's layer and grant flags are REPEATABLE, so each layer and
            // each grant adds two argv tokens. These are the flags that let the
            // CLI journey reach the same composer the plugin path does.
            layers.forEach { (name, bytes) ->
                val layerFile = dir.resolve("$name.pkx").toFile()
                layerFile.writeBytes(bytes)
                args.add("--layer")
                args.add("$name=${layerFile.path}")
            }
            grants.forEach { (issuer, layerAndDigest) ->
                args.add("--grant")
                args.add("$issuer:${layerAndDigest.first}:${layerAndDigest.second}")
            }
            val lines = mutableListOf<String>()
            val exit = PolicyCli.run(args) { lines += it }
            val body = lines.joinToString("\n")
            val exitVerdict = when (exit) {
                ExitCodes.OK -> GovernanceVerdict.PASSED
                ExitCodes.VIOLATIONS -> GovernanceVerdict.VIOLATED
                ExitCodes.EVALUATION_ERROR, ExitCodes.INTERNAL -> GovernanceVerdict.ERRORED
                ExitCodes.ADMISSION_ERROR, ExitCodes.COMPILER_ERROR -> GovernanceVerdict.REFUSED
                else -> error("unexpected exit code $exit")
            }
            return CliResult(
                exitCode = exit,
                findings = lines,
                governance = exitVerdict,
                policySetId = Regex("\"policySetId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun runPlugin(
        packed: ByteArray,
        resourceJson: String,
        layers: List<Pair<String, ByteArray>> = emptyList(),
        governance: PolicyGovernancePayload = PolicyGovernancePayload.EMPTY,
    ): PluginResult {
        val b64 = Base64.getEncoder()
        val output = PolicyCheckStepDefinition.evaluate(
            PolicyCheckInput(
                resourceBase64 = b64.encodeToString(resourceJson.toByteArray()),
                resourceFormat = "JSON",
                packedBundleBase64 = b64.encodeToString(packed),
                governance = governance.copy(
                    layers = layers.map { (name, bytes) ->
                        LayerPayload(
                            layer = name,
                            packedPolicyBase64 = b64.encodeToString(bytes),
                        )
                    },
                ),
                now = "2024-01-01T00:00:00Z",
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
}
