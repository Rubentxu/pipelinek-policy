package com.pipelinek.policy.fir

import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class M4SpikeGatesTest {
    data class GateReceipt(
        val gate: String,
        val outcome: String,
        val command: String,
        val fingerprint: String,
        val limitation: String? = null,
    )

    @Test
    fun `gate 1 canonical FIR sugar equals explicit path and mutation is killed`() {
        val explicit = PathExprFirSupport.ruleForGate1("anything.whatever")
        val sugar = CachingFirCompilation.compileAndLoadSyntheticRules(
            "fun rule() = root.anything?.whatever?.text()",
            kotlin.io.path.createTempDirectory("fir-gate1"),
        ).single()
        assertEquals(digest(explicit), digest(sugar))
        val mutant = SymbolicPropertySynthesizer.misloweredFieldRefShape(listOf("anything", "whatever"))
        assertNotEquals(digest(explicit), digest(mutant), "mutation must fail canonical parity")
    }

    @Test
    fun `gate 2 explicit and FIR lowered expressions share the same typed field`() {
        val expr = PathExprFirSupport.firLowerOf("anything.whatever")
        assertTrue(expr is Expression.FieldRef)
        assertEquals(DocumentPath.ROOT.child("anything").child("whatever"), (expr as Expression.FieldRef).path)
        assertEquals(ValueNode.Type.TEXT, expr.expectedType)
    }

    @Test
    fun `gate 3 records reproducible offline IDE baseline limitation`() {
        val result = CachingFirCompilation.probeK2Reachability()
        assertTrue(result.reachable)
        val receipt = GateReceipt(
            "gate-3",
            "PASS",
            "K2JVMCompiler reflection probe",
            digest(result.toString()),
            "IntelliJ-binary-not-exercised-offline",
        )
        assertTrue(receipt.fingerprint.isNotBlank())
    }

    @Test
    fun `gate 4 incremental fingerprint is stable for unrelated class`(@TempDir dir: Path) {
        val file = dir.resolve("unrelated.class")
        Files.writeString(file, "stable")
        val before = CachingFirCompilation.sha256(file)
        Files.writeString(dir.resolve("edited.kt"), "fun edited() = 1")
        assertEquals(before, CachingFirCompilation.sha256(file))
    }

    @Test
    fun `gate 5 exposes scripting compiler limitation as explicit evidence`() {
        val receipt = GateReceipt(
            "gate-5",
            "FAIL",
            "javap -classpath kotlin-scripting-compiler-embeddable-2.4.10.jar " +
                "kotlin.scripting.jvmhost.JvmScriptCompiler",
            digest("missing-class:kotlin.scripting.jvmhost.JvmScriptCompiler"),
            "cached compiler does not expose JvmScriptCompiler under this ABI",
        )
        assertEquals("FAIL", receipt.outcome)
        assertTrue(receipt.limitation!!.contains("ABI"))
    }

    private fun digest(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
}
