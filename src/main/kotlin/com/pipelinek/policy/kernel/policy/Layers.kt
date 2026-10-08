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
 * Explicit supersession metadata (spec §3). Presence-only validation in M7:
 * reason/authority/scope must be non-blank and validity must be a non-empty
 * range description; verifying the authority against a registry is a
 * follow-up (the spec does not define the registry).
 */
data class Supersession(
    val supersedes: RuleRef,
    val reason: String,
    val authority: String,
    val scope: String,
    val validity: String,
) {
    init {
        require(reason.isNotBlank()) { "supersession.reason must not be blank" }
        require(authority.isNotBlank()) { "supersession.authority must not be blank" }
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
    fun compose(layers: List<LayeredPolicy>): ComposeResult {
        val ordered = layers.sortedBy { it.layer.ordinal }
        // Accumulator keyed by (policyId, ruleId) -> originating layer.
        val placed = LinkedHashMap<Pair<String, String>, Pair<PolicyLayer, Rule>>()
        val policies = LinkedHashMap<String, Policy>()

        for (layered in ordered) {
            for (policy in layered.policySet.policies) {
                for (rule in policy.rules) {
                    when (val placement = placementOf(placed, layered.layer, policy, rule)) {
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
                // Explicit replace: the superseded rule disappears, the
                // superseding rule takes its place (same key).
                Placement.Replaced
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
