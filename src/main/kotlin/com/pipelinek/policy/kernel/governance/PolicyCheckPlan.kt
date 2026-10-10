package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.bundle.BundleVerifier
import com.pipelinek.policy.bundle.VerifiedBundle
import com.pipelinek.policy.decoder.ResourceFormat

/**
 * B4.6 / ADR-0016 · typed refusals of [PolicyCheckPlan].
 *
 * Closed vocabulary (law 9): no free-form reason strings inside the kernel.
 *
 * [render] is deliberately the enum NAME and nothing else. The adapter renders
 * it into the existing `PolicyCheckOutput.refused(reason: String)` string, and
 * because the string equals the name, promoting these to wire-visible codes in
 * B6 is a mechanical change rather than a second vocabulary to maintain.
 *
 * `RESOURCE_DECODE_REFUSED` is NOT a plan refusal in the sense of the others:
 * the plan cannot know it, because the decode result arrives after the plan is
 * computed. It lives here so the whole refusal surface of one check has one
 * vocabulary, and it is emitted by the host after performing the decode.
 */
enum class PolicyCheckPlanRefusal {
    /** The resource string is not valid Base64. */
    RESOURCE_NOT_BASE64,

    /** The packed bundle string is not valid Base64. */
    BUNDLE_NOT_BASE64,

    /** The declared resource format is not a member of [ResourceFormat]. */
    UNKNOWN_RESOURCE_FORMAT,

    /** No decoder is registered for the admitted format. */
    NO_DECODER_FOR_FORMAT,

    /** The packed bundle failed admission. */
    BUNDLE_REFUSED,

    /** The decoder refused the resource CONTENT. Emitted by the host, post-plan. */
    RESOURCE_DECODE_REFUSED,

    /**
     * The decode succeeded but produced more or fewer than one document.
     *
     * The plan cannot detect this: it is an answer the decoder returns AFTER
     * admission (ADR-0016). Emitted by the host, with the observed count kept
     * as diagnostic only, never as part of the wire vocabulary.
     */
    MULTI_DOCUMENT_RESOURCE,
    ;

    /** The wire string is the enum NAME: no prefix, no encoding scheme. */
    fun render(): String = name
}

/**
 * B4.6 / ADR-0016 · the inputs a plan is computed FROM.
 *
 * There is deliberately NO field for a plan. If a plan could travel here, the
 * host would be asserting its own validity, which is the exact failure this
 * ADR exists to prevent. The type cannot express it.
 *
 * [availableDecoderFormats] is what the HOST claims it can decode. The plan
 * checks the claim against the admitted format without ever selecting an
 * implementation: architectural law 6 forbids the core from depending on a
 * format-specific library, and the module graph enforces that arrow in one
 * direction only.
 */
data class PolicyCheckPlanRequest(
    val resourceBase64: String,
    val packedBundleBase64: String,
    val declaredFormat: String,
    val availableDecoderFormats: Set<ResourceFormat>,
)

/**
 * B4.6 / ADR-0016 · the kernel-computed admission decision.
 *
 * ## What this type does NOT claim
 *
 * It does **not** validate the resource payload. It cannot: it establishes
 * that the resource is Base64, that the declared format is known and covered,
 * and that the bundle passes admission. Whether those bytes actually parse as
 * the declared format is the DECODER's answer and arrives afterwards.
 *
 * That is why this is named "admission" and not "validation": a name implying
 * the stronger claim would be a name that lies about its body, the same defect
 * as the deleted `wouldDeny`.
 *
 * ## Purity
 *
 * Pure and stdlib-only (law 5, ADR-0011 D11.1): no clock, no filesystem, no
 * network, no randomness. `BundleVerifier.verifyPacked` satisfies all of those.
 */
sealed interface PolicyCheckPlan {

    /**
     * Inputs are admissible. [decodeFormat] is the format the host MUST decode
     * [resourceBytes] as; performing any other decode is not this plan.
     */
    data class PlanReady(
        val resourceBytes: ByteArray,
        val decodeFormat: ResourceFormat,
        val verifiedBundle: VerifiedBundle,
    ) : PolicyCheckPlan {
        // ByteArray in a data class needs explicit equality: without this,
        // two PlanReadys over the same inputs would NOT be equal, and plan
        // reproducibility would be untestable.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PlanReady) return false
            return decodeFormat == other.decodeFormat &&
                resourceBytes.contentEquals(other.resourceBytes) &&
                verifiedBundle.semanticDigest == other.verifiedBundle.semanticDigest
        }

        override fun hashCode(): Int {
            var result = decodeFormat.hashCode()
            result = 31 * result + resourceBytes.contentHashCode()
            result = 31 * result + verifiedBundle.semanticDigest.hashCode()
            return result
        }
    }

    /** No plan could be computed. An operational failure, in every mode. */
    data class PlanRefused(
        val refusal: PolicyCheckPlanRefusal,
        /**
         * The adapter renders ONLY [refusal] to the wire. This is retained so
         * the diagnostic is not lost, not because it is published.
         */
        val cause: Throwable? = null,
    ) : PolicyCheckPlan

    companion object {

        /**
         * Compute the plan. Total: every input maps to exactly one plan, with
         * no default and no thrown exception (law 9).
         *
         * Order matters only for which refusal is reported first; each check is
         * independent and no check is skipped.
         */
        // One return per refusal; the catch contains the UNEXPECTED.
        @Suppress("ReturnCount", "TooGenericExceptionCaught")
        fun compute(request: PolicyCheckPlanRequest): PolicyCheckPlan {
            val resourceBytes = decodeBase64(request.resourceBase64)
                ?: return PlanRefused(PolicyCheckPlanRefusal.RESOURCE_NOT_BASE64)

            val packedBundle = decodeBase64(request.packedBundleBase64)
                ?: return PlanRefused(PolicyCheckPlanRefusal.BUNDLE_NOT_BASE64)

            val format = runCatching { ResourceFormat.valueOf(request.declaredFormat.uppercase()) }
                .getOrNull()
                ?: return PlanRefused(PolicyCheckPlanRefusal.UNKNOWN_RESOURCE_FORMAT)

            // The kernel may know the decoder CONTRACT but never SELECT an
            // implementation (law 6). Checking the host's declared coverage is
            // the strongest claim available without that dependency.
            if (format !in request.availableDecoderFormats) {
                return PlanRefused(PolicyCheckPlanRefusal.NO_DECODER_FOR_FORMAT)
            }

            val verified = try {
                BundleVerifier.verifyPacked(packedBundle)
            } catch (e: IllegalArgumentException) {
                // Covers every TYPED admission failure: `BundleRefusal`
                // extends IllegalArgumentException, as do the budget and
                // structural `require` checks inside verifyPacked. One branch,
                // not a dead `catch (BundleRefusal)` that can never be
                // reached because its subclass is caught below it.
                return PlanRefused(PolicyCheckPlanRefusal.BUNDLE_REFUSED, e)
            } catch (e: RuntimeException) {
                // A generic engine crash must never escape as an unhandled
                // exception that reads as "no verdict reached". Detekt wants a
                // narrower type, but the whole point of this branch is to
                // contain the UNEXPECTED, which by definition has no narrow
                // type. Removing it would reintroduce the B0.2 leak.
                return PlanRefused(PolicyCheckPlanRefusal.BUNDLE_REFUSED, e)
            }

            return PlanReady(
                resourceBytes = resourceBytes,
                decodeFormat = format,
                verifiedBundle = verified,
            )
        }

        /**
         * Base64 decode that reports invalid input as `null` rather than
         * throwing. The JDK's `IllegalArgumentException` carries no diagnostic
         * beyond "last unit does not have enough valid bits", so keeping the
         * cause would retain nothing worth reading; the typed refusal is the
         * information the caller acts on.
         */
        @Suppress("SwallowedException")
        private fun decodeBase64(value: String): ByteArray? = try {
            java.util.Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
