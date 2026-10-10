package com.pipelinek.policy.plugin

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin declared its release identity in THREE places that had drifted
 * apart: `PLUGIN_VERSION` in the step definition, the `releaseVersion` literal
 * the build measured into `pipelinek-policy-release.properties`, and the
 * `Implementation-Version` in the fat-jar manifest. At the H1 release the
 * first said "0.1.0" while the root project said "0.2.0-M2", so an artifact
 * could be published that reported one identity in its step descriptor and
 * another in its plugin manifest — and the runtime admission gate reads both.
 *
 * Two of the three are now derived from the root project version. This test
 * exists to hold the third honest: `PLUGIN_VERSION` has to stay a compile-time
 * literal (the `StepContract` is a static val built before any resource is
 * readable), which means nothing at build time can prove it agrees with the
 * measured provenance. This is that proof, read from the artifact the build
 * actually produced.
 *
 * It is deliberately a resource-reading test rather than a string comparison
 * against `build.gradle.kts`: comparing literals would pass even if the
 * provenance task had stopped writing the file at all.
 */
class PluginVersionAgreementTest {

    /**
     * The provenance the BUILD measured, read from the packaged resource. This
     * is the version a consumer's admission gate sees.
     */
    private fun measuredReleaseVersion(): String {
        val resource = PolicyPluginDeclaration.RELEASE_PROPERTIES_RESOURCE
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "no $resource on the test classpath: the build did not write the release " +
                "provenance, so there is nothing to agree with"
        }
        val props = Properties()
        stream.use { props.load(it) }
        return requireNotNull(props.getProperty("pipelinek.policy.release.version")) {
            "$resource carries no pipelinek.policy.release.version"
        }
    }

    /**
     * The two identities a consumer can observe must be the same string.
     *
     * Both go through the same admission path: the manifest's release ref is
     * built from the measured provenance, while `pluginVersion` comes from the
     * step descriptor. If these disagree, a pipeline run can be attributed to
     * a plugin version that was never released.
     */
    @Test
    fun `the step descriptor version equals the measured manifest provenance`() {
        assertEquals(
            measuredReleaseVersion(),
            PolicyCheckStepDefinition.PLUGIN_VERSION,
            "the step descriptor reports a different version than the manifest " +
                "provenance the build measured; a published artifact would carry two identities",
        )
    }

    /**
     * The descriptor really does publish the constant, rather than the constant
     * being decorative and the descriptor holding something else.
     *
     * Without this, the test above could pass while `contract.descriptor` never
     * referenced `PLUGIN_VERSION` — the two values would agree and neither would
     * be the one a consumer reads.
     */
    @Test
    fun `the descriptor publishes the constant under test`() {
        assertEquals(
            PolicyCheckStepDefinition.PLUGIN_VERSION,
            PolicyCheckStepDefinition.contract.descriptor.pluginVersion,
            "the step descriptor does not publish PLUGIN_VERSION, so agreeing " +
                "with the manifest provenance proves nothing about what runs",
        )
    }

    /**
     * `parseSemVer` splits on "-" and requires three numeric components, then
     * builds a `SemVer` that the SDK compares. A version this cannot parse would
     * fail at admission, on a consumer's machine, after the artifact shipped.
     * The build guard catches the same shape at configuration time; this pins
     * it from the value actually packaged.
     */
    @Test
    fun `the version parses as the SemVer the manifest expects`() {
        val raw = measuredReleaseVersion()
        val parts = raw.split("-")[0].split(".")
        assertTrue(
            parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) },
            "'$raw' is not a SemVer triple; PolicyPluginDeclaration.parseSemVer would reject it",
        )
    }
}