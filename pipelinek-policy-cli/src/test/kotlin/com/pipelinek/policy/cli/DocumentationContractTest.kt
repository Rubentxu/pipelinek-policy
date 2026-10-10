package com.pipelinek.policy.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REQ-B6.9 · La documentación publicada no puede divergir del contrato ejecutable.
 *
 * B6.9 encontró que `docs/09-examples/EXAMPLES.md` publicaba la matriz de exit
 * codes como `0/1/2/3` con `1` = violaciones y `2` = uso, **invertida** respecto
 * a ADR-0012 y a `ExitCodes.kt`. El código estaba bien; la doc servía una
 * mentira a quien la leyera (un agente habría tratado una violación como error
 * de invocación). No lo detectaba ningún test porque los exit codes sí estaban
 * fijados por tests, pero la documentación **no estaba atada** a ellos.
 *
 * Estos tests atan la doc al código. Son guards de deriva, no snapshots:
 * parsean lo que la doc afirma y lo comparan con la fuente de verdad.
 */
class DocumentationContractTest {

    private fun repoFile(relative: String): File {
        // Walk up from the module dir until we find the repository root marker.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("no se encontró $relative subiendo desde ${File(".").absolutePath}")
    }

    private fun doc(relative: String): String = repoFile(relative).readText()

    /**
     * 01: la matriz de exit codes publicada en EXAMPLES.md debe coincidir,
     * código por código, con `ExitCodes`. Falsificado en B6.9: la doc decía
     * `1` = violaciones y `2` = uso.
     */
    @Test
    fun `01 examples doc exit codes match ExitCodes constants`() {
        val text = doc("docs/09-examples/EXAMPLES.md")
        val expected = mapOf(
            ExitCodes.OK to "OK",
            ExitCodes.USAGE to "error de uso/invocación",
            ExitCodes.VIOLATIONS to "violaciones",
            ExitCodes.EVALUATION_ERROR to "error de evaluación",
            ExitCodes.ADMISSION_ERROR to "rechazo de admisión",
            ExitCodes.COMPILER_ERROR to "error de compilación",
        )
        for ((code, meaning) in expected) {
            val pattern = Regex("`$code`[^\\n]*" + Regex.escape(meaning))
            assertTrue(pattern.containsMatchIn(text), "EXAMPLES.md no afirma que `$code` = $meaning; la doc debe reflectir ExitCodes.$code=$code")
        }
    }

    /**
     * 02: ADR-0012 es la autoridad del contrato de exit codes y su tabla debe
     * seguir al código. Si alguien cambiara `ExitCodes` sin tocar el ADR (o al
     * revés), este test falla.
     */
    @Test
    fun `02 ADR-0012 table matches ExitCodes constants`() {
        val text = doc("docs/04-adrs/ADR-0012-cli-exit-code-contract.md")
        val meanings = mapOf(
            ExitCodes.OK to "completed successfully",
            ExitCodes.USAGE to "usage error",
            ExitCodes.VIOLATIONS to "violation",
            ExitCodes.EVALUATION_ERROR to "Evaluation, configuration, plan, budget",
            ExitCodes.ADMISSION_ERROR to "admission or decoding refusal",
            ExitCodes.COMPILER_ERROR to "compilation error",
        )
        for ((code, meaning) in meanings) {
            val row = Regex("^\\|\\s*$code\\s*\\|[^|]*" + Regex.escape(meaning), RegexOption.MULTILINE)
            assertTrue(row.containsMatchIn(text), "ADR-0012 no tiene la fila `| $code | ... $meaning`; ExitCodes.$code=$code")
        }
    }

    /**
     * 03 FALSIFICATION: los exit codes de uso y violaciones NO pueden
     * intercambiarse. Este es exactamente el defecto de B6.9; si alguien
     * "corrigiera" la doc al revés, este test lo mata.
     */
    @Test
    fun `03 usage and violation codes are not swapped`() {
        assertTrue(ExitCodes.USAGE != ExitCodes.VIOLATIONS, "USAGE y VIOLATIONS colapsaron en el mismo código; el contrato los distingue")
        assertEquals(1, ExitCodes.USAGE)
        assertEquals(2, ExitCodes.VIOLATIONS)
    }

    /**
     * 04: el README declara el contrato CLI completo; debe listar los mismos
     * comandos que `CommandRegistry` (la única fuente) y en la misma forma.
     */
    @Test
    fun `04 README CLI table lists every registered command`() {
        val readme = doc("README.md")
        for (spec in CommandRegistry.commands) {
            assertTrue(readme.contains("| `${spec.name}` |"), "README no documenta el comando `${spec.name}` que CommandRegistry registra")
        }
        // The README claims nine; the registry is the source of truth.
        assertEquals(CommandRegistry.commands.size, CommandRegistry.commands.distinctBy { it.name }.size)
    }

    /**
     * 05: los paths de receipts que el README promete deben existir. Una
     * promesa documental a un fichero ausente es deriva silenciosa.
     */
    @Test
    fun `05 README receipt paths exist`() {
        val readme = doc("README.md")
        val paths = Regex("`(docs/history/[A-Za-z0-9_./-]+\\.md)`")
            .findAll(readme)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        assertTrue(paths.isNotEmpty(), "el README ya no cita ningún receipt; se perdió la evidencia")
        for (p in paths) {
            assertTrue(repoFile(p).isFile, "README cita $p pero ese fichero no existe")
        }
    }

    /**
     * 06: el README no debe volver a describirse como M0 incompleto. Esa
     * deriva (proyecto con 571 tests presentándose como smoke test) es
     * justamente lo que B6.9 corrigió; anclarlo evita la regresión.
     */
    @Test
    fun `06 README no regresses to M0 framing`() {
        val readme = doc("README.md")
        assertTrue(!readme.contains("M0 INCOMPLETE"), "README volvió a declarar 'M0 INCOMPLETE'; el proyecto ya no está en M0")
        assertTrue(readme.contains("Quickstart"))
    }
}
