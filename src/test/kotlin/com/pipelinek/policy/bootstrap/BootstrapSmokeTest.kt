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
    fun `JVM runtime is JDK 21`() {
        assertEquals(21, Runtime.version().feature())
    }

    @Test
    fun `Kotlin runtime matches declared 2_4_20`() {
        assertEquals("2.4.20", KotlinVersion.CURRENT.toString())
    }
}