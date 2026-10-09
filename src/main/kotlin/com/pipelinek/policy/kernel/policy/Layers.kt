package com.pipelinek.policy.kernel.policy

/**
 * M7 §"Policy layers" — layered policy composition with explicit authority.
 *
 * Authority order (spec §1): PLATFORM > ORGANIZATION > PROJECT > PIPELINE_LOCAL.
 * Composition is monotonic by default: lower layers ADD constraints; they can
 * only replace an upper-layer rule through an EXPLICIT [Supersession]
 * (spec §3). Duplicate `(policyId, ruleId)` across layers without a
 * supersession grant is REFUSED (spec §2: "No last wins") — architectural
 * law 12: lower layers MUST NOT silently weaken mandatory upper-layer rules.
 *
 * Pure data + pure function (law 5): no I/O, no clock, no globals.
 */

/** Authority layer of a policy source. Ordinal encodes the authority order. */
enum class PolicyLayer {
    PLATFORM,
    ORGANIZATION,
    PROJECT,
    PIPELINE_LOCAL,
}

/** Layer-agnostic reference to a rule: the supersession target. */
data class RuleRef(
    val policyId: String,
    val ruleId: String,
)

/**
 * The authority a supersession ASSERTS (B4-T2).
 *
 * This is a CLAIM carried by the bundle, never a grant. A claim only names
 * who the bundle says authorized it and over which layers; whether that is
 * true is decided solely by the host-supplied [AuthorityRegistry]. Textual
 * authority is not authority: an arbitrary string in a bundle grants nothing.
 *
 * @property issuer who the bundle claims issued the grant.
 * @property grantedLayers the layers the claim says the grant covers.
 * @property grantDigest stable digest of the real grant, so the same
 *   authorization can be recognized across bundle versions.
 */
data class SupersessionAuthority(
    val issuer: String,
    val grantedLayers: Set<PolicyLayer>,
    val grantDigest: String,
) {
    init {
        require(issuer.isNotBlank()) { "SupersessionAuthority.issuer must not be blank" }
        require(grantedLayers.isNotEmpty()) { "SupersessionAuthority.grantedLayers must not be empty" }
        require(grantDigest.isNotBlank()) { "SupersessionAuthority.grantDigest must not be blank" }
    }
}

/**
 * Host-supplied grants (B4-T2, architectural law 10: no global registries).
 *
 * The registry is an ordinary value passed to [LayerComposer.compose]. It is
 * NEVER read from a bundle: `metadata.txt` is untrusted input, so a grant
 * assembled from a claim is just the claim again with a new name. [EMPTY] is
 * the default and it grants nothing, which is the fail-closed reading of
 * "no registry was supplied".
 */
data class AuthorityRegistry(val grants: Map<String, Set<PolicyLayer>> = emptyMap()) {

    /**
     * The layers this issuer is actually authorized to supersede. An unknown
     * issuer grants nothing, and the lookup is absent rather than permissive.
     */
    fun grantsFor(issuer: String): Set<PolicyLayer> = grants[issuer] ?: emptySet()

    /**
     * Whether [claim] is covered by a grant held under the same issuer. The
     * claim must not exceed the grant: naming additional layers the host never
     * granted is a REFUSAL, not a widening of the grant.
     */
    fun covers(claim: SupersessionAuthority): Boolean =
        grants[claim.issuer]?.containsAll(claim.grantedLayers) == true

    /** Whether [claim] is honored for a supersession arriving in [layer]. */
    fun authorizes(claim: SupersessionAuthority, layer: PolicyLayer): Boolean =
        layer in claim.grantedLayers && covers(claim)

    companion object {
        /** Grants nothing. The default: fail closed. */
        val EMPTY = AuthorityRegistry()

        /** A registry holding exactly the given authorities, keyed by issuer. */
        fun of(vararg authorities: SupersessionAuthority): AuthorityRegistry =
            AuthorityRegistry(
                authorities.associate { it.issuer to it.grantedLayers },
            )
    }
}

/**
 * Explicit supersession metadata (spec §3).
 *
 * [authority] is the bundle's CLAIM about who permitted the weakening. It is
 * validated against the host-supplied [AuthorityRegistry] at composition
 * time (B4-T2); a claim without a matching grant is REFUSED.
 */
data class Supersession(
    val supersedes: RuleRef,
    val reason: String,
    val authority: SupersessionAuthority,
    val scope: String,
    val validity: String,
) {
    init {
        require(reason.isNotBlank()) { "supersession.reason must not be blank" }
        require(scope.isNotBlank()) { "supersession.scope must not be blank" }
        require(validity.isNotBlank()) { "supersession.validity must not be blank" }
    }
}

/** A policy set assigned to one authority layer. */
data class LayeredPolicy(
    val layer: PolicyLayer,
    val policySet: PolicySet,
)

/** Typed refusal of a layer composition (spec §2/§3). Never a silent drop. */
sealed class LayerCompositionRefusal(message: String) : IllegalArgumentException(message) {
    data class DuplicateRuleId(
        val policyId: String,
        val ruleId: String,
        val existingLayer: PolicyLayer,
        val incomingLayer: PolicyLayer,
    ) : LayerCompositionRefusal(
            "duplicate (policyId=$policyId, ruleId=$ruleId) in $existingLayer and $incomingLayer " +
                "without explicit supersession; last-wins is refused (spec §2)",
        )

    data class UnknownSupersessionTarget(
        val supersedes: RuleRef,
        val supersedingLayer: PolicyLayer,
    ) : LayerCompositionRefusal(
            "supersession target (policyId=${supersedes.policyId}, ruleId=${supersedes.ruleId}) " +
                "not found below layer $supersedingLayer (spec §3)",
        )

    /**
     * B4-T2: the supersession CLAIMS an authority the host has not granted.
     *
     * This is the refusal that makes architectural law 12 mean something. A
     * bundle naming an issuer grants itself nothing, so a claim without a
     * matching host grant cannot weaken an upper-layer rule.
     */
    data class AuthorityLacksCapability(
        val issuer: String,
        val supersedingLayer: PolicyLayer,
        val grantedLayers: Set<PolicyLayer>,
        val claimedLayers: Set<PolicyLayer>,
    ) : LayerCompositionRefusal(
            "supersession claims authority from issuer=$issuer over " +
                "${claimedLayers.joinToString("+")} for layer $supersedingLayer, but no host grant " +
                "covers it (host grants: ${grantedLayers.joinToString("+").ifEmpty { "none" }}); " +
                "a bundle may claim a grant, never grant one (B4-T2)",
        )
}

/** Result of [LayerComposer.compose]. */
sealed interface ComposeResult {
    data class Composed(val policySet: PolicySet) : ComposeResult

    data class Refused(val refusal: LayerCompositionRefusal) : ComposeResult
}

/**
 * Pure composer. Input order is irrelevant: layers are normalized by
 * authority (PLATFORM first). Higher authority enters first; a lower layer
 * may only replace an existing `(policyId, ruleId)` via an explicit
 * supersession declared on the incoming rule.
 */
object LayerComposer {

    @Suppress("ReturnCount") // typed refusals are early exits by design (spec §2/§3)
    fun compose(
        layers: List<LayeredPolicy>,
        authorities: AuthorityRegistry = AuthorityRegistry.EMPTY,
    ): ComposeResult {
        val ordered = layers.sortedBy { it.layer.ordinal }
        // Accumulator keyed by (policyId, ruleId) -> originating layer.
        val placed = LinkedHashMap<Pair<String, String>, Pair<PolicyLayer, Rule>>()
        val policies = LinkedHashMap<String, Policy>()

        for (layered in ordered) {
            for (policy in layered.policySet.policies) {
                for (rule in policy.rules) {
                    val placement = placementOf(placed, layered.layer, policy, rule, authorities)
                    when (placement) {
                        is Placement.Added -> {
                            placed[policy.id to rule.id] = layered.layer to rule
                            addRule(policies, policy.id, rule)
                        }
                        is Placement.Replaced -> {
                            placed[policy.id to rule.id] = layered.layer to rule
                            replaceRule(policies, policy.id, rule)
                        }
                        is Placement.Refused -> return ComposeResult.Refused(placement.refusal)
                    }
                }
            }
        }
        val id = ordered.joinToString("+") { it.policySet.id }
        return ComposeResult.Composed(
            PolicySet(id = "layered:$id", policies = policies.values.toList()),
        )
    }

    /** Decides what to do with one incoming rule against the placed map. */
    private sealed interface Placement {
        data object Added : Placement
        data object Replaced : Placement
        data class Refused(val refusal: LayerCompositionRefusal) : Placement
    }

    private fun placementOf(
        placed: Map<Pair<String, String>, Pair<PolicyLayer, Rule>>,
        layer: PolicyLayer,
        policy: Policy,
        rule: Rule,
        authorities: AuthorityRegistry,
    ): Placement {
        val key = policy.id to rule.id
        val supersession = rule.supersession
        val existing = placed[key]
        return when {
            existing == null -> {
                if (supersession != null) {
                    // Spec §3: supersession must point at a rule that exists;
                    // replacing nothing is refused.
                    Placement.Refused(
                        LayerCompositionRefusal.UnknownSupersessionTarget(
                            supersedes = supersession.supersedes,
                            supersedingLayer = layer,
                        ),
                    )
                } else {
                    Placement.Added
                }
            }
            supersession != null && supersession.supersedes == RuleRef(policy.id, rule.id) -> {
                // B4-T2: reaching this point used to be the whole decision.
                // The target matching was sufficient on its own, so ANY
                // non-blank authority string let a lower layer replace an
                // upper-layer rule — the bundle granted itself authority by
                // asserting it. The claim now has to be covered by a host
                // grant for this layer before the replacement happens.
                if (authorities.authorizes(supersession.authority, layer)) {
                    // Explicit replace: the superseded rule disappears, the
                    // superseding rule takes its place (same key).
                    Placement.Replaced
                } else {
                    Placement.Refused(
                        LayerCompositionRefusal.AuthorityLacksCapability(
                            issuer = supersession.authority.issuer,
                            supersedingLayer = layer,
                            grantedLayers = authorities.grantsFor(supersession.authority.issuer),
                            claimedLayers = supersession.authority.grantedLayers,
                        ),
                    )
                }
            }
            else -> {
                // Duplicate without an explicit supersession grant on the
                // incoming rule: REFUSED (spec §2).
                Placement.Refused(
                    LayerCompositionRefusal.DuplicateRuleId(
                        policyId = policy.id,
                        ruleId = rule.id,
                        existingLayer = existing.first,
                        incomingLayer = layer,
                    ),
                )
            }
        }
    }

    private fun addRule(policies: MutableMap<String, Policy>, policyId: String, rule: Rule) {
        policies.getOrPut(policyId) { Policy(policyId, mutableListOf()) }
            .let { (it.rules as MutableList<Rule>).add(rule) }
    }

    private fun replaceRule(policies: MutableMap<String, Policy>, policyId: String, rule: Rule) {
        val policy = policies.getOrPut(policyId) { Policy(policyId, mutableListOf()) }
        val rules = policy.rules as MutableList<Rule>
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
    }
}
