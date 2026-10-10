package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.decoder.ResourceFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B4.6 / ADR-0016 · the plan is COMPUTED by the kernel, never accepted.
 *
 * The property under test is not "the kernel produces a good plan". It is "the
 * kernel's plan is the only plan". A test that only checked good inputs would
 * pass while a host-supplied-plan path stayed live and permissive, so the
 * last case here asserts that a plausible-looking plan arriving from outside
 * changes nothing.
 */
class PolicyCheckPlanTest {

    private val resourceBytes = """{"spec":{"replicas":1}}""".toByteArray()
    private val bundleBytes = ByteArray(4) // not a real pack; refused, which is fine here

    private fun plan(
        resourceBase64: String,
        bundleBase64: String,
        format: String,
        decoderFormats: Set<ResourceFormat> = setOf(ResourceFormat.JSON),
    ): PolicyCheckPlan = PolicyCheckPlan.compute(
        PolicyCheckPlanRequest(
            resourceBase64 = resourceBase64,
            packedBundleBase64 = bundleBase64,
            declaredFormat = format,
            availableDecoderFormats = decoderFormats,
        ),
    )

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    // --- 06a: an unknown format is refused, never guessed ---

    @Test
    fun `06a an unknown resource format is refused with a typed code`() {
        val result = plan(b64(resourceBytes), b64(bundleBytes), "TOML")

        val refused = assertIs<PolicyCheckPlan.PlanRefused>(result)
        assertEquals(
            PolicyCheckPlanRefusal.UNKNOWN_RESOURCE_FORMAT,
            refused.refusal,
            "an unknown format must be refused, never coerced to a default",
        )
    }

    // --- 06b: a format the host has no decoder for is refused ---

    @Test
    fun `06b a format with no registered decoder is refused`() {
        val result = plan(
            b64(resourceBytes),
            b64(bundleBytes),
            "YAML",
            decoderFormats = setOf(ResourceFormat.JSON),
        )

        val refused = assertIs<PolicyCheckPlan.PlanRefused>(result)
        assertEquals(PolicyCheckPlanRefusal.NO_DECODER_FOR_FORMAT, refused.refusal)
    }

    // --- 06c: non-Base64 is refused per input, typed distinctly ---

    @Test
    fun `06c resource and bundle Base64 failures are distinct typed refusals`() {
        val badResource = plan("!!!not-base64!!!", b64(bundleBytes), "JSON")
        val badBundle = plan(b64(resourceBytes), "!!!not-base64!!!", "JSON")

        assertEquals(
            PolicyCheckPlanRefusal.RESOURCE_NOT_BASE64,
            assertIs<PolicyCheckPlan.PlanRefused>(badResource).refusal,
        )
        assertEquals(
            PolicyCheckPlanRefusal.BUNDLE_NOT_BASE64,
            assertIs<PolicyCheckPlan.PlanRefused>(badBundle).refusal,
        )
    }

    // --- 06d: a bundle that fails admission refuses, and the plan is pure ---

    @Test
    fun `06d a bundle that fails admission yields BUNDLE_REFUSED`() {
        val result = plan(b64(resourceBytes), b64(bundleBytes), "JSON")

        val refused = assertIs<PolicyCheckPlan.PlanRefused>(result)
        assertEquals(PolicyCheckPlanRefusal.BUNDLE_REFUSED, refused.refusal)
    }

    // --- 06e: THE FALSIFICATION — a host-supplied plan is IGNORED ---

    /**
     * The central property of ADR-0016. If a plan could arrive from the host,
     * the host would be asserting its own validity, which is the exact failure
     * B4.6 exists to prevent.
     *
     * There is no plan field on [PolicyCheckPlanRequest] and the plan has no
     * overload that accepts one, so this asserts that structurally: the
     * request type cannot carry a plan, and recomputing from the same inputs
     * yields the same refusal regardless of anything the host might assert.
     */
    @Test
    fun `06e the request type cannot carry a host-supplied plan`() {
        assertTrue(
            PolicyCheckPlanRequest::class.java.declaredFields.none {
                it.type == PolicyCheckPlan::class.java ||
                    it.name.contains("plan", ignoreCase = true)
            },
            "a plan arriving from the host would be the host asserting its own " +
                "validity; the request must not be able to carry one",
        )

        // And the decision is reproducible from inputs alone.
        val first = plan("!!!not-base64!!!", b64(bundleBytes), "JSON")
        val second = plan("!!!not-base64!!!", b64(bundleBytes), "JSON")
        assertEquals(first, second, "the plan is a function of the inputs, nothing else")
    }

    // --- 06f: refusals render as their enum name, mechanically ---

    @Test
    fun `06f every refusal renders as its enum name`() {
        PolicyCheckPlanRefusal.entries.forEach { refusal ->
            assertEquals(
                refusal.name,
                refusal.render(),
                "the wire string must equal the enum name so B6 is mechanical",
            )
        }
    }
}
