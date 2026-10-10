package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.policy.AuthorityRegistry
import com.pipelinek.policy.kernel.policy.PolicyLayer
import com.pipelinek.policy.kernel.policy.SupersessionAuthority
import kotlinx.serialization.Serializable

/**
 * H1.2 / B4-T10 · the governance payload that travels through the step input.
 *
 * ## Why this type exists
 *
 * `PolicyCheckInput` is `@Serializable`, so it cannot carry
 * [AuthorityRegistry] or a `Waiver` directly: those hold `Instant` values and a
 * selector tree that no wire format describes. The alternative was dropping
 * them, which is what actually happened — the plugin ran
 * `IrRuntimeAdapter.evaluate(...)` inline with no layers, no waivers and no
 * host grant, so `LayerComposer`, `WaiverMatcher` and `AuthorityRegistry` had no
 * production caller on the plugin side at all.
 *
 * This is the SEAM, not a second governance path. It is an ordered, all-data
 * projection of what the host already holds; [toAuthorityRegistry] and
 * [toWaivers] are the only places it is turned back into kernel types, and both
 * are pure mapping. An adapter still cannot decide anything here: there is no
 * verdict field, no tally and no policy to evaluate, so a payload cannot
 * disagree with the core about the outcome.
 *
 * ## Deliberately absent
 *
 * No `enforcement` field: that already lives on [PolicyCheckInput] and must not
 * be given a second, competing home. No clock: law 5 forbids the kernel from
 * reading time, so the host passes [PolicyCheckInput.now] instead of letting a
 * waiver window be evaluated against wall-clock time inside the step.
 */
@Serializable
data class PolicyGovernancePayload(
    /**
     * Host-held grants, keyed by issuer. A bundle that asserts a supersession
     * is only honoured when a grant here covers it — the claim is never the
     * authority. Empty means fail-closed, which is the default.
     */
    val grants: List<GrantClaim> = emptyList(),
    /**
     * Extra layers under governance, ordered from the OUTERMOST authority to
     * the innermost so composition order is data, not code.
     */
    val layers: List<LayerPayload> = emptyList(),
    /** Waivers the host has already admitted. Empty means nothing is exempted. */
    val waivers: List<WaiverPayload> = emptyList(),
) {
    /**
     * Rebuild the kernel registry. Kept here so the wire shape and the kernel
     * shape cannot drift apart: there is exactly one mapping, and it is total.
     */
    fun toAuthorityRegistry(): AuthorityRegistry = AuthorityRegistry(
        grants.associate { grant ->
            grant.issuer to SupersessionAuthority(
                issuer = grant.issuer,
                grantedLayers = grant.toPolicyLayers(),
                grantDigest = grant.digest,
            )
        },
    )

    companion object {
        /** Grants nothing, exempts nothing, composes nothing. The fail-closed default. */
        val EMPTY = PolicyGovernancePayload()
    }
}

/**
 * One host-held grant, in wire form.
 *
 * [digest] is compared for equality against the digest the host holds for this
 * issuer; the kernel never computes one (ADR-0011), so this is a claim the host
 * asserts about itself.
 */
@Serializable
data class GrantClaim(
    val issuer: String,
    val layers: List<String>,
    val digest: String,
) {
    /**
     * Resolve the wire layer names to the closed enum.
     *
     * An unknown name is a REFUSAL, not a default: silently dropping it would
     * shrink [grantedLayers] and turn an over-broad grant into a narrow one
     * without telling anyone. The `require` is the fail-closed reading, and it
     * fires before any composition happens.
     */
    fun toPolicyLayers(): Set<PolicyLayer> = layers.mapTo(mutableSetOf()) { name ->
        requireNotNull(PolicyLayer.entries.firstOrNull { it.name == name }) {
            "GrantClaim.layer '$name' is not a PolicyLayer: " +
                PolicyLayer.entries.joinToString(", ") { it.name }
        }
    }
}

/** A governance layer as it travels through the wire. */
@Serializable
data class LayerPayload(
    val layer: String,
    /**
     * The packed policy document for this layer, base64. The host packs it;
     * the step verifies it through the same path the main bundle takes, so a
     * layer is admitted or refused on identical terms to the project layer.
     */
    val packedPolicyBase64: String,
) {
    fun toPolicyLayer(): PolicyLayer =
        requireNotNull(PolicyLayer.entries.firstOrNull { it.name == layer }) {
            "LayerPayload.layer '$layer' is not a PolicyLayer: " +
                PolicyLayer.entries.joinToString(", ") { it.name }
        }
}

/** A waiver as it travels through the wire. */
@Serializable
data class WaiverPayload(
    val id: String,
    val policyId: String,
    val ruleId: String,
    /**
     * The path the waiver is scoped to (e.g. `metadata.name`). Split from
     * [subjectEquals] rather than parsed from one string so the wire never has
     * an ambiguous encoding to guess at.
     */
    val subjectPath: String,
    val subjectEquals: String,
    val datasetScope: String? = null,
    val projectScope: String? = null,
    val reason: String,
    val issuer: String,
    /** RFC-3339 instants. The kernel reads them, never the clock. */
    val notBefore: String,
    val notAfter: String,
) {
    /**
     * Parse the validity window.
     *
     * A malformed instant is a REFUSAL rather than a silently open window: a
     * waiver that fails to parse as "now" would exempt a violation forever, and
     * "exempt forever" is exactly the silent weakening law 12 forbids.
     */
    fun validity(): Pair<java.time.Instant, java.time.Instant> =
        java.time.Instant.parse(notBefore) to java.time.Instant.parse(notAfter)
}