package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.decoder.ResourceFormat

/**
 * B4.7 · closed refusal vocabulary for the ingress budget.
 *
 * Rendered to the wire as the enum NAME, exactly like
 * [PolicyCheckPlanRefusal], so promoting any of these to a wire-visible code
 * in B6 is mechanical rather than a second vocabulary to maintain (law 12).
 *
 * Law 8: the budget failure and the encoding failure are DIFFERENT facts and
 * never collapse into one. An operator debugging a rejected upload needs to
 * know whether the payload was malformed or merely too large.
 */
enum class ResourceIngressRefusal {
    /** The resource string was longer than the encoded-length budget. */
    RESOURCE_ENCODED_TOO_LARGE,

    /** The resource decoded to more bytes than the decoded budget allows. */
    RESOURCE_DECODED_TOO_LARGE,

    /** The resource string is not valid Base64. */
    RESOURCE_NOT_BASE64,

    /** The bundle string was longer than the encoded-length budget. */
    BUNDLE_ENCODED_TOO_LARGE,

    /** The bundle decoded to more bytes than the decoded budget allows. */
    BUNDLE_DECODED_TOO_LARGE,

    /** The bundle string is not valid Base64. */
    BUNDLE_NOT_BASE64,

    ;

    fun render(): String = name
}

/**
 * B4.7 · the three refusals one payload kind can produce.
 *
 * Bundling them keeps [ResourceIngressLimits.admit] total without a five
 * positional-parameter call, and makes the resource/bundle symmetry explicit:
 * the code path is shared, only the vocabulary differs.
 */
private data class ResourceIngressRefusals(
    val encodedTooLarge: ResourceIngressRefusal,
    val decodedTooLarge: ResourceIngressRefusal,
    val notBase64: ResourceIngressRefusal,
)

/**
 * B4.7 · result of applying [ResourceIngressLimits] to one payload.
 *
 * [Refused] deliberately has NO bytes field. That absence is the guarantee
 * that a caller cannot obtain the decoded payload of something the budget
 * rejected; if the bytes were merely discarded, a later refactor could easily
 * reintroduce the leak.
 */
sealed interface ResourceIngressAdmission {

    /** Payload admitted within budget. These are its actual decoded bytes. */
    data class Admitted(val bytes: ByteArray) : ResourceIngressAdmission {

        /**
         * The ingress contract carries bytes, not a declared format. JSON is
         * reported as the admissible default so a caller cannot smuggle a
         * format claim through the budget check; the real format decision
         * belongs to [PolicyCheckPlan], which has the declared format.
         */
        fun assumedFormat(): ResourceFormat = ResourceFormat.JSON

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Admitted) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Payload rejected. No bytes exist to expose. */
    data class Refused(val refusal: ResourceIngressRefusal) : ResourceIngressAdmission
}

/**
 * B4.7 · typed ingress budget for untrusted resource and bundle payloads.
 *
 * ## Why the guard is over `String.length`
 *
 * A budget checked after `Base64.decode` has already allocated the payload and
 * therefore has not prevented the allocation it claims to prevent. Base64
 * expands 3 bytes into 4 characters, so the ENCODED length is a sufficient
 * upper bound on the decoded length and can be compared in O(1) against
 * [maxBase64Chars] before any decoding occurs. The decoded check remains as a
 * second, exact bound, but it is a backstop rather than the guard.
 *
 * ## Purity
 *
 * Pure and stdlib-only (law 5, ADR-0011 D11.1): no clock, no filesystem, no
 * network, no randomness, and no global mutable registry (law 10) — the
 * budgets are instance state supplied by the caller.
 *
 * ## The ceiling
 *
 * [HARD_CEILING_BYTES] bounds what a caller may configure. A host that raised
 * its own budget above the ceiling would turn a product decision into a
 * deployment accident, so an over-large request is CLAMPED rather than
 * honoured: the caller asked for less safety than the ceiling allows and gets
 * the ceiling, not what it asked for.
 *
 * Deliberately NOT a data class: the authoritative [maxResourceBytes] is
 * DERIVED from the request, so generated `equals`/`hashCode`/`toString` over
 * constructor properties would either expose the unclamped request or make
 * two differently-clamped instances compare equal. [equals] is written by hand
 * over the effective budgets, which is what callers actually mean.
 */
class ResourceIngressLimits(
    requestedResourceBytes: Int = DEFAULT_MAX_RESOURCE_BYTES,
    requestedBundleBytes: Int = DEFAULT_MAX_BUNDLE_BYTES,
) {

    /**
     * The resource budget, clamped to [HARD_CEILING_BYTES].
     *
     * A caller cannot raise its own safety ceiling. The property is the
     * authoritative value and [requestedResourceBytes] is retained only as a
     * diagnostic, so a host that asks for 1 GiB gets 256 MiB rather than
     * either an out-of-memory crash or a silently lifted guard.
     */
    val maxResourceBytes: Int = requestedResourceBytes.coerceAtMost(HARD_CEILING_BYTES)

    /** The bundle budget, clamped to [HARD_CEILING_BYTES]. See [maxResourceBytes]. */
    val maxBundleBytes: Int = requestedBundleBytes.coerceAtMost(HARD_CEILING_BYTES)

    /** What the host asked for before the clamp. Diagnostic only. */
    val requestedResourceBudget: Int = requestedResourceBytes

    /** What the host asked for before the clamp. Diagnostic only. */
    val requestedBundleBudget: Int = requestedBundleBytes

    /** Encoded-length threshold for the resource, derived, never stored. */
    val maxResourceBase64Chars: Int = encodedLengthFor(maxResourceBytes)

    /** Encoded-length threshold for the bundle, derived, never stored. */
    val maxBundleBase64Chars: Int = encodedLengthFor(maxBundleBytes)

    /**
     * Apply the budget to a Base64-encoded resource string.
     *
     * Total (law 9): every input maps to exactly one admission, with no
     * thrown exception. An oversized payload is an operational refusal, not an
     * exception, so a host cannot leak an OutOfMemoryError past the adapter.
     */
    fun admitResource(encoded: String): ResourceIngressAdmission = admit(
        encoded = encoded,
        maxChars = maxResourceBase64Chars,
        maxBytes = maxResourceBytes,
        refusals = ResourceIngressRefusals(
            encodedTooLarge = ResourceIngressRefusal.RESOURCE_ENCODED_TOO_LARGE,
            decodedTooLarge = ResourceIngressRefusal.RESOURCE_DECODED_TOO_LARGE,
            notBase64 = ResourceIngressRefusal.RESOURCE_NOT_BASE64,
        ),
    )

    /** Apply the budget to a Base64-encoded packed bundle string. */
    fun admitBundle(encoded: String): ResourceIngressAdmission = admit(
        encoded = encoded,
        maxChars = maxBundleBase64Chars,
        maxBytes = maxBundleBytes,
        refusals = ResourceIngressRefusals(
            encodedTooLarge = ResourceIngressRefusal.BUNDLE_ENCODED_TOO_LARGE,
            decodedTooLarge = ResourceIngressRefusal.BUNDLE_DECODED_TOO_LARGE,
            notBase64 = ResourceIngressRefusal.BUNDLE_NOT_BASE64,
        ),
    )

    // One return per refusal; the catch contains only the EXPECTED decode error.
    @Suppress("ReturnCount", "SwallowedException")
    private fun admit(
        encoded: String,
        maxChars: Int,
        maxBytes: Int,
        refusals: ResourceIngressRefusals,
    ): ResourceIngressAdmission {
        // The O(1) guard. Runs first and performs no allocation, so a payload
        // of any size is refused in constant time.
        if (encoded.length > maxChars) {
            return ResourceIngressAdmission.Refused(refusals.encodedTooLarge)
        }

        val bytes = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            // The JDK message is "last unit does not have enough valid bits",
            // which carries nothing actionable beyond the typed refusal.
            return ResourceIngressAdmission.Refused(refusals.notBase64)
        }

        if (bytes.size > maxBytes) {
            return ResourceIngressAdmission.Refused(refusals.decodedTooLarge)
        }

        return ResourceIngressAdmission.Admitted(bytes)
    }

    /**
     * Equality over the EFFECTIVE budgets, not the requested ones.
     *
     * Two limits that asked for different amounts but were clamped to the same
     * ceiling enforce exactly the same policy, so they are equal here. A host
     * that compares its configured limit against a default would otherwise see
     * a difference where there is none.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResourceIngressLimits) return false
        return maxResourceBytes == other.maxResourceBytes &&
            maxBundleBytes == other.maxBundleBytes
    }

    override fun hashCode(): Int = HASH_MULTIPLIER * maxResourceBytes + maxBundleBytes

    override fun toString(): String =
        "ResourceIngressLimits(resourceBytes=$maxResourceBytes, bundleBytes=$maxBundleBytes)"

    companion object {

        /** 64 MiB: the default resource budget. */
        const val DEFAULT_MAX_RESOURCE_BYTES: Int = 64 * 1024 * 1024

        /** 64 MiB: the default bundle budget, matching the resource default. */
        const val DEFAULT_MAX_BUNDLE_BYTES: Int = 64 * 1024 * 1024

        /** 256 MiB: the ceiling no configuration may exceed. */
        const val HARD_CEILING_BYTES: Int = 256 * 1024 * 1024

        /** Prime multiplier for [hashCode]; 31 is the Kotlin data-class convention. */
        private const val HASH_MULTIPLIER: Int = 31

        /** The default budget, used when a host supplies none. */
        val DEFAULT: ResourceIngressLimits = ResourceIngressLimits()

        /** Base64 encodes this many raw bytes into a quantum of characters. */
        private const val BYTES_PER_BASE64_QUANTUM: Int = 3

        /** ...and one quantum occupies this many characters. */
        private const val CHARS_PER_BASE64_QUANTUM: Int = 4

        /**
         * Base64 encodes 3 bytes as 4 characters, so the encoded length of an
         * n-byte payload is ceil(n / 3) * 4. Deriving the threshold is what
         * makes the guard O(1) over [String.length].
         *
         * The ceiling division uses [BYTES_PER_BASE64_QUANTUM] - 1 as the
         * rounding offset, which is the standard integer idiom for ceil.
         */
        private fun encodedLengthFor(bytes: Int): Int =
            ((bytes + BYTES_PER_BASE64_QUANTUM - 1) / BYTES_PER_BASE64_QUANTUM) *
                CHARS_PER_BASE64_QUANTUM
    }
}
