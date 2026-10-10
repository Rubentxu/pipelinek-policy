package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.durable.Fingerprint
import dev.rubentxu.pipeline.v2.domain.durable.OperationInput
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B6.8 · The plugin declares `ReplayPolicy.MEMOIZED`, and M10 declared
 * restart/replay as N/A.
 *
 * That declaration is only safe if the memo key actually distinguishes the
 * inputs the verdict depends on. `MEMOIZED` means the host may serve a
 * journalled outcome instead of re-running the handler, so anything NOT in the
 * fingerprint is a channel through which a changed input keeps a stale verdict.
 *
 * The concern is concrete rather than theoretical: `PolicyCheckInput` carries
 * the resource bytes, the bundle bytes, the format and the enforcement mode.
 * A fingerprint computed over a run identifier alone would memoize across
 * different files entirely, and the policy check would report PASS for a file
 * it never read.
 *
 * These tests exercise the SDK's own `Fingerprint.compute` over inputs shaped
 * like the ones the plugin is given. They are measurements, not assumptions:
 * each one asserts a fingerprint value the SDK computed from real bytes.
 */
class ReplayFingerprintTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** The step's input as the host would present it to `Fingerprint.compute`. */
    private fun inputFor(
        resourceBase64: String,
        bundleBase64: String = "YnVuZGxl",
        format: String = "json",
        enforcement: String = "ENFORCED",
        attempt: Int = 1,
    ): OperationInput {
        val params = buildJsonObject {
            put("resourceBase64", JsonPrimitive(resourceBase64))
            put("resourceFormat", JsonPrimitive(format))
            put("packedBundleBase64", JsonPrimitive(bundleBase64))
            put("enforcement", JsonPrimitive(enforcement))
        }
        return OperationInput(
            stepId = PolicyCheckStepDefinition.KEY.value,
            params = params,
            runId = "run-1",
            attempt = attempt,
        )
    }

    private fun fingerprintOf(input: OperationInput, attempt: Int = input.attempt) =
        Fingerprint.compute(input, PolicyCheckStepDefinition.KEY.value, ReplayPolicy.MEMOIZED, attempt)

    /**
     * The load-bearing property. If the resource bytes are not in the
     * fingerprint, `MEMOIZED` will serve the verdict computed for one file in
     * response to a different file, and no amount of correct evaluation
     * downstream can catch it.
     */
    @Test
    fun `a different resource yields a different fingerprint`() {
        val compliant = inputFor(btoa("""{"name":"ok"}"""))
        val violating = inputFor(btoa("""{"name":"other"}"""))

        assertNotEquals(
            fingerprintOf(compliant).hex,
            fingerprintOf(violating).hex,
            "MEMOIZED is only safe if the resource bytes move the fingerprint",
        )
    }

    /** Same property for the policy bundle: a new bundle must not reuse a verdict. */
    @Test
    fun `a different bundle yields a different fingerprint`() {
        val old = inputFor(btoa("x"), bundleBase64 = "YnVuZGxlLXYx")
        val new = inputFor(btoa("x"), bundleBase64 = "YnVuZGxlLXYy")

        assertNotEquals(
            fingerprintOf(old).hex,
            fingerprintOf(new).hex,
            "a different bundle must not reuse the memoized verdict of another bundle",
        )
    }

    /**
     * Enforcement mode changes what a violation MEANS, so it must not be
     * memoized across. A policy reported under `ENFORCED` cannot answer a
     * question asked under `ADVISORY`.
     */
    @Test
    fun `a different enforcement mode yields a different fingerprint`() {
        val enforced = inputFor(btoa("x"), enforcement = "ENFORCED")
        val advisory = inputFor(btoa("x"), enforcement = "ADVISORY")

        assertNotEquals(
            fingerprintOf(enforced).hex,
            fingerprintOf(advisory).hex,
            "enforcement mode changes the verdict's meaning and must move the fingerprint",
        )
    }

    /**
     * The format decoder is chosen by `resourceFormat`. Two payloads with the
     * same bytes but different formats are parsed by different decoders, so
     * they must not share a memo entry.
     */
    @Test
    fun `a different resource format yields a different fingerprint`() {
        val asJson = inputFor(btoa("a"), format = "json")
        val asCsv = inputFor(btoa("a"), format = "csv")

        assertNotEquals(
            fingerprintOf(asJson).hex,
            fingerprintOf(asCsv).hex,
            "the decoder is chosen by format, so a format change must move the fingerprint",
        )
    }

    /**
     * Determinism, which is what makes memoization possible at all: the same
     * input must always produce the same key. Without this, every run would
     * look like a miss and the policy would re-execute every time, which is a
     * correctness bug wearing a performance costume.
     */
    @Test
    fun `the same input yields the same fingerprint`() {
        val input = inputFor(btoa("""{"name":"ok"}"""))

        assertEquals(
            fingerprintOf(input).hex,
            fingerprintOf(input).hex,
            "a deterministic key is what makes MEMOIZED a cache rather than a rerun",
        )
    }

    /**
     * The attempt counter is part of the SDK's payload. This pins that it is
     * observed here too, so a retry is distinguishable from the original
     * attempt rather than colliding with it.
     */
    @Test
    fun `a different attempt yields a different fingerprint`() {
        val first = inputFor(btoa("x"), attempt = 1)
        val second = inputFor(btoa("x"), attempt = 2)

        assertNotEquals(
            fingerprintOf(first).hex,
            fingerprintOf(second).hex,
            "a retry must not collide with the attempt it retries",
        )
    }

    /**
     * The plugin must actually declare MEMOIZED, or none of the above matters:
     * the properties describe the fingerprint, and the declaration is what
     * obliges the host to consult it.
     */
    @Test
    fun `the plugin declares the replay policy it relies on`() {
        assertEquals(
            ReplayPolicy.MEMOIZED,
            PolicyCheckStepDefinition.contract.descriptor.replayPolicy,
            "the fingerprint properties above only matter if the step declares MEMOIZED",
        )
    }

    /**
     * The fingerprint is a SHA-256 hex digest. A shape assertion is cheap and
     * catches a silently substituted algorithm before it can be relied upon.
     */
    @Test
    fun `the fingerprint is a sha-256 hex digest`() {
        val hex = fingerprintOf(inputFor(btoa("x"))).hex

        assertEquals(64, hex.length, "expected a 64-char SHA-256 hex digest")
        assertTrue(
            hex.all { it in '0'..'9' || it in 'a'..'f' },
            "expected lowercase hex, got $hex",
        )
    }

    private fun btoa(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray())
}
