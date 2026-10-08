package com.pipelinek.policy.fir

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * M4.A (spike) — Gradle-side Kotlin compiler plugin entry point.
 *
 * `KotlinCompilerPluginSupportPlugin` is the seam the Kotlin Gradle plugin
 * uses to discover compiler plugins. The `java-gradle-plugin` Gradle plugin
 * auto-generates the `META-INF/services/org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin`
 * file by scanning the produced jar for subclasses of this class.
 *
 * Per spec REQ-FIR-Plugin-Bucket + design A2: this plugin's `getPluginArtifact()`
 * returns the artifact coordinates the Kotlin Gradle plugin will resolve
 * through the user's `pluginManagement`. The spike does NOT register any
 * `SubpluginOption` (no DSL flags), and `applyToCompilation` simply returns
 * an empty list — the actual FIR extension work is done by the
 * `PolicyFirRegistrar` (a `CompilerPluginRegistrar`) which Kotlin's K2 driver
 * discovers via the runtime classpath of the host compilation.
 *
 * The 2.4.x SPI exposes: `apply(Project)`, `isApplicable(KotlinCompilation)`,
 * `applyToCompilation(KotlinCompilation)`, `getCompilerPluginId()`,
 * `getPluginArtifact()`. `getPluginArtifactForNative()` is NOT part of the
 * 2.4.x interface (it was added in a later minor); we deliberately do NOT
 * override it so the plugin compiles against both 2.4.10 and 2.4.20.
 */
class PolicyFirPlugin : KotlinCompilerPluginSupportPlugin {

    override fun getCompilerPluginId(): String = "com.pipelinek.policy.fir"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "com.pipelinek.policy",
            artifactId = "policy-fir-plugin",
            version = "0.2.0-M4-fir-spike",
        )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project: Project = kotlinCompilation.target.project
        return project.objects.listProperty(SubpluginOption::class.java)
    }

    override fun apply(project: Project) {
        // No-op: the spike's only job is to be discoverable by the Kotlin
        // Gradle plugin (so it registers via `META-INF/services`) and to
        // carry the FIR extension SPI metadata (`PolicyFirRegistrar`).
        // Real work is done by `PolicyFirRegistrar.registerExtensions`.
    }
}
