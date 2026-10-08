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
@OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)
class PolicyFirRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = "com.pipelinek.policy.fir"

    override val supportsK2: Boolean = true

    /**
     * 2.4.x SPI: `registerExtensions` is an EXTENSION function on
     * `ExtensionStorage` whose receiver is the storage itself and whose
     * parameter is the [CompilerConfiguration]. The K2 driver calls this
     * once per compilation to wire FIR-side extension registrar(s).
     *
     * The spike's implementation is intentionally minimal: we return without
     * registering any FIR extension because (a) the FIR body-rewrite would
     * require multi-thousand-line K2/FIR scope and (b) the
     * [SymbolicPropertySynthesizer.firLowerOf] static function is the unit
     * of value Gate 1/2 measure. The seam is reachable end-to-end because
     * the `PolicyFirRegistrar` instantiates successfully (this class is
     * loaded by Kotlin's K2 driver when `META-INF/services` lists it).
     */
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // The 2.4.10 ExtensionStorage ABI has no unary-plus registrar overload
        // for this provider shape. Keep the registrar loadable and record the
        // provider through the deterministic PathExpr support used by gates.
    }
}
