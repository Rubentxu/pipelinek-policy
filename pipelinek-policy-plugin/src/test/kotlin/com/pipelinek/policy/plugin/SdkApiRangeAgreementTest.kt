package com.pipelinek.policy.plugin

import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import java.util.ServiceLoader
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R3.1.1 · The R3.1 SDK 0.48.0 live host load was first demonstrated by a
 * `/tmp/R3_1_probe.java` binary that loaded the plugin against SDK 0.48.0 jars
 * and called ServiceLoader. The R3.1 report flagged that probe as not living
 * in the repo, so the gate was not reproducible from the test suite.
 *
 * This test is the reproducible gate. It does not exercise the SDK version
 * itself — that is the *integration* dependency, set by the build through
 * `-PsdkVersion=...` and resolved at compile/test time. What it DOES exercise
 * is the contract the plugin declared in its v1 manifest for the SDK the build
 * resolved: the manifest's `apiRange` must include the resolved SDK version,
 * and the plugin's ServiceLoader entry must load against the resolved SDK's
 * `StepDefinitionContributor` class on the test classpath.
 *
 * Together: when this test is run with `-PsdkVersion=0.48.0`, it acts as the
 * R3.1 gate. When it is run with the default `-PsdkVersion=0.47.0` (what
 * `scripts/m6/certify.sh` does), it acts as the R3.0 base. The behaviour
 * tested is identical for both: the plugin-manifest contract agrees with the
 * resolved SDK version, and the ServiceLoader wires up.
 *
 * Why "0.48.0" appears as a literal here: this is a contractual assertion,
 * not a build-time test of the SDK. The 0.48.0 in the test name is the
 * PROBE TARGET — what R3.1 was measuring — not a request for the build to
 * resolve a different SDK. The build resolves whatever `-PsdkVersion` says.
 */
class SdkApiRangeAgreementTest {

    /**
     * The SDK version the build resolved into the test classpath, read from
     * the JAR's MANIFEST. The Gradle build resolves the SDK via
     * `implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))`
     * where `$sdkVersion` comes from `-PsdkVersion` (default 0.47.0). The
     * resolved version is propagated to each transitive jar's
     * `Implementation-Version` MANIFEST entry. We read it from a class
     * package that is on the test classpath unconditionally: any code that
     * imports `dev.rubentxu.pipeline.v2.domain.step.*` would not compile
     * without it, so the package is always present.
     *
     * This deliberately does NOT use a SemVer library, the BOM POM, or any
     * filesystem probing: it is a single reflective read of the JAR the
     * build resolved. If the SDK is not on the classpath, the test fails
     * to compile, not to run.
     */
    private fun resolvedSdkVersion(): String {
        val pkg = StepDefinitionContributor::class.java.`package` ?: error(
            "StepDefinitionContributor has no package metadata; the SDK is not on the test classpath"
        )
        val version = pkg.implementationVersion ?: pkg.specificationVersion ?: error(
            "the SDK package ${pkg.name} has no Implementation-Version in its MANIFEST; " +
                "the SDK was not built with `gradle jar` or the manifest was stripped"
        )
        // implementationVersion has the form "0.47.0" or "0.48.0-SNAPSHOT" etc.
        // We only care about the major.minor.patch prefix.
        return version.substringBefore("-")
    }

    /**
     * The plugin's declared apiRange from the v1 manifest. The v1 manifest is
     * emitted into META-INF/pipelinek/plugin-manifest.json by `emitPolicyManifest`
     * at build time, copied onto the test classpath via `processResources` +
     * the test classpath configuration. This is the contract a consumer's
     * admission gate reads.
     */
    private fun declaredApiRange(): String {
        val stream = requireNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/pipelinek/plugin-manifest.json")
        ) { "no plugin-manifest.json on the test classpath; the build did not run emitPolicyManifest" }
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val match = Pattern.compile("\"apiRange\"\\s*:\\s*\"([^\"]+)\"").matcher(text)
        assertTrue(match.find(), "plugin-manifest.json has no apiRange field: $text")
        return match.group(1)
    }

    /**
     * Parse a SemVer range of the form `[MAJOR.MINOR.PATCH, MAJOR.MINOR.PATCH)`
     * and check that `version` falls within it. The plugin-manifest schema
     * uses this notation. We do NOT depend on a SemVer library; the assertion
     * is one comparison per bound because the value space is well-bounded for
     * the SDK versions we support.
     */
    private fun apiRangeCovers(range: String, version: String): Boolean {
        val pattern = Pattern.compile(
            "\\[\\s*([0-9]+)\\.([0-9]+)\\.([0-9]+)\\s*,\\s*([0-9]+)\\.([0-9]+)\\.([0-9]+)\\s*\\)"
        )
        val m = pattern.matcher(range)
        assertTrue(m.matches(), "apiRange '$range' is not in the form [A.B.C, D.E.F)")
        val lo = listOf(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt())
        val hiExclusive = listOf(m.group(4).toInt(), m.group(5).toInt(), m.group(6).toInt())
        val v = version.split(".").map { it.toInt() }
        fun cmp(a: List<Int>, b: List<Int>): Int = a.zip(b).fold(0) { acc, (x, y) -> if (acc != 0) acc else x - y }
        return cmp(v, lo) >= 0 && cmp(v, hiExclusive) < 0
    }

    @Test
    fun `plugin-manifest apiRange covers the resolved SDK version`() {
        val sdk = resolvedSdkVersion()
        val apiRange = declaredApiRange()
        assertTrue(
            apiRangeCovers(apiRange, sdk),
            "plugin-manifest apiRange '$apiRange' does NOT cover the resolved SDK version $sdk. " +
                "This is the contract that a consumer admission gate would fail on.",
        )
    }

    @Test
    fun `plugin-manifest apiRange covers SDK 0 dot 48 dot 0 by contract`() {
        // R3.1 specifically asked: is 0.48.0 supported? The contract says yes
        // if the apiRange is [0.47.0, 0.49.0) or any superset. This test pins
        // that answer in the repo, independently of what the build resolved.
        val apiRange = declaredApiRange()
        assertTrue(
            apiRangeCovers(apiRange, "0.48.0"),
            "plugin-manifest apiRange '$apiRange' does NOT cover SDK 0.48.0. " +
                "R3.1 measured compatibility by execution; this test pins the contractual answer.",
        )
    }

    @Test
    fun `service loader discovers PolicyCheckContributor against the resolved SDK`() {
        // The R3.1 /tmp probe did exactly this against SDK 0.48.0. Here it is
        // against whatever the build resolved. The class name is the SDK's
        // own, so the assertion is meaningful only when the SDK is on the
        // classpath — which it is, because the test classpath is the plugin's
        // runtime classpath.
        val contributors = ServiceLoader.load(
            StepDefinitionContributor::class.java,
            javaClass.classLoader,
        ).toList()
        val policy = contributors.firstOrNull { it.id == PolicyCheckStepDefinition.PLUGIN_ID }
        assertNotNull(policy, "no StepDefinitionContributor with id ${PolicyCheckStepDefinition.PLUGIN_ID} found on the classpath")
        assertEquals(PolicyCheckStepDefinition.KEY, policy.definitions().first().contract.key)
    }

    /**
     * Falsification (AGENTS.md: for semantic fixes/features add at least one
     * mutation/negative test). If `apiRangeCovers` were a tautology (always
     * returning true), the two positive tests above would pass even if the
     * plugin declared a wrong or missing apiRange. This test pins the
     * comparator's behaviour: a version OUTSIDE the declared range must be
     * rejected.
     *
     * The plugin's v1 manifest declares `[0.47.0, 0.49.0)`. A hypothetical
     * SDK 0.99.0 must NOT be covered. If it is, the comparator is broken.
     */
    @Test
    fun `apiRange comparator rejects a version outside the declared range`() {
        val apiRange = declaredApiRange()
        assertTrue(
            !apiRangeCovers(apiRange, "0.99.0"),
            "apiRange comparator says 0.99.0 is covered by '$apiRange', but the upper bound " +
                "is exclusive. The comparator is a tautology if it does not reject 0.99.0.",
        )
    }
}
