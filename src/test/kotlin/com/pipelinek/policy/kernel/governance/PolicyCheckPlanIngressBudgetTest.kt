package com.pipelinek.policy.kernel.governance

import com.pipelinek.policy.decoder.ResourceFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.util.Base64

/**
 * B4.7 · The ingress budget must be WIRED, not merely present.
 *
 * `ResourceIngressLimits` was implemented with a default budget, a hard
 * ceiling, an O(1) encoded-length guard before Base64 decoding and a closed
 * refusal vocabulary — and then nothing called it.
 *
 * `PolicyCheckPlan.compute` decoded `resourceBase64` and `packedBundleBase64`
 * with a bare `Base64.getDecoder().decode(...)`, materialising the full byte
 * array before any size check. An oversized payload was therefore allocated in
 * full and only then rejected, which is the opposite of what an ingress
 * budget is for.
 *
 * These tests pin the wiring. They fail against the unwired path.
 */
class PolicyCheckPlanIngressBudgetTest {

    /**
     * Validly-ENCODED but not a real pack. `BUNDLE_REFUSED` fires later in the
     * pipeline, which is what these tests want: they assert on the SIZE
     * refusal, so the bundle must get past the budget guard first without
     * being rejected for an unrelated reason beforehand. If a fixture were a
     * real pack the tests would be validating the pack, not the budget.
     */
    private val minimalBundle: String =
        Base64.getEncoder().encodeToString(ByteArray(4))

    /**
     * A TIGHT budget rather than the 64 MiB default. Using the default would
     * make the fixtures below (8 MiB) legitimate inputs, and the test would
     * then assert nothing about oversize at all. Pinning the budget keeps the
     * test honest about which side of the guard each case sits on.
     */
    private val tightLimits = ResourceIngressLimits(
        requestedResourceBytes = 64 * 1024,
        requestedBundleBytes = 64 * 1024,
    )

    private fun request(
        resourceBase64: String,
        bundleBase64: String = minimalBundle,
        format: String = "JSON",
    ) = PolicyCheckPlanRequest(
        resourceBase64 = resourceBase64,
        packedBundleBase64 = bundleBase64,
        declaredFormat = format,
        availableDecoderFormats = setOf(ResourceFormat.JSON, ResourceFormat.CSV),
        ingressLimits = tightLimits,
    )

    @Test
    fun `01a an oversized resource refuses BEFORE the full decode`() {
        // Far beyond any sane budget, but still valid Base64 so the refusal
        // must come from the SIZE guard and not from the decoder.
        val oversized = Base64.getEncoder().encodeToString(ByteArray(8 * 1024 * 1024) { 0x41 })
        val plan = PolicyCheckPlan.compute(request(oversized))

        val refused = assertIs<PolicyCheckPlan.PlanRefused>(plan)
        assertEquals(
            PolicyCheckPlanRefusal.RESOURCE_TOO_LARGE,
            refused.refusal,
            "an oversized resource must refuse as a budget refusal, got ${refused.refusal}",
        )
    }

    @Test
    fun `01b an oversized bundle refuses as a bundle budget refusal`() {
        val smallResource = Base64.getEncoder().encodeToString("{}".toByteArray())
        val oversized = Base64.getEncoder().encodeToString(ByteArray(8 * 1024 * 1024) { 0x41 })
        val plan = PolicyCheckPlan.compute(request(smallResource, oversized))

        val refused = assertIs<PolicyCheckPlan.PlanRefused>(plan)
        assertEquals(PolicyCheckPlanRefusal.BUNDLE_TOO_LARGE, refused.refusal)
    }

    @Test
    fun `01c the refusal vocabulary distinguishes too-large from not-base64`() {
        // Law 8: a size refusal and a malformed-encoding refusal are DIFFERENT
        // facts. Collapsing them into one "bad resource" would lose the
        // operator's ability to tell an attack from a typo.
        val notBase64 = "!!!not base64!!!"
        val plan = PolicyCheckPlan.compute(request(notBase64))
        val refused = assertIs<PolicyCheckPlan.PlanRefused>(plan)
        assertEquals(PolicyCheckPlanRefusal.RESOURCE_NOT_BASE64, refused.refusal)

        val oversized = Base64.getEncoder().encodeToString(ByteArray(8 * 1024 * 1024) { 0x41 })
        val big = assertIs<PolicyCheckPlan.PlanRefused>(PolicyCheckPlan.compute(request(oversized)))
        assertEquals(PolicyCheckPlanRefusal.RESOURCE_TOO_LARGE, big.refusal)
        assertTrue(big.refusal != refused.refusal)
    }

    @Test
    fun `01d a resource within budget is NOT refused for size`() {
        // The guard must not fire on ordinary input. Without this, a fix that
        // simply refuses everything would pass the tests above.
        //
        // The assertion is deliberately narrow: it does NOT require the plan
        // to succeed. This fixture's bundle is not a real pack, so the run
        // ends in BUNDLE_REFUSED regardless. What matters is that a small
        // resource never produces a SIZE refusal — that is the property
        // under test, and demanding a full valid plan here would be testing
        // the packer instead of the budget.
        val smallResource = Base64.getEncoder().encodeToString("""{"a":1}""".toByteArray())
        val plan = PolicyCheckPlan.compute(request(smallResource))
        val refused = assertIs<PolicyCheckPlan.PlanRefused>(plan)
        assertTrue(
            refused.refusal != PolicyCheckPlanRefusal.RESOURCE_TOO_LARGE,
            "a 7-byte resource must not hit the resource size guard, got ${refused.refusal}",
        )
    }

    @Test
    fun `01e the size refusal is decided on ENCODED length, not after decoding`() {
        // The O(1) guard exists to avoid materialising an oversized payload.
        // Prove the encoded length ALONE decides, and that it decides for the
        // SIZE reason specifically.
        //
        // 2 MiB of encoded characters is ~1.5 MiB decoded: far past any budget
        // this plan should accept, and the decoded content is trivially valid
        // JSON, which is exactly the adversarial shape — cheap to send,
        // expensive to decode.
        val paddingHeavy = "QUFB" + "QQ==".repeat(2 * 1024 * 1024)
        val plan = PolicyCheckPlan.compute(request(paddingHeavy))
        val refused = assertIs<PolicyCheckPlan.PlanRefused>(plan)
        assertEquals(
            PolicyCheckPlanRefusal.RESOURCE_TOO_LARGE,
            refused.refusal,
            "the encoded length alone must decide, got ${refused.refusal}",
        )
    }
}
