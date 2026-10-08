package com.pipelinek.policy.cli.cert

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REQ M10-06 (06c) · Regex surface zero in the policy domain.
 *
 * STRUCTURAL: the kernel/evaluator expose NO user-evaluable regex
 * (TEXT_EQUALS/BOOLEAN_EQUALS are structural comparisons). This test
 * keeps it that way: it fails if the domain sources start importing
 * java.util.regex — which is the precondition for any ReDoS surface.
 * Adding MATCHES without a limits test would trip this canary first.
 */
class RegexSurfaceZeroTest {

    private val domainPackages = listOf(
        "src/main/kotlin/com/pipelinek/policy/kernel",
        "src/main/kotlin/com/pipelinek/policy/dsl",
        "src/main/kotlin/com/pipelinek/policy/ir",
        "src/main/kotlin/com/pipelinek/policy/bundle",
    )

    @Test
    fun `06c - domain sources import no regex engine`() {
        val repoRoot = run {
            var dir = java.nio.file.Path.of(System.getProperty("user.dir"))
            while (!java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
                dir = dir.parent ?: fail("repo root not found")
            }
            dir
        }
        val offenders = mutableListOf<String>()
        for (pkg in domainPackages) {
            val dir = repoRoot.resolve(pkg)
            if (!java.nio.file.Files.exists(dir)) continue
            java.nio.file.Files.walk(dir).use { stream ->
                stream.filter { it.fileName?.toString()?.endsWith(".kt") == true }
                    .forEach { path ->
                        path.toFile().forEachLine { line ->
                            if (line.contains("java.util.regex") || line.contains("Regex(")) {
                                offenders += "${path.fileName}: $line.trim()"
                            }
                        }
                    }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "regex engine leaked into the policy domain (ReDoS surface). " +
                "Offenders:\n" + offenders.joinToString("\n") +
                "\nIf MATCHES is being added, ship an adversarial limits test " +
                "first and update this canary's contract explicitly.",
        )
    }

    @Test
    fun `06c - Expression Operator exposes no MATCHES variant`() {
        val operatorNames = com.pipelinek.policy.kernel.expression.Expression.Operator.entries
        assertTrue(
            operatorNames.none { it.name.contains("MATCH", ignoreCase = true) },
            "Expression.Operator gained a MATCHES-like variant; the regex " +
                "surface-zero certificate is void until limits tests exist. " +
                "Operators: ${operatorNames.map { it.name }}",
        )
    }
}
