package com.pipelinek.policy.arch

import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals

/**
 * REQ-M9-09 (M9 CLI y Agent DX) · kernel purity guard.
 *
 * The CLI module owns ALL filesystem/network/process surface. The domain
 * packages (kernel, ir, bundle, dsl, decoder) must not gain new I/O
 * imports: this test pins the exact audited baseline (09a). Any new
 * `java.io/java.nio/java.net/Process/Runtime` import in the domain fails
 * here BEFORE it ships.
 */
class DomainIoPurityTest {

    /** Audited baseline: exact set of allowed I/O-adjacent imports (OBSERVED 2026-10-08). */
    private val allowed: Map<String, Set<String>> = mapOf(
        "kernel/value/ValueNode.kt" to setOf("java.security.MessageDigest"),
        "kernel/policy/Waivers.kt" to setOf("java.time.Instant"),
        "ir/CanonicalPolicyJson.kt" to setOf("java.security.MessageDigest"),
        "bundle/PolicyBundle.kt" to setOf(
            "java.io.ByteArrayOutputStream",
            "java.nio.ByteBuffer",
            "java.security.MessageDigest",
        ),
        "bundle/BundleVerifier.kt" to setOf("java.nio.ByteBuffer"),
    )

    private val domainDirs = listOf(
        "kernel", "expression", "path", "policy", "selector", "value", "evaluator", "ir", "bundle", "dsl", "decoder",
    )

    @Test
    fun `09a domain packages import no filesystem network or process io`() {
        val srcRoot = Path("src/main/kotlin/com/pipelinek/policy")
        val offenders = mutableListOf<String>()
        srcRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> domainDirs.any { f.parentFile.path.endsWith(it) } }
            .forEach { f ->
                val rel = f.relativeTo(srcRoot.toFile()).invariantSeparatorsPath
                val baseline = allowed[rel] ?: emptySet()
                f.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        val imp = trimmed.removePrefix("import ").trim()
                        val isIo = imp.startsWith("java.io.") ||
                            imp.startsWith("java.nio.file") ||
                            imp.startsWith("java.net.") ||
                            imp.startsWith("javax.net.")
                        if (isIo && imp !in baseline) offenders += "$rel: $imp"
                    }
                    // Process/Runtime usage
                    if (line.contains("ProcessBuilder") || line.contains("Runtime.getRuntime")) {
                        offenders += "$rel: $trimmed"
                    }
                }
            }
        assertEquals(emptyList(), offenders.sorted(), "domain gained I/O imports beyond the audited baseline")
    }
}
