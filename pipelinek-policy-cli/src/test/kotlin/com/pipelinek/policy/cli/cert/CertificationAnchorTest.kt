package com.pipelinek.policy.cli.cert

import java.security.MessageDigest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B6.1 · Certification anchor.
 *
 * What this replaced: a static `cert/SHA.txt` holding a `certified-sha:` line
 * that nothing ever compared to anything, plus a `*Test.kt` file count used as
 * a proxy for "how much was certified". Both were free to drift. The marker
 * file still exists, but its SHA is now CHECKED against the source tree, and
 * the marked baseline is checked against reality instead of only against a
 * constant in this file.
 *
 * The design rule: an anchor that cannot fail is not an anchor. Every claim
 * here is falsifiable, and the mutations that would break it are named.
 */
class CertificationAnchorTest {

    /**
     * Baseline certified in M10, and re-certified on every cycle since.
     * Grow on purpose; never shrink silently.
     */
    companion object {
        const val CERTIFIED_MIN_TEST_CLASSES: Int = 42
    }

    private fun marker(): String = CertificationAnchorTest::class.java
        .getResourceAsStream("/cert/SHA.txt")
        ?.bufferedReader()?.readText()
        ?: error("cert/SHA.txt marker missing from test resources")

    private fun markerField(name: String): String = marker().lineSequence()
        .firstOrNull { it.startsWith("$name:") }
        ?.substringAfter("$name:")?.trim()
        ?: error("cert/SHA.txt must record `$name`")

    @Test
    fun `01a - certification marker carries a SHA and a suite baseline`() {
        assertTrue(marker().isNotBlank(), "marker must carry the certified SHA")
        assertTrue(
            marker().lineSequence().any { it.startsWith("baseline-suites:") },
            "marker must record the baseline suite count",
        )
    }

    /**
     * B6.1 · The certified SHA must be the SHA of the tree it certifies.
     *
     * This is the check the old anchor was missing entirely: `certified-sha`
     * was a string nobody compared, so it went stale silently while still
     * looking like a certification. Here it is compared against a digest of
     * the actual sources.
     *
     * The marker moves on purpose, and only with the reason recorded next to
     * it — never by a script that overwrites whatever it finds.
     */
    @Test
    fun `01b - the certified SHA matches the current source tree`() {
        val certified = markerField("certified-sha")
        assertEquals(40, certified.length, "certified-sha must be a full 40-char SHA, got '$certified'")

        val actual = sourceTreeSha()
        assertEquals(
            actual,
            certified,
            "cert/SHA.txt certifies $certified but the source tree hashes to $actual. " +
                "Re-certify on purpose and record why, or find out what moved.",
        )
    }

    @Test
    fun `01c - falsification - suite count below certified baseline fails`() {
        // The project ships ${CERTIFIED_MIN_TEST_CLASSES}+ test classes across
        // modules. This anchor is the executable contract: it fails loudly if
        // the certified surface shrinks.
        val knownSuites = countShippedTestClasses()
        assertTrue(
            knownSuites >= CERTIFIED_MIN_TEST_CLASSES,
            "certified baseline $CERTIFIED_MIN_TEST_CLASSES but only $knownSuites test classes found — " +
                "a module's test suite may have fallen out of the check",
        )
    }

    @Test
    fun `01d - the marked baseline is not a lie about the current tree`() {
        // 01c compares against a constant in this file; this compares against
        // the number written in the marker. They can disagree, and when they
        // do the marker is the thing that is wrong — so the marker is checked
        // too, rather than only the code.
        val marked = markerField("baseline-suites").toInt()
        val actual = countShippedTestClasses()
        assertTrue(
            actual >= marked,
            "cert/SHA.txt claims $marked suites but only $actual are present",
        )
    }

    /**
     * SHA-1 over the sorted Kotlin source paths and their contents.
     *
     * This is not `git rev-parse HEAD` and does not pretend to be: it answers
     * the only question the anchor needs answered, which is whether the
     * certified code still matches the marked code. Paths are hashed along
     * with contents so moving a file moves the anchor, and only source
     * directories are walked so a build artefact or scratch file cannot.
     */
    private fun sourceTreeSha(): String {
        val root = repoRoot()
        val digest = MessageDigest.getInstance("SHA-1")
        val files = listOf("src/main/kotlin", "src/test/kotlin")
            .map { root.resolve(it) }
            .filter { java.nio.file.Files.exists(it) }
            .flatMap { dir ->
                java.nio.file.Files.walk(dir).use { stream ->
                    stream.filter { p ->
                        !p.toString().contains("/build/") &&
                            p.fileName?.toString()?.endsWith(".kt") == true
                    }.toList()
                }
            }
            .map { root.relativize(it).toString() }
            .sorted()
        for (path in files) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(java.nio.file.Files.readAllBytes(root.resolve(path)))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun repoRoot(): java.nio.file.Path {
        var dir = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!java.nio.file.Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("no repo root above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /**
     * Counts *Test.kt files under every module's src/test. Filesystem scan
     * in TEST scope only (production purity laws are untouched; the CLI
     * module is the argv/filesystem surface by design).
     */
    private fun countShippedTestClasses(): Int {
        var count = 0
        java.nio.file.Files.walk(repoRoot()).use { stream ->
            stream.filter { p ->
                !p.toString().contains("/build/") &&
                    p.fileName?.toString()?.endsWith("Test.kt") == true
            }.forEach { count++ }
        }
        return count
    }

    @Test
    fun `corpus digest is stable for the fixed seed (feeds 04c)`() {
        val corpus = RandomDocuments.corpus(200)
        val digest = MessageDigest.getInstance("SHA-256")
        corpus.forEach { digest.update(it.toString().toByteArray(Charsets.UTF_8)) }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        // Same seed ⇒ same digest; two fresh draws MUST agree bit-for-bit.
        val again = RandomDocuments.corpus(200)
        val digest2 = MessageDigest.getInstance("SHA-256")
        again.forEach { digest2.update(it.toString().toByteArray(Charsets.UTF_8)) }
        assertEquals(
            hex,
            digest2.digest().joinToString("") { "%02x".format(it) },
            "same seed must reproduce the corpus exactly",
        )
    }
}
