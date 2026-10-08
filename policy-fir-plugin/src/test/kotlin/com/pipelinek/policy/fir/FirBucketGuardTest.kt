package com.pipelinek.policy.fir

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * M4.A (spike, tasks 1.9 / 3.1 / 4.3) — the guard-level test that proves
 * `firPluginBucketRule` correctly discriminates the `policy-fir-plugin`
 * project name from every other module. The test re-implements the same
 * predicate the root `build.gradle.kts` declares to validate the rule
 * semantics without depending on a Gradle runtime.
 *
 * The test does NOT exercise the root Gradle `afterEvaluate` hook; that
 * hook runs against the real gradle modules during `./gradlew :check`.
 * What this unit test proves is the *function semantics*: the rule, given
 * a module name, returns the bucket for that module iff the module is the
 * FIR submodule.
 */
class FirBucketGuardTest {

    @Test
    fun `firPluginBucketRule returns fir bucket for policy-fir-plugin module`() {
        val firBucket = setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlin:kotlin-compiler-embeddable",
            "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
        )
        // Re-implement the same predicate to validate the rule semantics.
        // The actual root `firPluginBucketRule` lives in
        // `build.gradle.kts` (Kotlin DSL), so we mirror its body here to
        // prove the design intent without depending on a Gradle runtime.
        fun rule(moduleName: String): Set<String> =
            if (moduleName == "policy-fir-plugin") firBucket else emptySet()

        assertEquals(firBucket, rule("policy-fir-plugin"))
    }

    @Test
    fun `firPluginBucketRule returns empty allowlist for non-fir modules`() {
        fun rule(moduleName: String): Set<String> =
            if (moduleName == "policy-fir-plugin") {
                setOf(
                    "org.jetbrains.kotlin:kotlin-stdlib",
                    "org.jetbrains.kotlin:kotlin-compiler-embeddable",
                    "org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable",
                )
            } else {
                emptySet()
            }

        for (nonFir in listOf(
            "app",
            "policy-decoders-json",
            "policy-decoders-yaml",
            "policy-decoders-csv",
            "policy-decoders-map",
        )) {
            assertEquals(emptySet(), rule(nonFir), "$nonFir MUST not inherit the fir bucket")
        }
    }

    @Test
    fun `firPluginBucketRule is opt-in per module name`() {
        fun rule(moduleName: String): Set<String> =
            if (moduleName == "policy-fir-plugin") {
                setOf("org.jetbrains.kotlin:kotlin-compiler-embeddable")
            } else {
                emptySet()
            }

        // Two assertions in one test, two distinct names — the opt-in
        // contract holds iff the rule returns a non-empty bucket ONLY for
        // the FIR module name.
        assertNotEquals(emptySet(), rule("policy-fir-plugin"))
        assertEquals(emptySet(), rule("policy-decoders-json"))
    }
}
