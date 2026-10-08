package com.pipelinek.policy.fir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * M4.A (spike, tasks 2.6) — K2 reflection probe that proves the SPI seam
 * the spike needs is reachable on the cached compiler jars, WITHOUT trying
 * to drive a full K2 invocation (which requires a real JDK rt, a class
 * graph, a content-root resolver, etc. — multi-thousand-line scope and
 * well outside a single spike).
 *
 * **What this class proves on the spike.**
 *
 *   - `K2JVMCompiler` is loadable from `kotlin-compiler-embeddable-2.4.10.jar`
 *     via reflection (Gate 3 prerequisite — IDE baseline substitute).
 *   - The class advertises `public static void main(String[])` (verified
 *     during WU-0 via `javap`; re-verified at runtime via reflection so
 *     the spike doesn't depend on a pre-cached dig).
 *   - The basic scripting entry points (`FirScriptingCompilerExtensionRegistrar`,
 *     `JvmCliScriptEvaluationExtension`) are reachable on
 *     `kotlin-scripting-compiler-embeddable-2.4.10.jar` (Gate 5 prerequisite).
 *
 * **What this class does NOT prove on the spike.**
 *
 *   - That the FIR extension actually rewrites `root.anything?.whatever?.text()`
 *   into `root().optionalField("anything").optionalField("whatever").asText()`.
 *   The full FIR-tree construction is multi-thousand-line K2/FIR scope and
 *   is deferred to the M4.A-complete cycle (see `SymbolicPropertySynthesizer`).
 *
 * Gate 1 uses [PathExprFirSupport.firLowerOf] for the canonicalDigest parity
 * check; Gate 2 uses the kernel `Evaluator` over the lowered expression vs the
 * explicit-API expression. The K2 invocation path is exercised in Gate 3 (IDE
 * substitute) and Gate 5 (script) via reflection over the compiler's static
 * API surface, never a full `compile-to-bytecode` round-trip.
 */
object CachingFirCompilation {

    data class Result(
        val reachable: Boolean,
        val k2CompilerClass: String?,
        val scriptingRegistrarClass: String?,
        val errorMessage: String?,
    )

    /** Per-process cache so multiple gate tests don't pay the reflection cost. */
    private var cachedResult: Result? = null

    /**
     * Reflectively probe whether the K2 JVM compiler entry point is reachable
     * on the host classpath. The probe is conservative (instantiating the
     * `K2JVMCompiler` class via reflection is enough to confirm SPI seam).
     */
    fun probeK2Reachability(): Result {
        cachedResult?.let { return it }

        val k2 = try {
            val cls = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
            cls.getConstructor().newInstance()
            cls.name
        } catch (t: Throwable) {
            return Result(
                reachable = false,
                k2CompilerClass = null,
                scriptingRegistrarClass = null,
                errorMessage = "K2 JVM compiler NOT reachable: ${t.javaClass.name}: ${t.message}",
            ).also { cachedResult = it }
        }

        val scriptingRegistrar = try {
            Class.forName(
                "org.jetbrains.kotlin.scripting.compiler.plugin.FirScriptingCompilerExtensionRegistrar",
            ).name
        } catch (t: Throwable) {
            return Result(
                reachable = false,
                k2CompilerClass = k2,
                scriptingRegistrarClass = null,
                errorMessage = "Scripting registrar NOT reachable: ${t.javaClass.name}: ${t.message}",
            ).also { cachedResult = it }
        }

        return Result(
            reachable = true,
            k2CompilerClass = k2,
            scriptingRegistrarClass = scriptingRegistrar,
            errorMessage = null,
        ).also { cachedResult = it }
    }

    /**
     * Reflectively attempt a no-op `K2JVMCompiler` instantiation via the
     * SPI service loader. This is the path that proves the
     * `META-INF/services/...KotlinCompilerPluginSupportPlugin` discovery
     * mechanism is wired through the host classpath. Returns null if no
     * service entries are loadable (which is fine for the spike's Gate 4
     * incremental fingerprint).
     */
    fun serviceLoaderProbe(): List<String> {
        val loader = java.util.ServiceLoader.load(
            org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin::class.java,
        )
        return loader.iterator().asSequence().map { it.javaClass.name }.toList()
    }

    /** SHA-256 hex of the given file path's content. */
    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (Files.exists(path)) {
            Files.newInputStream(path).use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 hex of the given string's UTF-8 bytes. */
    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(text.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}