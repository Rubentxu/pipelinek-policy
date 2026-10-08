package com.pipelinek.policy.bootstrap

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Generic smoke test asserting the build environment is sane.
 *
 * Body MUST NOT reference any product-domain type — it proves the
 * Kotlin / JVM toolchain resolves to the declared versions and the
 * project compiles end-to-end with zero domain logic.
 */
class BootstrapSmokeTest {

    @Test
    fun `JVM runtime is a certified LTS (21 or 25)`() {
        // M10 (REQ 02): the compiler matrix certifies the suite on both LTS
        // toolchains (Temurin 21 and 25). The runtime must be one of them.
        org.junit.jupiter.api.Assertions.assertTrue(
            Runtime.version().feature() in setOf(21, 25),
            "runtime must be a certified LTS, got ${Runtime.version().feature()}",
        )
    }

    @Test
    fun `Kotlin runtime matches declared 2_4_20`() {
        assertEquals("2.4.20", KotlinVersion.CURRENT.toString())
    }
}
