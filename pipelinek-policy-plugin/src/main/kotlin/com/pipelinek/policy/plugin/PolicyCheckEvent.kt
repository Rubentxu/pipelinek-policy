package com.pipelinek.policy.plugin

import com.pipelinek.policy.kernel.governance.EnforcementMode
import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode

/**
 * M6 REQ-05 · payload of the `policy.check.reported` event.
 *
 * Deterministic shape: verdict name, counts and the report digest — the same
 * values the step output already carries, so the event is an observation of
 * the work, never a second source of truth for it.
 *
 * M7 REQ-M7-04: `enforcement` and `wouldDeny` are APPENDED to the v1 wire
 * format (v1 payloads without them decode with ENFORCED/wouldDeny=false),
 * so M6 consumers keep working while shadow runs are observable.
 */
data class PolicyCheckReported(
    val verdict: String,
    val violationsCount: Int,
    val reportDigest: String?,
    val policySetId: String?,
    val enforcement: EnforcementMode = EnforcementMode.ENFORCED,
    val wouldDeny: Boolean = false,
)

/**
 * Hand-written deterministic codec, mirroring the SDK example: the same payload
 * must encode to the same bytes every run, and an unknown schema version is a
 * named refusal (`PayloadDecode.UnknownSchemaVersion`), never a guess.
 */
object PolicyCheckReportedCodec : EventPayloadCodec<PolicyCheckReported> {
    private const val V1 = 1
    private const val SEP = "\u001F" // unit separator: cannot appear in these fields

    override fun encode(payload: PolicyCheckReported): String =
        "v1$SEP${payload.verdict}$SEP${payload.violationsCount}" +
            "$SEP${payload.reportDigest ?: "-"}$SEP${payload.policySetId ?: "-"}" +
            "$SEP${payload.enforcement.name}$SEP${payload.wouldDeny}"

    override fun decode(raw: String, schemaVersion: Int): PayloadDecode<PolicyCheckReported> {
        if (schemaVersion != V1) return PayloadDecode.UnknownSchemaVersion(schemaVersion)
        val parts = raw.split(SEP)
        // M6 wire form (5 fields) and M7 wire form (7 fields) are both valid v1.
        if (!(parts.size == 5 || parts.size == 7) || parts[0] != "v1") {
            return PayloadDecode.Malformed("not a v1 policy.check.reported payload: $raw")
        }
        val count = parts[2].toIntOrNull()
            ?: return PayloadDecode.Malformed("v1 payload carries a non-numeric count: $raw")
        fun field(i: Int): String? = parts[i].takeUnless { it == "-" }
        val enforcement = if (parts.size == 7) {
            runCatching { EnforcementMode.valueOf(parts[5]) }.getOrNull()
                ?: return PayloadDecode.Malformed("v1 payload carries an unknown enforcement mode: $raw")
        } else {
            EnforcementMode.ENFORCED
        }
        return PayloadDecode.Decoded(
            PolicyCheckReported(
                verdict = parts[1],
                violationsCount = count,
                reportDigest = field(3),
                policySetId = field(4),
                enforcement = enforcement,
                wouldDeny = if (parts.size == 7) parts[6].toBooleanStrictOrNull() ?: false else false,
            ),
        )
    }
}

/**
 * ServiceLoader entry point:
 * `META-INF/services/dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor`.
 *
 * The kind is namespaced (`policy.check.reported`) because the registry refuses
 * unnamespaced kinds before any collision can be recorded.
 */
class PolicyEventContributor : EventDefinitionContributor {
    override val id: String = PolicyCheckStepDefinition.PLUGIN_ID

    override fun definitions(): Iterable<EventDefinitionCreation<*>> = listOf(
        EventDefinition.create(
            kind = PolicyCheckStepDefinition.REPORTED_EVENT_KIND,
            schemaVersion = 1,
            payloadClass = PolicyCheckReported::class.java,
            emittedBy = PolicyCheckStepDefinition.PLUGIN_ID,
            codec = PolicyCheckReportedCodec,
        ),
    )
}
