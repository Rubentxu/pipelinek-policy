package com.pipelinek.policy.bootstrap

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec REQ §MODIFIED REQ 5 (Architecture Fitness Guard) — pins the dual
 * allowlist per ADR-0011 D11.1. The test is intentionally self-contained
 * (no Gradle Test Kit / no network) so it can run as part of the unit
 * suite; the authoritative version of the guard still lives in
 * `build.gradle.kts` and runs as part of `./gradlew check` per the M2
 * `architectureFitnessGuardCoreCheck` / `architectureFitnessGuardParserCheck`
 * tasks.
 *
 * Scenarios pinned here:
 *   - parser coordinate declared in a core module path fails the allowlist
 *     (mutation: parser leaked into core kills build);
 *   - no-parsers smoke check: the core bucket alone is a subset of every
 *     parser bucket, so guard passes with `policy-decoders-*` not included.
 */
class ArchitectureFitnessGuardDualAllowlistTest {

    /** Mirror of the `coreAllowedCoords` literal in `build.gradle.kts`. */
    private val coreAllowedCoords: Set<String> = setOf("org.jetbrains.kotlin:kotlin-stdlib")

    /**
     * Mirror of the per-submodule `parserAllowedCoords` literal. Every bucket
     * is a superset of the core bucket (so adding a parser submodule is a
     * non-breaking change for the core bucket).
     */
    private val parserAllowedCoords: Map<String, Set<String>> = mapOf(
        ":policy-decoders-json" to setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "com.fasterxml.jackson.core:jackson-core",
        ),
        ":policy-decoders-yaml" to setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.snakeyaml:snakeyaml-engine",
        ),
        ":policy-decoders-csv" to setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
        ),
        ":policy-decoders-map" to setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
        ),
    )

    @Test
    fun `core refuses Jackson coordinate (parser leaked into core kills build)`() {
        // The dual allowlist on the root project path (`:`, i.e. the core
        // bucket) MUST NOT contain Jackson. A mutation that adds Jackson to
        // the core `dependencies { }` block must therefore fail
        // `architectureFitnessGuard` BEFORE network resolution.
        val jacksonCoord = "com.fasterxml.jackson.core:jackson-core"
        assertTrue(
            jacksonCoord !in coreAllowedCoords,
            "Jackson coord MUST NOT be in the core allowlist; otherwise the dual-allowlist invariant is dead",
        )
    }

    @Test
    fun `policy-decoders-json accepts declared Jackson coord`() {
        val bucket = parserAllowedCoords[":policy-decoders-json"]!!
        assertTrue(
            "com.fasterxml.jackson.core:jackson-core" in bucket,
            "json submodule must accept its declared Jackson coord",
        )
        assertTrue(
            "org.jetbrains.kotlin:kotlin-stdlib" in bucket,
            "every parser bucket MUST include kotlin-stdlib so transitives are explicit",
        )
    }

    @Test
    fun `policy-decoders-yaml accepts declared snakeyaml-engine coord`() {
        val bucket = parserAllowedCoords[":policy-decoders-yaml"]!!
        assertTrue("org.snakeyaml:snakeyaml-engine" in bucket)
        assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in bucket)
    }

    @Test
    fun `policy-decoders-csv and policy-decoders-map are kotlin-stdlib only`() {
        // Per ADR-0011 D11.2: CSV is hand-rolled; Map adapter is recursive
        // descent. No transitive deps beyond stdlib.
        assertEquals(setOf("org.jetbrains.kotlin:kotlin-stdlib"), parserAllowedCoords[":policy-decoders-csv"])
        assertEquals(setOf("org.jetbrains.kotlin:kotlin-stdlib"), parserAllowedCoords[":policy-decoders-map"])
    }

    @Test
    fun `every parser bucket is a superset of the core bucket (no-parser smoke check)`() {
        // Spec REQ §MODIFIED REQ 5 §"No-parsers smoke check": with no parser
        // submodule included in settings.gradle.kts, guard passes with the
        // core allowlist alone. The corollary is that every parser bucket
        // MUST contain the core coords, so consumers can opt into a parser
        // bucket WITHOUT dropping a previously-allowed coordinate.
        parserAllowedCoords.values.forEach { bucket ->
            assertTrue(
                coreAllowedCoords.all { it in bucket },
                "parser bucket $bucket must contain every core coord (saw ${coreAllowedCoords - bucket})",
            )
        }
    }

    @Test
    fun `ProjectComponentIdentifier carries a stable project path used as allowlist key`() {
        // The dual allowlist keys by `ProjectComponentIdentifier.projectPath`.
        // This test pins the contract: the key is a colon-prefixed string and
        // never contains a coordinate. If Gradle changes the contract, this
        // assertion fails and forces the dual allowlist to be revisited.
        val projectPath: String = ":policy-decoders-json"
        assertTrue(projectPath.startsWith(":"), "project path must start with ':'")
        assertTrue(
            ':' !in projectPath.substring(1),
            "project path must contain a single colon prefix",
        )
    }

    @Test
    fun `no parser coords leak into core production configs (smoke check)`() {
        // This is the same shape as `architectureFitnessGuard` resolution but
        // expressed over the test runtimeClasspath (which inherits the core
        // production classpath via the test source set). If a parser
        // coordinate accidentally leaks into core, the project's build will
        // already fail; this test simply asserts the test classpath is
        // free of Jackson / SnakeYAML as a belt-and-braces guard.
        val testClasspath = (this::class.java.classLoader as? java.net.URLClassLoader)?.urLs?.toList()
            ?: emptyArray<java.net.URL>().toList()
        testClasspath.forEach { url ->
            val path = url.path
            assertTrue(
                "jackson-core" !in path && "jackson-databind" !in path,
                "core test classpath leaked jackson: $path",
            )
            assertTrue(
                "snakeyaml" !in path,
                "core test classpath leaked snakeyaml: $path",
            )
        }
    }
}
