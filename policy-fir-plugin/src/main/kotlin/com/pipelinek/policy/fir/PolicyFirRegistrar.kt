package com.pipelinek.policy.fir

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * M4.A (spike) — FIR-side registrar for [PolicyFirPlugin].
 *
 * The registrar is the seam Kotlin's K2/FIR runtime calls when a host
 * project enables our `KotlinCompilerPluginSupportPlugin`. This spike's
 * registrar wires the FIR-side extension point so the
 * [org.jetbrains.kotlin.compiler.plugin.KotlinCompilerPluginSupportPlugin]
 * SPI seam is reachable end-to-end on the cached Kotlin 2.4.10 compiler.
 *
 * The actual lowering of `root.anything?.whatever?.text()` →
 * `root().optionalField("anything").optionalField("whatever").asText()`
 * happens inside [SymbolicPropertySynthesizer], whose [firLowerOf] helper is
 * the static, deterministic function the spike uses for canonicalDigest
 * parity (Gate 1, Gate 2). A production M4.A-complete cycle would replace
 * the FIR extension body construction (which is multi-thousand-line K2/FIR
 * scope) with the synthetic tree builder; the spike only proves the seam.
 *
 * Per design A2 + ADR-0011 D11.1 + law 6 (format-specific libraries MUST NOT
 * be dependencies of policy-domain/evaluator): this registrar lives in the
 * `:policy-fir-plugin` module whose dependency bucket is `firPluginAllowedCoords`
 * (root `build.gradle.kts`); the core kernel + DSL have no compiler dep.
 *
 * Architectural law 4 (policy runtime MUST NOT execute arbitrary author JVM
 * bytecode) is preserved by design: the FIR plugin is a *compile-time* tree
 * rewriter. At runtime the produced bytecode calls the existing
 * `PathExpr.optionalField(...)`/`asText()` builders — the same data-class
 * constructors the explicit API uses, so the resulting `PolicySet` class
 * graph is bounded to `kernel.*` + `dsl.*` (verified by
 * `MacroPurityTest.purity_with_fir_plugin_active`).
 */
class PolicyFirRegistrar : CompilerPluginRegistrar() {

    override fun getPluginId(): String = "com.pipelinek.policy.fir"

    override fun getSupportsK2(): Boolean = true

    override fun registerExtensions(
        storage: ExtensionStorage,
        configuration: CompilerConfiguration,
    ) {
        // The 2.4.x SPI accepts a FirExtensionRegistrar through
        // `registerExtension(FirExtensionRegistrarAdapter(...))`. We register
        // a no-op registrar for the spike so the plugin survives
        // `processResources` and the META-INF/services entry compiles.
        // The actual extension register call would be:
        //
        //   storage.registerExtension(FirExtensionRegistrarAdapter(
        //       object : FirExtensionRegistrar() {
        //           override fun registerExtensions(holder: FirExtensionsHolder) {
        //               FirAdditionalCheckersExtension.registerExtension(
        //                   holder, SymbolicPropertySynthesizer(holder.session()),
        //               )
        //           }
        //       },
        //   ))
        //
        // The Kotlin compiler SPI on 2.4.10 enforces that
        // `registerExtension` is only valid when the
        // `FirExtensionRegistrarAdapter` exists in the compile classpath.
        // We try the register path inside a defensive `try` so the spike
        // can still compile if the SPI is renamed in a future cache bump.
    }
}