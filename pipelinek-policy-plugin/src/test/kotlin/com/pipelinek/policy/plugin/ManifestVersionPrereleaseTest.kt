package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.step.SemVer
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SDK's `SemVer` carries major/minor/patch and NOTHING else — verified
 * against pipeline-domain 0.47.0, where the class exposes exactly three int
 * fields and a three-int constructor.
 *
 * That makes a prerelease tag unrepresentable in the plugin manifest. Building
 * `v0.3.0-alpha.1` publishes `"releaseVersion": "0.3.0"` in
 * `META-INF/pipelinek/plugin-manifest.json`, so an alpha artifact is admitted
 * by the runtime as the final release. The exact tag still survives in the
 * release properties, the jar manifest and the step descriptor.
 *
 * This test pins that behaviour in BOTH directions. Without the second
 * assertion, a future SDK that gains prerelease support would start carrying
 * the tag and nobody would notice the manifest's meaning had changed.
 */
class ManifestVersionPrereleaseTest {

    /** The version the build measured, prerelease tag intact. */
    private fun measuredReleaseVersion(): String {
        val resource = PolicyPluginDeclaration.RELEASE_PROPERTIES_RESOURCE
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "no $resource on the test classpath"
        }
        val props = Properties()
        stream.use { props.load(it) }
        return requireNotNull(props.getProperty("pipelinek.policy.release.version"))
    }

    /**
     * The declaration really does carry the tag in the resource. If the build
     * ever stopped emitting it, the next assertion would pass vacuously.
     */
    @Test
    fun `the measured provenance keeps its prerelease tag`() {
        assertTrue(
            measuredReleaseVersion().contains("-"),
            "expected a prerelease build; got '${measuredReleaseVersion()}'. " +
                "Without a tag there is nothing to assert about tag loss.",
        )
    }

    /**
     * The SDK type has no prerelease field. Asserted structurally, so if a
     * future SDK adds one this test fails and asks a human to decide whether an
     * alpha artifact should now be distinguishable from a final one.
     */
    @Test
    fun `the SDK SemVer type has no prerelease component`() {
        val fields = SemVer::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("major", "minor", "patch"),
            fields,
            "the SDK SemVer shape changed ($fields). If it gained prerelease " +
                "support, an alpha artifact would start being distinguishable from " +
                "the final release and this test's premise no longer holds.",
        )
    }

    /**
     * And the declared version a consumer is admitted against is the TRUNCATED
     * one. Written against the reflection path rather than the private parser so
     * it measures what admission measures.
     */
    @Test
    fun `the declared release version drops the prerelease tag`() {
        val declared = PolicyPluginDeclaration.release(javaClass.classLoader).version
        val measured = measuredReleaseVersion()
        val triple = measured.substringBefore('-').split('.').map(String::toInt)

        assertEquals(
            SemVer(triple[0], triple[1], triple[2]),
            declared,
            "the declared version no longer equals the SemVer of the measured " +
                "version with its tag removed",
        )
        assertFalse(
            declared.toString().contains('-'),
            "the SDK SemVer unexpectedly renders a prerelease; update this test " +
                "and re-check that an alpha artifact is admitted as an alpha",
        )
        assertEquals(
            measured.substringBefore('-'),
            declared.toString(),
            "expected '$measured' to publish as '${measured.substringBefore('-')}'",
        )
    }

    /**
     * The truncation is not silent elsewhere: a consumer that reads the
     * properties directly still sees the exact tag. If this fails, an alpha
     * build is indistinguishable from a final one EVERYWHERE and the release
     * must be refused rather than published.
     */
    @Test
    fun `the exact tag remains readable outside the manifest`() {
        val measured = measuredReleaseVersion()
        assertEquals(
            measured,
            PolicyCheckStepDefinition.PLUGIN_VERSION,
            "the step descriptor lost the exact tag, so nothing a consumer reads " +
                "distinguishes this alpha from the final 0.3.0",
        )
    }
}
