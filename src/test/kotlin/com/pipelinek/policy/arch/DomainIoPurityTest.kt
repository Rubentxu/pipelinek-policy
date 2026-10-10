package com.pipelinek.policy.arch

import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REQ-M9-09 (M9 CLI y Agent DX) + ARCH-LAW-5 · kernel purity guard.
 *
 * The CLI module owns ALL filesystem/network/process surface. The domain
 * packages (kernel, ir, bundle, dsl, decoder) must not gain new I/O
 * imports: this test pins the exact audited baseline (09a). Any new
 * `java.io/java.nio/java.net/Process/Runtime` import in the domain fails
 * here BEFORE it ships.
 *
 * ARCH-LAW-5 ("evaluator core MUST NOT perform filesystem/network/process/
 * credential/clock/random I/O") is only meaningful if the guard has no blind
 * spot. This guard previously selected files by matching the DIRECT parent
 * directory name against a hardcoded suffix list, so every domain package
 * missing from that list escaped it entirely: all of `kernel/dataset`
 * (Accumulator, DatasetPlanner, DatasetShape, StreamingEvaluator) and all of
 * `kernel/governance` (EnforcementInterpreter, EnforcementMode) were
 * unverified. A `java.nio.file.Files` import in DatasetPlanner.kt passed green.
 *
 * The selection is now the package TREE, not a name list: every `.kt` file
 * under the domain roots is covered, including files sitting directly in a
 * root and any package nested at any depth. `coveredFiles()` is asserted
 * against the whole source tree so that adding a new domain package fails
 * here instead of quietly opting out of the law.
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

    /**
     * The domain packages, named as ROOTS. Every nested package is covered
     * automatically; a new package does not need to be registered here, but
     * a new ROOT does, and [coveredFiles] fails until it is.
     */
    private val domainRoots = listOf("kernel", "ir", "bundle", "dsl", "decoder")

    private fun sourceFiles(): List<java.io.File> {
        val root = Path("src/main/kotlin/com/pipelinek/policy").toFile()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * A file is domain when it sits anywhere inside a domain root. Prefix
     * matching on the package directory, not suffix matching on a name list:
     * `kernel/dataset/DatasetPlanner.kt` is domain because it is under `kernel`,
     * not because some leaf directory happens to be called `dataset`.
     */
    private fun coveredFiles(): List<java.io.File> {
        val root = Path("src/main/kotlin/com/pipelinek/policy").toFile()
        return sourceFiles().filter { f ->
            val rel = f.parentFile.relativeTo(root).invariantSeparatorsPath
            domainRoots.any { rel == it || rel.startsWith("$it/") }
        }
    }

    private fun relPath(file: java.io.File): String =
        file.relativeTo(Path("src/main/kotlin/com/pipelinek/policy").toFile()).invariantSeparatorsPath

    @Test
    fun `the guard covers every domain file including those the old suffix list skipped`() {
        val covered = coveredFiles().map(::relPath).sorted()
        val everySourceFile = sourceFiles().map(::relPath).sorted()

        assertTrue(covered.isNotEmpty(), "the guard must cover at least one file")
        // Every file the previous name-list selection silently skipped.
        assertTrue(
            covered.containsAll(
                listOf(
                    "kernel/dataset/Accumulator.kt",
                    "kernel/dataset/DatasetPlanner.kt",
                    "kernel/dataset/DatasetShape.kt",
                    "kernel/dataset/StreamingEvaluator.kt",
                    "kernel/governance/EnforcementInterpreter.kt",
                    "kernel/governance/EnforcementMode.kt",
                    "kernel/governance/PolicyCheckPlan.kt",
                    // B4.7 ingress budget: pure by construction, and the guard
                    // must say so explicitly rather than by prefix accident.
                    "kernel/governance/ResourceIngressLimits.kt",
                ),
            ),
            "the whole domain tree must be covered: $covered",
        )
        // No duplicates: a file counted twice would let one offender be listed
        // twice and mask a different offender in the sorted comparison.
        assertEquals(covered.size, covered.distinct().size, "each file must be covered exactly once")
        // The selection must be exhaustive over the domain: every source file
        // either is domain or is deliberately outside the roots. If a new
        // package appears, this tells us the roots are stale instead of
        // letting it opt out of ARCH-LAW-5 silently.
        val outsideRoots = everySourceFile.filter { it.substringBefore('/') !in domainRoots }
        assertEquals(
            emptyList(),
            outsideRoots,
            "a source file sits outside every declared domain root; either it is " +
                "domain and the roots are stale, or it is a legitimately " +
                "non-domain package that must be declared as such",
        )
    }

    @Test
    fun `09a domain packages import no filesystem network or process io`() {
        val srcRoot = Path("src/main/kotlin/com/pipelinek/policy")
        val offenders = mutableListOf<String>()
        coveredFiles().forEach { f ->
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
