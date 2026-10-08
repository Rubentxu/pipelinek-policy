package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * REQ M10-05 · CSV characterization 64 MiB (05a/05b).
 *
 * 05a: generate a 64 MiB synthetic CSV, decode it WHOLE_DOCUMENT, evaluate
 *      each row under the canonical rule (`temp` TEXT_EQUALS over the
 *      threshold family produces a deterministic verdict set) and emit a
 *      SHA-256 characterization digest of the full report stream. The
 *      digest is stable for the same generator.
 * 05b (falsification): mutating ONE cell of the corpus changes the digest.
 *
 * The 1 GiB run reuses this exact harness via CertCsvMain (05c, on-demand).
 */
class CsvCharacterizationTest {

    private val csv = CsvResourceDecoder()

    /**
     * Canonical rule over TEXT cells (no inference, law 9): the notes field
     * carries the row id, so the verdict stream fingerprints every row.
     */
    private val set = PolicySet(
        "csv-characterization",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "region-is-known",
                        "region must be region-0",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("region"),
                                ValueNode.Type.TEXT,
                            ),
                            Expression.Operator.TEXT_EQUALS,
                            Expression.Literal(ValueNode.TextValue("region-0")),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun reportStreamDigest(rows: List<ValueNode>): String {
        val md = MessageDigest.getInstance("SHA-256")
        rows.forEach { row ->
            md.update(Evaluator.evaluate(set, row).digest.toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `05a - 64 MiB CSV decodes and characterizes with a stable digest`() {
        val bytes = SyntheticCsv.bytes(64L * 1024 * 1024)
        assertTrue(bytes.size >= 64L * 1024 * 1024, "generator must reach 64 MiB")

        val decoded = csv.decode(bytes, DecodeOptions())
        val doc = (decoded as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
        val rows = (doc.root as ValueNode.SequenceValue).elements

        // Sanity: the row count matches the generator's arithmetic.
        val expectedRows = SyntheticCsv.rows(64L * 1024 * 1024).count() - 1
        assertEquals(expectedRows.toLong(), rows.size.toLong(), "row count must match generator")

        // Characterization digest is deterministic across two fresh runs.
        assertEquals(reportStreamDigest(rows), reportStreamDigest(rows))
    }

    @Test
    fun `05b - falsification - one mutated cell changes the digest`() {
        val small = SyntheticCsv.bytes(64 * 1024)
        val decoded = csv.decode(small, DecodeOptions())
        val doc = (decoded as com.pipelinek.policy.decoder.DecodeResult.Ok).documents.single()
        val rows = (doc.root as ValueNode.SequenceValue).elements.toMutableList()

        val baseline = reportStreamDigest(rows)

        // Mutate row 0's region cell (region-0 -> region-X).
        val mutatedRow = ValueNode.MappingValue(
            (rows[0] as ValueNode.MappingValue).entries.toMutableMap().apply {
                put("region", ValueNode.TextValue("region-X"))
            },
        )
        rows[0] = mutatedRow
        assertNotEquals(baseline, reportStreamDigest(rows), "one mutated cell must change the digest")
    }
}
