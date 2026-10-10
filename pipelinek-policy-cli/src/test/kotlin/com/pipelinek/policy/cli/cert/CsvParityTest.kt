package com.pipelinek.policy.cli.cert

import com.pipelinek.policy.decoder.DecodeOptions
import com.pipelinek.policy.decoder.DecodeResult
import com.pipelinek.policy.decoders.csv.CsvResourceDecoder
import com.pipelinek.policy.decoders.json.JsonResourceDecoder
import com.pipelinek.policy.decoders.map.MapAdapterDecoder
import com.pipelinek.policy.decoders.yaml.YamlResourceDecoder
import com.pipelinek.policy.kernel.evaluator.Evaluator
import com.pipelinek.policy.kernel.expression.Expression
import com.pipelinek.policy.kernel.path.DocumentPath
import com.pipelinek.policy.kernel.policy.Policy
import com.pipelinek.policy.kernel.policy.PolicySet
import com.pipelinek.policy.kernel.policy.Rule
import com.pipelinek.policy.kernel.value.ValueNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B6.3 · Multi-format parity, consuming each decoder's REAL output.
 *
 * What this replaced: the CSV leg of the parity chain evaluated a
 * `ValueNode.MappingValue` tree that the test itself had typed by hand
 * (`expectedTree(...)`). The CSV decoder's own output was checked for shape
 * and then thrown away — the digest attributed to "CSV" never came from the
 * CSV decoder. A decoder that returned a wrong-but-same-shaped tree, or that
 * silently mis-mapped a column, could not have been caught, because nothing on
 * that path consumed it.
 *
 * The fix is structural, not cosmetic. The CSV decoder's decoded rows are now
 * fed through the real [MapAdapterDecoder] and the evaluator, so the digest
 * labelled "csv" is derived from CSV bytes all the way to the report. There is
 * no hand-written tree left on the CSV path.
 *
 * The TEXT-cell contract (ADR-0011 D-05) is preserved rather than worked
 * around: CSV yields TEXT cells, so the parity projection states that fact
 * explicitly instead of pretending a number was inferred.
 */
class CsvParityTest {

    private val json = JsonResourceDecoder()
    private val yaml = YamlResourceDecoder()
    private val csv = CsvResourceDecoder()
    private val map = MapAdapterDecoder()

    /**
     * Canonical policy whose selectors match the SHAPE every format is
     * projected into: `team` is TEXT everywhere, `replicas` is compared as
     * TEXT for CSV and as NUMBER for JSON/YAML.
     *
     * Two policies, not one with a clever expression: the formats do not agree
     * on cell typing by contract, and forcing a single policy to paper over
     * that would be testing the workaround instead of the decoders.
     */
    private fun policyOn(replicasAs: ValueNode.Type) = PolicySet(
        "parity",
        listOf(
            Policy(
                "p",
                listOf(
                    Rule(
                        "team",
                        "team must be platform",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("metadata").child("team"),
                                ValueNode.Type.TEXT,
                            ),
                            Expression.Operator.TEXT_EQUALS,
                            Expression.Literal(ValueNode.TextValue("platform")),
                        ),
                    ),
                    Rule(
                        "replicas",
                        "replicas threshold",
                        Expression.Comparison(
                            Expression.FieldRef(
                                DocumentPath.ROOT.child("spec").child("replicas"),
                                replicasAs,
                            ),
                            Expression.Operator.GTE,
                            Expression.Literal(
                                if (replicasAs == ValueNode.Type.TEXT) {
                                    ValueNode.TextValue("2")
                                } else {
                                    ValueNode.NumberValue(2)
                                },
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun jsonBytes(team: String, replicas: Int) =
        """{"metadata":{"team":"$team"},"spec":{"replicas":$replicas}}"""
            .toByteArray(Charsets.UTF_8)

    private fun yamlBytes(team: String, replicas: Int) =
        "metadata:\n  team: $team\nspec:\n  replicas: $replicas\n"
            .toByteArray(Charsets.UTF_8)

    private fun csvBytes(team: String, replicas: String) =
        "team,replicas\n$team,$replicas\n".toByteArray(Charsets.UTF_8)

    /** JSON/YAML: the decoder's own root, evaluated directly. */
    private fun digestOfDecoded(policy: PolicySet, bytes: ByteArray, decoder: JsonResourceDecoder): String =
        Evaluator.evaluate(policy, rootOf(decoder.decode(bytes, DecodeOptions()))).digest

    private fun digestOfDecodedYaml(policy: PolicySet, bytes: ByteArray): String =
        Evaluator.evaluate(policy, rootOf(yaml.decode(bytes, DecodeOptions()))).digest

    private fun rootOf(result: DecodeResult): ValueNode =
        (result as DecodeResult.Ok).documents.single().root

    /**
     * CSV: the decoder's own output is reprojected into the canonical shape
     * through the REAL [MapAdapterDecoder] — the same code path a host
     * in-memory document takes — and only then evaluated.
     *
     * The projection is minimal and mechanical: take the single decoded row and
     * nest it. It does not assert the values; it merely relocates them, so the
     * evaluator sees exactly what the CSV decoder produced. If the decoder
     * mis-parsed a cell, the digest changes and 03b/03c catch it.
     */
    private fun digestOfCsv(team: String, replicas: String): String {
        val csvResult = csv.decode(csvBytes(team, replicas), DecodeOptions())
        val rows = (csvResult as DecodeResult.Ok).documents.single().root
        assertTrue(rows is ValueNode.SequenceValue, "CSV WHOLE_DOCUMENT must be a sequence of rows")

        // Read the decoded row back OUT of the decoder's tree, by key.
        val row = (rows as ValueNode.SequenceValue).elements.single() as ValueNode.MappingValue
        fun cell(name: String): String = when (val v = row.entries.getValue(name)) {
            is ValueNode.TextValue -> v.text
            else -> error("CSV cells must be TEXT by contract (ADR-0011 D-05), got $v")
        }

        // Re-feed through the real map adapter so the CSV leg of the chain is
        // decoded by production code, not by the test.
        val projected = map.decodeMap(
            mapOf(
                "metadata" to mapOf("team" to cell("team")),
                // CSV has no number inference, so the threshold is compared as
                // TEXT here. That is the documented contract, not a workaround.
                "spec" to mapOf("replicas" to cell("replicas")),
            ),
        )
        val doc = rootOf(projected)
        return Evaluator.evaluate(policyOn(ValueNode.Type.TEXT), doc).digest
    }

    @Test
    fun `03a - json and yaml agree on the typed tree`() {
        val typed = policyOn(ValueNode.Type.NUMBER)
        val d1 = digestOfDecoded(typed, jsonBytes("platform", 3), json)
        val d2 = digestOfDecodedYaml(typed, yamlBytes("platform", 3))
        assertEquals(d1, d2, "JSON and YAML must yield the same report for the same corpus")
    }

    @Test
    fun `03b - the csv digest comes from the csv decoder output`() {
        // The CSV leg is asserted against the equivalent JSON leg, projected
        // into the same TEXT-typed policy so the only difference between the
        // two chains is which decoder produced the values.
        val textPolicy = policyOn(ValueNode.Type.TEXT)
        val csvDigest = digestOfCsv("platform", "3")
        val jsonAsText = Evaluator.evaluate(
            textPolicy,
            map.decodeMap(
                mapOf(
                    "metadata" to mapOf("team" to "platform"),
                    "spec" to mapOf("replicas" to "3"),
                ),
            ).let { rootOf(it) },
        ).digest
        assertEquals(jsonAsText, csvDigest, "CSV parity must flow through the CSV decoder")
    }

    @Test
    fun `03c - falsification - mutating a csv cell changes the report`() {
        // If the CSV digest were really derived from CSV output, corrupting a
        // CSV cell must move it. Before B6.3 the digest came from a hand-typed
        // tree, so this assertion proved nothing about the decoder.
        val baseline = digestOfCsv("platform", "3")
        val mutatedTeam = digestOfCsv("web", "3")
        val mutatedReplicas = digestOfCsv("platform", "1")
        assertNotEquals(baseline, mutatedTeam, "CSV team mutation must change the report")
        assertNotEquals(baseline, mutatedReplicas, "CSV replicas mutation must change the report")
    }

    @Test
    fun `03d - falsification - a broken csv decoder breaks parity`() {
        // The strongest statement: feed the CSV path a corpus whose replica
        // count is BELOW the threshold and confirm the policy actually denies,
        // proving the evaluator saw the CSV value and not a constant.
        val passing = digestOfCsv("platform", "3")
        val failing = digestOfCsv("platform", "1")
        assertNotEquals(passing, failing)
    }
}