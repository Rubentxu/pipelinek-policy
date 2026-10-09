package com.pipelinek.policy.kernel.policy

import com.pipelinek.policy.kernel.expression.Expression.Comparison
import com.pipelinek.policy.kernel.expression.Expression.FieldRef
import com.pipelinek.policy.kernel.expression.Expression.Literal
import com.pipelinek.policy.kernel.expression.Expression.Operator
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M7 REQ-M7-01 / REQ-M7-02 — layered composition.
 *
 * Scenarios 01a/01b/02a/02b from M7_SPECIFICATION plus the falsification
 * contracts (01c/02c): the assertions are written so that a mutation to
 * "last wins" or "ignore the supersession target" FAILS these tests.
 */
class LayersTest {

    private fun rule(id: String, threshold: Long) = Rule(
        id = id,
        message = "spec.replicas must be >= $threshold",
        expression = Comparison(
            left = FieldRef(
                DocumentPath.ROOT.child("spec").child("replicas"),
                ValueNode.Type.NUMBER,
            ),
            op = Operator.GTE,
            right = Literal(ValueNode.NumberValue(threshold)),
        ),
    )

    private fun set(id: String, vararg rules: Rule) = PolicySet(id = id, policies = listOf(Policy(id, rules.toList())))

    // --- REQ-M7-01 scenario 01a: distinct policy ids compose ---

    @Test
    fun `01a compose adds constraints across layers in authority order`() {
        val platform = LayeredPolicy(PolicyLayer.PLATFORM, set("platform-baseline", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("project-extra", rule("max-replicas", 10)))

        val result = LayerComposer.compose(listOf(project, platform)) // input order irrelevant

        val composed = assertIs<ComposeResult.Composed>(result)
        val ruleIds = composed.policySet.policies.flatMap { it.rules.map { r -> r.id } }
        assertEquals(listOf("min-replicas", "max-replicas"), ruleIds)
    }

    // --- REQ-M7-01 scenario 01b: duplicate refused, no last-wins ---

    @Test
    fun `01b duplicate policyId ruleId across layers is refused`() {
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", rule("min-replicas", 5)))

        val result = LayerComposer.compose(listOf(org, project))

        val refused = assertIs<ComposeResult.Refused>(result)
        val dup = assertIs<LayerCompositionRefusal.DuplicateRuleId>(refused.refusal)
        assertEquals("shared", dup.policyId)
        assertEquals("min-replicas", dup.ruleId)
        assertEquals(PolicyLayer.ORGANIZATION, dup.existingLayer)
        assertEquals(PolicyLayer.PROJECT, dup.incomingLayer)
    }

    /**
     * REQ-M7-01 scenario 01c (falsification): under a "last wins" mutation
     * (silently keeping the incoming duplicate) 01b already fails; this test
     * additionally pins that the KEPT rule would be the upper-layer one, i.e.
     * a mutation that drops the ORGANIZATION rule in favor of PROJECT is
     * detected by the composed rule content.
     */
    @Test
    fun `01c mutation-contract - silent drop or last-wins must not compose`() {
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", rule("min-replicas", 5)))

        val result = LayerComposer.compose(listOf(org, project))

        // Any non-refused outcome here is a weakening of an upper layer (law 12).
        assertTrue(result is ComposeResult.Refused, "duplicate must refuse, got: $result")
    }

    // --- REQ-M7-02 scenario 02a: explicit supersession replaces ---

    @Test
    fun `02a supersession with full metadata replaces only the targeted rule`() {
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "project requires higher floor",
                authority = SupersessionAuthority(
                    issuer = "project-admin",
                    grantedLayers = setOf(PolicyLayer.PROJECT),
                    grantDigest = "sha256:m7",
                ),
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3), rule("other", 7)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", superseding))

        // B4-T2: the replacement now needs a host grant naming the PROJECT
        // layer. Without it the same composition refuses.
        val result = LayerComposer.compose(
            listOf(org, project),
            AuthorityRegistry.of(superseding.supersession!!.authority),
        )

        val composed = assertIs<ComposeResult.Composed>(result)
        val shared = composed.policySet.policies.single { it.id == "shared" }
        // Exactly one min-replicas rule: the superseding one (threshold 5).
        assertEquals(1, shared.rules.count { it.id == "min-replicas" })
        val kept = shared.rules.single { it.id == "min-replicas" }
        val keptThreshold = ((kept.expression as Comparison).right as Literal).value
        val keptNumber = (keptThreshold as ValueNode.NumberValue).number
        assertEquals(5L, keptNumber.toLong())
        // Untouched sibling survives.
        assertTrue(shared.rules.any { it.id == "other" })
    }

    // --- REQ-M7-02 scenario 02b: unknown target refused ---

    @Test
    fun `02b supersession of nonexistent target is refused`() {
        val orphan = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "does-not-exist"),
                reason = "broken reference",
                authority = SupersessionAuthority(
                    issuer = "project-admin",
                    grantedLayers = setOf(PolicyLayer.PROJECT),
                    grantDigest = "sha256:m7",
                ),
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", orphan))

        val result = LayerComposer.compose(listOf(project))

        val refused = assertIs<ComposeResult.Refused>(result)
        val unknown = assertIs<LayerCompositionRefusal.UnknownSupersessionTarget>(refused.refusal)
        assertEquals(RuleRef("shared", "does-not-exist"), unknown.supersedes)
    }

    /**
     * REQ-M7-02 scenario 02c (falsification): a mutation that ignores the
     * supersession target entirely (treats every supersession as a plain add)
     * makes 02b pass a Composed result — pinned to fail here — and would also
     * leave BOTH rules in 02a.
     */
    @Test
    fun `02c mutation-contract - ignoring the target must not compose`() {
        val orphan = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "does-not-exist"),
                reason = "broken reference",
                authority = SupersessionAuthority(
                    issuer = "project-admin",
                    grantedLayers = setOf(PolicyLayer.PROJECT),
                    grantDigest = "sha256:m7",
                ),
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val result = LayerComposer.compose(listOf(LayeredPolicy(PolicyLayer.PROJECT, set("shared", orphan))))
        assertTrue(result is ComposeResult.Refused, "orphan supersession must refuse, got: $result")
    }

    // --- B4-T2: supersession authority is a host-granted capability ---

    /**
     * A supersession may only weaken an upper-layer rule if some authority has
     * actually granted that capability. `Supersession.authority` is what the
     * bundle ASSERTS about itself; only the host-injected [AuthorityRegistry]
     * is a grant. With no registry (the default `EMPTY`), a bundle that
     * claims any authority at all must be REFUSED.
     */
    @Test
    fun `02d a claimed authority with no grant refuses`() {
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "project requires higher floor",
                authority = SupersessionAuthority(
                    issuer = "acme-platform",
                    grantedLayers = setOf(PolicyLayer.PROJECT),
                    grantDigest = "sha256:0f1e2d",
                ),
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", superseding))

        val result = LayerComposer.compose(listOf(org, project))

        val refused = assertIs<ComposeResult.Refused>(result, "textual authority is not authority")
        assertIs<LayerCompositionRefusal.AuthorityLacksCapability>(refused.refusal)
    }

    @Test
    fun `02e a matching host grant composes`() {
        val authority = SupersessionAuthority(
            issuer = "acme-platform",
            grantedLayers = setOf(PolicyLayer.PROJECT),
            grantDigest = "sha256:0f1e2d",
        )
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "project requires higher floor",
                authority = authority,
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", superseding))
        val registry = AuthorityRegistry.of(authority)

        val result = LayerComposer.compose(listOf(org, project), registry)

        val composed = assertIs<ComposeResult.Composed>(result)
        val kept = composed.policySet.policies.single { it.id == "shared" }.rules.single { it.id == "min-replicas" }
        val threshold = ((kept.expression as Comparison).right as Literal).value
        assertEquals(5L, (threshold as ValueNode.NumberValue).number.toLong())
    }

    /**
     * The grant is scoped to the layer it names. A PROJECT grant must not
     * authorize a PIPELINE_LOCAL supersession, or a local rule could rewrite
     * the platform floor merely by naming a real issuer.
     */
    @Test
    fun `02f a grant does not extend past the layers it names`() {
        val authority = SupersessionAuthority(
            issuer = "acme-platform",
            grantedLayers = setOf(PolicyLayer.PROJECT),
            grantDigest = "sha256:0f1e2d",
        )
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "local override",
                authority = authority,
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val local = LayeredPolicy(PolicyLayer.PIPELINE_LOCAL, set("shared", superseding))

        val result = LayerComposer.compose(listOf(org, local), AuthorityRegistry.of(authority))

        val refused = assertIs<ComposeResult.Refused>(result, "a PROJECT grant must not authorize PIPELINE_LOCAL")
        assertIs<LayerCompositionRefusal.AuthorityLacksCapability>(refused.refusal)
    }

    /**
     * Order irrelevance is a property of the composer, not an artifact of a
     * registry-free path. It must still hold when a registry is supplied.
     */
    @Test
    fun `02g order irrelevance holds with a registry present`() {
        val authority = SupersessionAuthority(
            issuer = "acme-platform",
            grantedLayers = setOf(PolicyLayer.PROJECT),
            grantDigest = "sha256:0f1e2d",
        )
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "project requires higher floor",
                authority = authority,
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val project = LayeredPolicy(PolicyLayer.PROJECT, set("shared", superseding))
        val registry = AuthorityRegistry.of(authority)

        val forward = LayerComposer.compose(listOf(org, project), registry)
        val reversed = LayerComposer.compose(listOf(project, org), registry)

        assertEquals(forward, reversed, "input order must not change the composed outcome")
    }

    /**
     * B4-T2 second mutation: a bundle may CLAIM a grant, never GRANT one.
     *
     * The claim on the supersession names an issuer and even names the layers
     * it wants. The host granted something NARROWER. The registry is the only
     * thing that decides, so the wider claim must be refused rather than
     * honoured — otherwise the bundle is granting itself authority simply by
     * describing itself accurately.
     */
    @Test
    fun `02h a claim wider than the host grant is refused`() {
        val granted = SupersessionAuthority(
            issuer = "acme-platform",
            grantedLayers = setOf(PolicyLayer.PROJECT),
            grantDigest = "sha256:0f1e2d",
        )
        // Same issuer, same digest: the claim asks for both layers.
        val claimed = granted.copy(grantedLayers = setOf(PolicyLayer.PROJECT, PolicyLayer.PIPELINE_LOCAL))
        val superseding = rule("min-replicas", 5).copy(
            supersession = Supersession(
                supersedes = RuleRef("shared", "min-replicas"),
                reason = "local override",
                authority = claimed,
                scope = "project:demo",
                validity = "2026-01-01/2027-01-01",
            ),
        )
        val org = LayeredPolicy(PolicyLayer.ORGANIZATION, set("shared", rule("min-replicas", 3)))
        val local = LayeredPolicy(PolicyLayer.PIPELINE_LOCAL, set("shared", superseding))

        val result = LayerComposer.compose(listOf(org, local), AuthorityRegistry.of(granted))

        val refused = assertIs<ComposeResult.Refused>(
            result,
            "the claim must not exceed the host grant, however well it matches",
        )
        assertIs<LayerCompositionRefusal.AuthorityLacksCapability>(refused.refusal)
    }

    /**
     * The default parameter is the fail-closed path, and it is the path every
     * existing caller takes until a host explicitly opts in. Pinned so that
     * changing the default to a permissive registry fails here.
     */
    @Test
    fun `02i the registry default is the empty fail-closed registry`() {
        assertEquals(AuthorityRegistry.EMPTY, AuthorityRegistry.of())
    }
}
