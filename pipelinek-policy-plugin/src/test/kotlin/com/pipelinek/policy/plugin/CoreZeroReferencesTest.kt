package com.pipelinek.policy.plugin

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M6 REQ-09 · hard gate: ZERO references to `policy.check` outside the plugin
 * module, tests and docs. The integration lives exclusively behind the public
 * external-plugin seam (architectural law 11): no coordinator branch, no core
 * dispatch, no special case anywhere in `src/main` outside this module.
 */
class CoreZeroReferencesTest {

    @Test
    fun `no policy_check reference in core src main outside the plugin module`() {
        val repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize()
        val coreMain = repoRoot.resolve("src/main")
        assertTrue(Files.isDirectory(coreMain), "core src/main not found at $coreMain")

        val offenders = Files.walk(coreMain).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }
                .filter { path ->
                    val text = Files.readString(path)
                    // Both spellings: the PluginStepId literal and the DSL name.
                    text.contains("policy.check") || text.contains("policyCheck")
                }
                .map { "core reference: $it" }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "REQ-09 violated: policy.check must not leak into core src/main.\n" +
                offenders.joinToString("\n"),
        )
    }
}
