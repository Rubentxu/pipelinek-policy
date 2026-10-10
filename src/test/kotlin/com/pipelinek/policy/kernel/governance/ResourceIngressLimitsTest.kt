package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.decoder.ResourceFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B4.7 · typed ingress budget for untrusted resource and bundle payloads.
 *
 * The property under test is NOT "oversized input is rejected". It is that the
 * rejection happens BEFORE the allocation, because a guard that runs after
 * `Base64.decode` has already materialised the payload has not prevented the
 * memory exhaustion it claims to prevent. That is why the first two cases
 * measure the guard over `String.length` only, and the later cases pin the
 * accounting so an implementation cannot quietly decode first and check
 * afterwards.
 *
 * Law 8 applies: Missing, Null and TypeMismatch stay distinct. A resource
 * string that is absent, one that is not Base64, and one that is oversized are
 * three different operational facts and produce three different refusals.
 */
class ResourceIngressLimitsTest {

    private val megabyte = 1024 * 1024

    private val defaults = ResourceIngressLimits.DEFAULT

    // --- O(1) guard on the encoded length, before any decode ---

    @Test
    fun `01a default limit is 64 MiB and the hard ceiling is 256 MiB`() {
        assertEquals(64 * megabyte, defaults.maxResourceBytes)
        assertEquals(256 * megabyte, ResourceIngressLimits.HARD_CEILING_BYTES)
        // The default must be reachable and the ceiling must bound the default,
        // otherwise "hard ceiling" would be a name without an effect.
        assertTrue(
            defaults.maxResourceBytes < ResourceIngressLimits.HARD_CEILING_BYTES,
            "the default must sit strictly below the hard ceiling",
        )
    }

    @Test
    fun `01b maxResourceBase64Chars is derived from maxResourceBytes without decoding`() {
        // Base64 expands 3 bytes into 4 chars, so the encoded length is
        // ceil(bytes / 3) * 4. Deriving the threshold is what makes the guard
        // O(1) over String.length instead of O(n) over decoded bytes.
        val expectedChars = ((defaults.maxResourceBytes + 2) / 3) * 4
        assertEquals(expectedChars, defaults.maxResourceBase64Chars)
        // A payload one character over the threshold must be refused by length
        // alone, with no decode performed.
        val oversized = "A".repeat(defaults.maxResourceBase64Chars + 1)
        val verdict = defaults.admitResource(oversized)
        assertIs<ResourceIngressAdmission.Refused>(verdict)
        assertEquals(ResourceIngressRefusal.RESOURCE_ENCODED_TOO_LARGE, verdict.refusal)
    }

    @Test
    fun `01c a payload at the encoded threshold decodes and hits the decoded backstop`() {
        // The encoded threshold ceil(n/3)*4 is LOOSE: a payload of exactly
        // that many characters can decode to up to 2 bytes more than the
        // budget, because ceil(n/3) rounds up. The decoded backstop therefore
        // is reachable, and this is the only input shape that reaches it.
        // Verified: maxChars=89478488 decodes to 67108866 bytes against a
        // 67108864-byte budget, i.e. 2 bytes over.
        val atThreshold = "A".repeat(defaults.maxResourceBase64Chars)
        val verdict = defaults.admitResource(atThreshold)
        assertIs<ResourceIngressAdmission.Refused>(verdict)
        assertEquals(ResourceIngressRefusal.RESOURCE_DECODED_TOO_LARGE, verdict.refusal)
    }

    // --- the decoded payload is measured against the same typed budget ---

    @Test
    fun `02a an oversized decoded payload is refused, never truncated`() {
        // Budget 4 bytes gives maxChars=8, whose maximum decodable length is
        // 6 bytes: two bytes over the budget. This exercises the decoded
        // backstop through the length guard, which is the only route to it.
        val limits = ResourceIngressLimits(
            requestedResourceBytes = 4,
            requestedBundleBytes = 4,
        )
        // 6 bytes encode to exactly 8 chars, passing the length guard.
        val payload = "abcdef"
        val encoded = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
        assertEquals(8, encoded.length)
        val verdict = limits.admitResource(encoded)
        assertIs<ResourceIngressAdmission.Refused>(verdict)
        assertEquals(ResourceIngressRefusal.RESOURCE_DECODED_TOO_LARGE, verdict.refusal)
    }

    @Test
    fun `02b an admissible payload returns its bytes untouched`() {
        val limits = ResourceIngressLimits(requestedResourceBytes = 64, requestedBundleBytes = 64)
        val payload = """{"a":1}"""
        val verdict = limits.admitResource(java.util.Base64.getEncoder().encodeToString(payload.toByteArray()))
        assertIs<ResourceIngressAdmission.Admitted>(verdict)
        assertEquals(payload, String(verdict.bytes))
        assertEquals(ResourceFormat.JSON, verdict.assumedFormat())
    }

    @Test
    fun `02c non Base64 is a distinct refusal from oversized`() {
        val limits = ResourceIngressLimits(
            requestedResourceBytes = megabyte,
            requestedBundleBytes = megabyte,
        )
        val verdict = limits.admitResource("not base64 !!!")
        assertIs<ResourceIngressAdmission.Refused>(verdict)
        // Law 8: TypeMismatch stays distinct from a budget failure.
        assertEquals(ResourceIngressRefusal.RESOURCE_NOT_BASE64, verdict.refusal)
    }

    // --- a refused payload never materialises bytes ---

    @Test
    fun `03a refusal exposes no bytes so a caller cannot bypass the budget`() {
        val limits = ResourceIngressLimits(requestedResourceBytes = 4, requestedBundleBytes = 4)
        val verdict = limits.admitResource("QUFBQUFBQUFBQUFBQUFB")
        assertIs<ResourceIngressAdmission.Refused>(verdict)
        // The type itself is the guarantee: Refused has no bytes field at all,
        // so there is no decoded payload to leak past the budget.
        assertEquals(ResourceIngressRefusal.RESOURCE_ENCODED_TOO_LARGE, verdict.refusal)
    }

    @Test
    fun `03b bundle budget is enforced independently of the resource budget`() {
        val limits = ResourceIngressLimits(requestedResourceBytes = 64, requestedBundleBytes = 4)
        val small = java.util.Base64.getEncoder().encodeToString("ab".toByteArray())
        val big = java.util.Base64.getEncoder().encodeToString(ByteArray(64))
        // Resource fits, bundle does not: the two budgets must not collapse.
        assertIs<ResourceIngressAdmission.Admitted>(limits.admitResource(small))
        val bundleVerdict = limits.admitBundle(big)
        assertIs<ResourceIngressAdmission.Refused>(bundleVerdict)
        assertEquals(ResourceIngressRefusal.BUNDLE_ENCODED_TOO_LARGE, bundleVerdict.refusal)
    }

    @Test
    fun `03c a limit above the hard ceiling is clamped, never honoured`() {
        val absurd = ResourceIngressLimits(
            requestedResourceBytes = ResourceIngressLimits.HARD_CEILING_BYTES * 4,
            requestedBundleBytes = ResourceIngressLimits.HARD_CEILING_BYTES * 4,
        )
        // A caller-supplied budget must not be able to raise the ceiling. If it
        // could, the ceiling would be advisory and the P0 would return.
        assertEquals(ResourceIngressLimits.HARD_CEILING_BYTES, absurd.maxResourceBytes)
        assertEquals(ResourceIngressLimits.HARD_CEILING_BYTES, absurd.maxBundleBytes)
    }
}
